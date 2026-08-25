package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.approval.ApprovalAlreadyConsumedException;
import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.ApprovalState;
import com.trade.mall.agent.approval.infrastructure.InMemoryApprovalRepository;
import com.trade.mall.agent.approval.infrastructure.InMemoryAuthorizationPort;
import com.trade.mall.agent.evidence.ConfidenceLevel;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.SourceLocator;
import com.trade.mall.agent.evidence.port.RefundLogBundle;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.orchestration.infrastructure.FileDiagnosisRunStore;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.ParamsHashing;
import com.trade.mall.agent.proposal.Proposal;
import com.trade.mall.agent.proposal.VerificationPlan;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.reasoning.FindingType;
import com.trade.mall.agent.understanding.Anchor;
import com.trade.mall.agent.understanding.AnchorType;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 不依赖 JUnit 的 V3 最小回归：专门验证 durable checkpoint（耐久检查点）与审批恢复语义。 */
public final class DiagnosisCheckpointRegressionCheck {

    public static void main(String[] args) throws Exception {
        int passed = 0;
        Path dir = Files.createTempDirectory("mallagent-diagnosis-checkpoint-");

        DiagnosisRun original = richPausedRun();
        new FileDiagnosisRunStore(dir).save(original);

        // 模拟进程对象全部丢失后，用一个全新的 Store 实例重新打开同一持久目录。
        DiagnosisRun restored = new FileDiagnosisRunStore(dir).find(original.diagnosisId()).orElseThrow();
        check(restored.state() == DiagnosisState.AWAITING_APPROVAL, "AWAITING_APPROVAL 快照可从进程外恢复"); passed++;
        check(restored.evidenceBundle().items().size() == 2, "EvidenceBundle（证据集合）完整恢复"); passed++;
        check("RF-001".equals(restored.proposal().verificationPlan().correlationKey()), "Proposal（处置提议）的 refundSn 关联键完整恢复"); passed++;
        check(((FindingResult.Concluded) restored.finding()).versionSnapshot()
            .equals(new VersionSnapshot("model-a", "prompt-v1", "tool-v1")), "VersionSnapshot（版本快照）完整恢复"); passed++;

        DiagnosisRun newer = new DiagnosisRun(original.ticketSn(), original.diagnosisId(), DiagnosisState.EXECUTING, 10,
            original.anchor(), original.evidenceBundle(), original.finding(), original.proposal(), original.approvalId(), null);
        var store = new FileDiagnosisRunStore(dir);
        store.save(newer);
        boolean staleRejected = false;
        try { store.save(original); } catch (IllegalStateException expected) { staleRejected = true; }
        check(staleRejected, "旧 seq（流程序号）不能覆盖较新的 checkpoint（检查点）"); passed++;

        var ledger = new InMemoryEventLedger();
        var repo = new InMemoryApprovalRepository(ledger);
        var auth = new InMemoryAuthorizationPort();
        auth.authorize("alice", "RF-001");
        var gate = new ApprovalGate(repo, auth, () -> 1_700_000_000_000L);
        String hash = original.proposal().paramsHash();
        gate.request("APP-RF-001", "RF-001", "v1", hash);
        gate.grant("APP-RF-001", "alice");
        gate.grant("APP-RF-001", "alice"); // HTTP 重放 / grant 已落库但 Diagnosis 尚未 checkpoint
        check(repo.findByOperationId("RF-001").orElseThrow().state() == ApprovalState.GRANTED,
            "同一批准者的 GRANT（批准）可以安全重放"); passed++;
        check(ledger.countOfType("APP-RF-001", "Approval.Granted") == 1,
            "GRANT 重放不会重复写 Approval.Granted（批准事件）"); passed++;

        gate.consume("RF-001", "v1", hash);
        check("RF-001".equals(gate.recoverConsumed("RF-001", "v1", hash).operationId()),
            "已 CONSUMED（消费）但执行仍 PENDING 时可恢复同一批准能力票据"); passed++;
        boolean strictConsumeStillOneShot = false;
        try { gate.consume("RF-001", "v1", hash); }
        catch (ApprovalAlreadyConsumedException expected) { strictConsumeStillOneShot = true; }
        check(strictConsumeStillOneShot, "正常 consume（消费）语义仍保持一次性"); passed++;

        var ledger2 = new InMemoryEventLedger();
        var repo2 = new InMemoryApprovalRepository(ledger2);
        var auth2 = new InMemoryAuthorizationPort();
        auth2.authorize("bob", "RF-002");
        var gate2 = new ApprovalGate(repo2, auth2, () -> 1_700_000_000_001L);
        gate2.request("APP-RF-002", "RF-002", "v1", hash);
        gate2.request("APP-RF-002", "RF-002", "v1", hash);
        check(repo2.findByOperationId("RF-002").orElseThrow().state() == ApprovalState.PENDING,
            "相同绑定的 Approval.request（审批请求）可安全重放"); passed++;
        gate2.reject("APP-RF-002", "bob");
        gate2.reject("APP-RF-002", "bob");
        check("bob".equals(repo2.findByOperationId("RF-002").orElseThrow().approverId()),
            "REJECT（拒绝）记录真实决策人并可同人重放"); passed++;
        check(ledger2.countOfType("APP-RF-002", "Approval.Rejected") == 1,
            "REJECT 重放不会重复写拒绝事件"); passed++;

        System.out.println("==== V3 CHECKPOINT CHECKS PASSED: " + passed + " ====");
    }

    private static DiagnosisRun richPausedRun() {
        var refund = new RefundRecord(7L, "RF-001", 88L, "ORDER-001", 1,
            new BigDecimal("12.34"), "timeout", null);
        var log = new RefundLogRecord(10L, "RF-001", "ORDER-001", "CHANNEL_UNKNOWN",
            "ALIPAY", false, "TIMEOUT", "timeout", "trace-1", 1_700_000_000_000L);
        var evidence = EvidenceBundle.of("ORDER-001", List.of(
            Evidence.present("REFUND", SourceLocator.of("oms_order_refund", "id=7"), ConfidenceLevel.VERIFIED, refund),
            Evidence.present("REFUND_LOG", SourceLocator.of("oms_order_refund_log", "refundSn=RF-001"),
                ConfidenceLevel.VERIFIED, new RefundLogBundle(List.of(log)))
        ));
        var version = new VersionSnapshot("model-a", "prompt-v1", "tool-v1");
        var finding = new FindingResult.Concluded("diag-1:FINDING:1", FindingType.REFUND_STUCK_NEEDS_RETRY,
            List.of("e1", "e2"), 0.9, version);
        Map<String, String> params = Map.of(
            "orderSn", "ORDER-001", "refundSn", "RF-001", "returnApplyId", "88",
            "amount", "12.34", "currency", "CNY", "actor", "mall-agent", "note", "retry"
        );
        var proposal = new Proposal("diag-1:PROPOSAL:1", ActionType.REFUND_RETRY, params,
            ParamsHashing.sha256(params), finding.findingId(),
            new VerificationPlan("REFUND_LOG", "验证当前退款的新成功事实", "RF-001", 10L));
        return new DiagnosisRun("T-1", "diag-1", DiagnosisState.AWAITING_APPROVAL, 9,
            new Anchor(AnchorType.ORDER, "ORDER-001"), evidence, finding, proposal, "APP-RF-001", null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("[PASS] " + message);
    }
}

