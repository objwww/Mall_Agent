package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.infrastructure.InMemoryApprovalRepository;
import com.trade.mall.agent.approval.infrastructure.InMemoryAuthorizationPort;
import com.trade.mall.agent.config.KillSwitch;
import com.trade.mall.agent.config.infrastructure.InMemoryConfigReader;
import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.evidence.application.EvidenceCollectionService;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.collector.OrderEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundLogEvidenceCollector;
import com.trade.mall.agent.evidence.infrastructure.InMemoryOrderReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundLogReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundReadPort;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.execution.application.DefaultActionDispatcher;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.InMemoryAttemptSequence;
import com.trade.mall.agent.execution.infrastructure.ScriptedActionPort;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.DefaultLlmRegistry;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.ScriptedLlmClient;
import com.trade.mall.agent.proposal.RemediationProposerService;
import com.trade.mall.agent.reasoning.ReasoningService;
import com.trade.mall.agent.understanding.TicketUnderstandingService;
import com.trade.mall.agent.verification.RecoveryVerifier;
import com.trade.mall.agent.verification.infrastructure.RefundLogFactSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** V8：Diagnosis（诊断）终态后自动 release（释放）LLM pin（模型钉住引用）的最小检查。 */
public final class DiagnosisTerminalResourceRegressionCheck {
    private static final long NOW = 1_700_000_000_000L;
    private static int passed;

    public static void main(String[] args) {
        terminalCheckpointReleasesOnlyAfterSave();
        awaitingApprovalKeepsPin();
        System.out.println("V8 diagnosis terminal resource regression: " + passed + "/5 passed");
    }

    private static void terminalCheckpointReleasesOnlyAfterSave() {
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorMissing\":true,\"reason\":\"没有订单号\"}");
        try (Env env = fresh(client)) {
            var callback = new java.util.function.Consumer<String>() {
                @Override public void accept(String diagnosisId) {
                    require(env.store.find(diagnosisId).map(DiagnosisRun::isTerminal).orElse(false),
                        "终态资源必须在 checkpoint（检查点）成功保存之后才释放");
                    env.registry.release(diagnosisId);
                }
            };
            var orch = env.orchestrator(callback);
            DiagnosisRun terminal = orch.runToApproval("ticket-terminal", "diag-terminal", "没有订单号");
            require(terminal.isTerminal(), "AnchorMissing（锚点缺失）应收敛到终态");
            requireThrows(() -> env.registry.forPinned("diag-terminal"),
                "终态 Diagnosis 保存成功后必须自动释放 LLM pin");
        }
    }

    private static void awaitingApprovalKeepsPin() {
        String diag = "diag-wait";
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-wait\",\"symptoms\":[\"退款处理中\"],\"confidence\":0.9}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + EvidenceEventIds.collected(diag, "REFUND") + "\",\""
                + EvidenceEventIds.collected(diag, "REFUND_LOG") + "\"],\"confidence\":0.9}");
        try (Env env = fresh(client)) {
            env.refunds.put(new RefundRecord(1, "rf-wait", 123L, "order-wait", 1,
                new BigDecimal("12.34"), null, NOW));
            env.refundLogs.add(new RefundLogRecord(1, "rf-wait", "order-wait", "CHANNEL_FAILED",
                "ALIPAY", false, "E", "failed", "trace", NOW));
            var orch = env.orchestrator(env.registry::release);
            DiagnosisRun paused = orch.runToApproval("ticket-wait", diag, "退款处理中");
            require(paused.state() == DiagnosisState.AWAITING_APPROVAL,
                "需要审批的资金动作应暂停在 AWAITING_APPROVAL（等待审批）");
            require(env.registry.forPinned(diag) == client,
                "非终态 checkpoint 不能释放 LLM pin，后续恢复仍需同一版本");
        }
    }

    private static Env fresh(ScriptedLlmClient client) {
        var ledger = new InMemoryEventLedger();
        var registry = new DefaultLlmRegistry(
            new InMemoryLlmClientFactory().register("modelA", () -> client), ledger, new InMemoryAlertPort(),
            new InMemoryPromptVersionStore("v1", "system prompt"), "tool-v1", "modelA",
            Duration.ofSeconds(1), () -> NOW);
        var pool = Executors.newFixedThreadPool(2);
        var orders = new InMemoryOrderReadPort();
        var refunds = new InMemoryRefundReadPort();
        var refundLogs = new InMemoryRefundLogReadPort();
        List<EvidenceCollector<?>> collectors = List.of(
            new OrderEvidenceCollector(orders), new RefundEvidenceCollector(refunds), new RefundLogEvidenceCollector(refundLogs));
        var store = new MapRunStore();
        return new Env(ledger, registry, pool, orders, refunds, refundLogs, collectors, store);
    }

    private record Env(
        InMemoryEventLedger ledger,
        DefaultLlmRegistry registry,
        ExecutorService pool,
        InMemoryOrderReadPort orders,
        InMemoryRefundReadPort refunds,
        InMemoryRefundLogReadPort refundLogs,
        List<EvidenceCollector<?>> collectors,
        MapRunStore store
    ) implements AutoCloseable {
        DiagnosisOrchestrator orchestrator(java.util.function.Consumer<String> terminalReleaser) {
            var understanding = new TicketUnderstandingService(registry, ledger, () -> NOW);
            var evidence = new EvidenceCollectionService(collectors, ledger, pool, Duration.ofSeconds(1), () -> NOW);
            var reasoning = new ReasoningService(registry, ledger, () -> NOW);
            var proposer = new RemediationProposerService(ledger, () -> NOW);
            var approvalRepo = new InMemoryApprovalRepository(ledger);
            var approvalGate = new ApprovalGate(approvalRepo, new InMemoryAuthorizationPort(), () -> NOW);
            var executionRepo = new InMemoryActionExecutionRepository(ledger);
            var dispatcher = new DefaultActionDispatcher(new KillSwitch(new InMemoryConfigReader().set(true)),
                new ExecutionApplicationService(executionRepo, () -> NOW), ledger,
                new ScriptedActionPort(), new InMemoryAttemptSequence());
            var verifier = new RecoveryVerifier(List.of(new RefundLogFactSource(refundLogs)), ledger, () -> NOW);
            return new DiagnosisOrchestrator(understanding, evidence, reasoning, proposer, approvalGate,
                executionRepo, dispatcher, (type, params) -> {}, verifier, ledger, () -> NOW, store, terminalReleaser);
        }
        @Override public void close() { pool.shutdownNow(); registry.close(); }
    }

    private static final class MapRunStore implements DiagnosisRunStore {
        private final Map<String, DiagnosisRun> runs = new ConcurrentHashMap<>();
        @Override public void save(DiagnosisRun run) { runs.put(run.diagnosisId(), run); }
        @Override public Optional<DiagnosisRun> find(String diagnosisId) { return Optional.ofNullable(runs.get(diagnosisId)); }
    }

    private static void requireThrows(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalStateException expected) { passed++; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}

