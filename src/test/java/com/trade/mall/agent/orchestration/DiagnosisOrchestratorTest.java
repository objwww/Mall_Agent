package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.infrastructure.InMemoryApprovalRepository;
import com.trade.mall.agent.approval.infrastructure.InMemoryAuthorizationPort;
import com.trade.mall.agent.config.KillSwitch;
import com.trade.mall.agent.config.infrastructure.InMemoryConfigReader;
import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.evidence.application.EvidenceCollectionService;
import com.trade.mall.agent.evidence.collector.AfterSaleEvidenceCollector;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.collector.OrderEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundLogEvidenceCollector;
import com.trade.mall.agent.evidence.infrastructure.InMemoryAfterSaleReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryOrderReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundLogReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundReadPort;
import com.trade.mall.agent.evidence.port.OrderRecord;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.execution.application.DefaultActionDispatcher;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.InMemoryAttemptSequence;
import com.trade.mall.agent.execution.infrastructure.ScriptedActionPort;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.DefaultLlmRegistry;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.ScriptedLlmClient;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.RemediationProposerService;
import com.trade.mall.agent.reasoning.ReasoningService;
import com.trade.mall.agent.understanding.TicketUnderstandingService;
import com.trade.mall.agent.verification.RecoveryVerifier;
import com.trade.mall.agent.verification.VerifyResult;
import com.trade.mall.agent.verification.infrastructure.RefundLogFactSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §87-94 一一对应——`M-ORCH-01` ★端到端闭环的 JUnit 复现：把 D5-D8
 * 全部能力（理解/取证/判定/提议/批准/执行/验证）用同一本 {@link InMemoryEventLedger}
 * 接线到 {@link DiagnosisOrchestrator}，覆盖 `domain_model_and_invariants.md` §4
 * 三条最容易做错的分支（VERIFY_UNAVAILABLE 独立、NOT_RECOVERED 回到 REASONING、
 * NO_CONCLUSION 不强行下结论）以及批准三态（GRANT/REJECT/LET_EXPIRE）。
 */
class DiagnosisOrchestratorTest {

    static final long NOW = 1_700_000_000_000L;

    /** 可编排的非资金动作执行器测试替身，与 D2 {@code ScriptedActionPort} 同一取向。 */
    static final class ScriptedNonFundActionExecutor implements NonFundActionExecutor {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public void execute(ActionType actionType, Map<String, String> params) { calls.incrementAndGet(); }
        int callCount() { return calls.get(); }
    }

    record Env(
        InMemoryEventLedger ledger,
        InMemoryOrderReadPort orderPort,
        InMemoryRefundReadPort refundPort,
        InMemoryRefundLogReadPort refundLogPort,
        ExecutorService pool,
        InMemoryApprovalRepository approvalRepo,
        InMemoryAuthorizationPort authPort,
        ScriptedActionPort actionPort,
        ScriptedNonFundActionExecutor nonFundExecutor,
        DiagnosisOrchestrator orchestrator
    ) {}

    private Env fresh(ScriptedLlmClient client) {
        var ledger = new InMemoryEventLedger();
        var factory = new InMemoryLlmClientFactory().register("modelA", () -> client);
        var registry = new DefaultLlmRegistry(factory, ledger, new InMemoryAlertPort(),
            new InMemoryPromptVersionStore("v1", "system prompt v1"), "tool-schema-v1",
            "modelA", Duration.ofSeconds(30), () -> NOW);
        var understandingService = new TicketUnderstandingService(registry, ledger, () -> NOW);

        var orderPort = new InMemoryOrderReadPort();
        var refundPort = new InMemoryRefundReadPort();
        var afterSalePort = new InMemoryAfterSaleReadPort();
        var refundLogPort = new InMemoryRefundLogReadPort();
        var pool = Executors.newFixedThreadPool(4);
        List<EvidenceCollector<?>> collectors = List.of(
            new OrderEvidenceCollector(orderPort), new RefundEvidenceCollector(refundPort),
            new AfterSaleEvidenceCollector(afterSalePort), new RefundLogEvidenceCollector(refundLogPort));
        var evidenceService = new EvidenceCollectionService(collectors, ledger, pool, Duration.ofSeconds(2), () -> NOW);

        var reasoningService = new ReasoningService(registry, ledger, () -> NOW);
        var proposerService = new RemediationProposerService(ledger, () -> NOW);

        var approvalRepo = new InMemoryApprovalRepository(ledger);
        var authPort = new InMemoryAuthorizationPort();
        var approvalGate = new ApprovalGate(approvalRepo, authPort, () -> NOW);

        var executionRepo = new InMemoryActionExecutionRepository(ledger);
        var killSwitch = new KillSwitch(new InMemoryConfigReader().set(true));
        var actionPort = new ScriptedActionPort();
        var actionDispatcher = new DefaultActionDispatcher(killSwitch,
            new ExecutionApplicationService(executionRepo, () -> NOW), ledger, actionPort, new InMemoryAttemptSequence());

        var nonFundExecutor = new ScriptedNonFundActionExecutor();
        var recoveryVerifier = new RecoveryVerifier(List.of(new RefundLogFactSource(refundLogPort)), ledger, () -> NOW);

        var orchestrator = new DiagnosisOrchestrator(understandingService, evidenceService, reasoningService,
            proposerService, approvalGate, executionRepo, actionDispatcher, nonFundExecutor, recoveryVerifier, ledger, () -> NOW);

        return new Env(ledger, orderPort, refundPort, refundLogPort, pool, approvalRepo, authPort, actionPort, nonFundExecutor, orchestrator);
    }

    /** ★ 全项目端到端核心论点：理解→取证→判定→提议→批准→真实执行→独立验证→RESOLVED。 */
    @Test void fundAction_fullHappyPath_grantedThenResolved() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-e2e\",\"symptoms\":[\"退款一直显示处理中\"],\"confidence\":0.85}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + EvidenceEventIds.collected("diag-e2e", "REFUND") + "\",\""
                + EvidenceEventIds.collected("diag-e2e", "REFUND_LOG") + "\"],\"confidence\":0.88}");
        var env = fresh(client);
        env.refundPort().put(new RefundRecord(1, "rf-e2e", 1001L, "order-e2e", 1, new BigDecimal("100.00"), null, NOW));
        env.refundLogPort().add(new RefundLogRecord(1, "rf-e2e", "order-e2e", "CHANNEL_FAILED", "ALIPAY", false, "E001", "渠道超时", "trace-e2e", NOW));
        env.authPort().authorize("alice", "rf-e2e");

        var paused = env.orchestrator().runToApproval("ticket-e2e", "diag-e2e", "我的退款一直显示处理中");
        assertEquals(DiagnosisState.AWAITING_APPROVAL, paused.state());
        assertEquals(0, env.actionPort().callCount("rf-e2e"), "批准之前执行端口不应被触碰");

        // 独立日志源新增 CHANNEL_SUCCESS——RecoveryVerifier 真正查询到的证据
        env.refundLogPort().add(new RefundLogRecord(2, "rf-e2e", "order-e2e", "CHANNEL_SUCCESS", "ALIPAY", true, null, null, "trace-e2e-2", NOW));
        env.actionPort().scriptOutcome("rf-e2e", new PortOutcome.Success("wx-ref-e2e"));

        var resolved = env.orchestrator().resumeAfterApproval(paused, ApprovalDecision.GRANT, "alice");

        assertEquals(DiagnosisState.RESOLVED, resolved.state());
        assertEquals(1, env.actionPort().callCount("rf-e2e"), "批准换来的一次性发出，INV-EXEC-001 全程守住");
        assertInstanceOf(VerifyResult.Recovered.class, resolved.verifyResult());
        env.pool().shutdownNow();
    }

    @Test void approvalRejected_endsAtRejected_executionNeverTouched() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-rej\",\"symptoms\":[\"退款一直显示处理中\"],\"confidence\":0.8}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + EvidenceEventIds.collected("diag-rej", "REFUND") + "\",\""
                + EvidenceEventIds.collected("diag-rej", "REFUND_LOG") + "\"],\"confidence\":0.8}");
        var env = fresh(client);
        env.refundPort().put(new RefundRecord(1, "rf-rej", 1002L, "order-rej", 1, new BigDecimal("60.00"), null, NOW));
        env.refundLogPort().add(new RefundLogRecord(1, "rf-rej", "order-rej", "CHANNEL_FAILED", "ALIPAY", false, "E002", "渠道失败", "trace-rej", NOW));
        env.authPort().authorize("bob", "rf-rej");

        var paused = env.orchestrator().runToApproval("ticket-rej", "diag-rej", "退款卡住了");
        var result = env.orchestrator().resumeAfterApproval(paused, ApprovalDecision.REJECT, "bob");

        assertEquals(DiagnosisState.REJECTED, result.state());
        assertEquals(0, env.actionPort().callCount("rf-rej"));
        env.pool().shutdownNow();
    }

    @Test void approvalExpired_endsAtExpired() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-exp\",\"symptoms\":[\"退款一直显示处理中\"],\"confidence\":0.8}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + EvidenceEventIds.collected("diag-exp", "REFUND") + "\",\""
                + EvidenceEventIds.collected("diag-exp", "REFUND_LOG") + "\"],\"confidence\":0.8}");
        var env = fresh(client);
        env.refundPort().put(new RefundRecord(1, "rf-exp", 1003L, "order-exp", 1, new BigDecimal("60.00"), null, NOW));
        env.refundLogPort().add(new RefundLogRecord(1, "rf-exp", "order-exp", "CHANNEL_FAILED", "ALIPAY", false, "E003", "渠道失败", "trace-exp", NOW));

        var paused = env.orchestrator().runToApproval("ticket-exp", "diag-exp", "退款卡住了");
        var result = env.orchestrator().resumeAfterApproval(paused, ApprovalDecision.LET_EXPIRE, "system");

        assertEquals(DiagnosisState.EXPIRED, result.state());
        assertEquals(0, env.actionPort().callCount("diag-exp:PROPOSAL:1"));
        env.pool().shutdownNow();
    }

    @Test void orderStatusRepair_withoutPaymentGatewayEvidence_neverExecutes() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-nonfund\",\"symptoms\":[\"支付成功但订单状态未更新\"],\"confidence\":0.78}")
            .scriptResponse("{\"findingType\":\"ORDER_STATUS_NOT_SYNCED\",\"evidenceIds\":[\""
                + EvidenceEventIds.collected("diag-nonfund", "ORDER") + "\"],\"confidence\":0.78}");
        var env = fresh(client);
        env.orderPort().put(new OrderRecord(1, "order-nonfund", 1, "tester", new BigDecimal("199.00"), NOW));

        var result = env.orchestrator().runToApproval("ticket-nonfund", "diag-nonfund", "付款成功了，订单一直显示未支付");

        assertFalse(result.isPausedAtApproval());
        assertEquals(DiagnosisState.ESCALATED_HUMAN, result.state());
        assertInstanceOf(com.trade.mall.agent.reasoning.FindingResult.NoConclusion.class, result.finding(),
            "缺少支付网关证据时必须在推理阶段停止，不能先修改订单再验证");
        assertNull(result.verifyResult(), "未执行动作时不应伪造恢复验证结果");
        assertEquals(0, env.nonFundExecutor().callCount(), "证据不足时不得执行订单状态修复");
        env.pool().shutdownNow();
    }

    /** ★ 三个最容易做错的分支之一：NOT_RECOVERED 回到 REASONING，不是重新 dispatch 同一个提议。 */
    @Test void notRecovered_routesBackToReasoning_neverRedispatches() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-nr\",\"symptoms\":[\"退款一直显示处理中\"],\"confidence\":0.8}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + EvidenceEventIds.collected("diag-nr", "REFUND") + "\",\""
                + EvidenceEventIds.collected("diag-nr", "REFUND_LOG") + "\"],\"confidence\":0.8}");
        var env = fresh(client);
        env.refundPort().put(new RefundRecord(1, "rf-nr", 1004L, "order-nr", 1, new BigDecimal("60.00"), null, NOW));
        // 诊断阶段需要真实退款日志，但验证阶段故意没有任何 CHANNEL_SUCCESS。
        env.refundLogPort().add(new RefundLogRecord(1, "rf-nr", "order-nr", "CHANNEL_FAILED", "ALIPAY", false, "E004", "渠道失败", "trace-nr", NOW));
        env.authPort().authorize("carol", "rf-nr");

        var paused = env.orchestrator().runToApproval("ticket-nr", "diag-nr", "退款卡住了");
        env.actionPort().scriptOutcome("rf-nr", new PortOutcome.Success("wx-ref-nr"));
        var result = env.orchestrator().resumeAfterApproval(paused, ApprovalDecision.GRANT, "carol");

        assertEquals(DiagnosisState.REASONING, result.state(), "不重复上一动作：回到 REASONING，不是 EXECUTING");
        assertInstanceOf(VerifyResult.NotRecovered.class, result.verifyResult());
        assertEquals(1, env.actionPort().callCount("rf-nr"), "NOT_RECOVERED 之后不应自动重新 dispatch 同一提议");
        env.pool().shutdownNow();
    }

    @Test void anchorMissing_shortCircuitsToEscalatedHuman() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorMissing\":true,\"reason\":\"工单里没有提到任何订单号或退款单号\"}");
        var env = fresh(client);

        var result = env.orchestrator().runToApproval("ticket-am", "diag-am", "东西质量不太好");

        assertEquals(DiagnosisState.ESCALATED_HUMAN, result.state());
        assertNull(result.evidenceBundle(), "理解阶段就短路，从未进入取证");
        assertEquals(1, client.callCount());
        env.pool().shutdownNow();
    }

    @Test void evidenceInsufficient_allUnavailable_shortCircuitsToEscalatedHuman() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-ei\",\"symptoms\":[\"退款卡住\"],\"confidence\":0.7}");
        var env = fresh(client);
        env.orderPort().disconnect();
        env.refundPort().disconnect();
        env.refundLogPort().disconnect();

        var result = env.orchestrator().runToApproval("ticket-ei", "diag-ei", "退款卡住了");

        assertEquals(DiagnosisState.ESCALATED_HUMAN, result.state());
        assertNull(result.finding(), "证据阶段就短路，从未进入判定");
        assertEquals(1, client.callCount());
        env.pool().shutdownNow();
    }

    @Test void noConclusion_shortCircuitsToEscalatedHuman_neverForcedToProposal() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-nc\",\"symptoms\":[\"退款卡住\"],\"confidence\":0.6}")
            .scriptResponse("{\"noConclusion\":true,\"reason\":\"现有证据不足以支持任何判定\"}");
        var env = fresh(client);
        env.refundPort().put(new RefundRecord(1, "rf-nc", "order-nc", 2, new BigDecimal("60.00"), null, NOW));

        var result = env.orchestrator().runToApproval("ticket-nc", "diag-nc", "退款卡住了");

        assertEquals(DiagnosisState.ESCALATED_HUMAN, result.state());
        assertNull(result.proposal(), "NoConclusion 不应被强行提议成任何处置动作");
        env.pool().shutdownNow();
    }
}
