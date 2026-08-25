package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundLogReadPort;
import com.trade.mall.agent.evidence.ConfidenceLevel;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.SourceLocator;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.ParamsHashing;
import com.trade.mall.agent.proposal.RemediationProposerService;
import com.trade.mall.agent.proposal.VerificationPlan;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.reasoning.FindingType;
import com.trade.mall.agent.understanding.Anchor;
import com.trade.mall.agent.understanding.AnchorType;
import com.trade.mall.agent.verification.RecoveryVerifier;
import com.trade.mall.agent.verification.VerifyResult;
import com.trade.mall.agent.verification.infrastructure.RefundLogFactSource;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;

/** 不依赖 JUnit 的最小回归检查：只覆盖本轮修复的真实反例。 */
public final class MinimalFixRegressionCheck {
    private static int checks;

    public static void main(String[] args) {
        responseWithoutStatusMustNotBecomeSuccess();
        realRefundViewStatusIsMappedStrictly();
        operationIdIsInjectedIntoJsonObject();
        refundProposalBindsRealMallCommandFieldsBeforeApproval();
        paramsEncodingMustNotBeStructurallyAmbiguous();
        historicalSuccessMustNotConfirmAnotherRefund();
        onlyNewSuccessAfterBaselineCanConfirmRecovery();
        System.out.println("MINIMAL FIX REGRESSION: " + checks + " checks passed");
    }

    private static void responseWithoutStatusMustNotBecomeSuccess() {
        PortOutcome out = HttpMallRefundActionPort.mapResponse(200, "{\"refundSn\":\"rf-1\"}");
        check(out instanceof PortOutcome.Inconclusive, "2xx without status must be Inconclusive");
    }

    private static void realRefundViewStatusIsMappedStrictly() {
        PortOutcome ok = HttpMallRefundActionPort.mapResponse(200,
            "{\"operationId\":\"op-1\",\"refundSn\":\"rf-1\",\"status\":\"SUCCEEDED\",\"error\":null}");
        check(ok instanceof PortOutcome.Success, "SUCCEEDED must map to Success");

        PortOutcome processing = HttpMallRefundActionPort.mapResponse(200,
            "{\"operationId\":\"op-1\",\"refundSn\":\"rf-1\",\"status\":\"PROCESSING\",\"error\":null}");
        check(processing instanceof PortOutcome.Inconclusive, "PROCESSING must stay inconclusive");
    }

    private static void operationIdIsInjectedIntoJsonObject() {
        String body = HttpMallRefundActionPort.addOperationId("{\"amount\":\"10.00\"}", "op-1");
        check(body.equals("{\"operationId\":\"op-1\",\"amount\":\"10.00\"}"), "operationId injection");
    }

    private static void refundProposalBindsRealMallCommandFieldsBeforeApproval() {
        long now = 1_700_000_000_000L;
        var ledger = new InMemoryEventLedger();
        var proposer = new RemediationProposerService(ledger, () -> now);
        var refund = new RefundRecord(77L, "RF-AFTER-77", 9007L, "order-77", 1,
            new BigDecimal("88.50"), null, null);
        var bundle = EvidenceBundle.of("order-77", List.of(
            Evidence.present("REFUND", SourceLocator.of("oms_order_refund", "77"),
                ConfidenceLevel.VERIFIED, refund)
        ));
        var finding = new FindingResult.Concluded("diag-77:FINDING:1",
            FindingType.REFUND_STUCK_NEEDS_RETRY, List.of("e-refund-77"), 0.9,
            new VersionSnapshot("model-a", "prompt-v1", "tool-v1"));

        var proposal = proposer.propose("diag-77", new Anchor(AnchorType.ORDER, "order-77"), finding, bundle);
        check("9007".equals(proposal.params().get("returnApplyId")), "proposal binds real returnApplyId");
        check("88.50".equals(proposal.params().get("amount")), "proposal binds real refund amount");
        check("CNY".equals(proposal.params().get("currency")), "proposal binds currency before approval");
        check("mall-agent".equals(proposal.params().get("actor")), "proposal binds actor before approval");
        check(!proposal.params().containsKey("orderSn"), "wire params are the real AgentRefundCommand fields, not old orderSn-only placeholder");
        check("RF-AFTER-77".equals(proposal.verificationPlan().correlationKey()), "refundSn remains the stable business correlation/idempotency key");
    }

    private static void paramsEncodingMustNotBeStructurallyAmbiguous() {
        Map<String,String> one = Map.of("a", "x&b=y");
        Map<String,String> two = Map.of("a", "x", "b", "y");
        check(!ParamsHashing.canonicalJson(one).equals(ParamsHashing.canonicalJson(two)), "canonical JSON differs");
        check(!ParamsHashing.sha256(one).equals(ParamsHashing.sha256(two)), "hash differs for structurally different params");
    }

    private static void historicalSuccessMustNotConfirmAnotherRefund() {
        long now = 1_700_000_000_000L;
        var port = new InMemoryRefundLogReadPort();
        port.add(new RefundLogRecord(1, "rf-old", "order-1", "CHANNEL_SUCCESS", "ALIPAY", true, null, null, "t-old", now));
        port.add(new RefundLogRecord(2, "rf-current", "order-1", "CHANNEL_FAILED", "ALIPAY", false, "E", "failed", "t-now", now));
        var verifier = new RecoveryVerifier(java.util.List.of(new RefundLogFactSource(port)), new InMemoryEventLedger(), () -> now);
        VerifyResult result = verifier.verify("op-1", "order-1", ActionType.REFUND_RETRY,
            new VerificationPlan("REFUND_LOG", "verify current refund", "rf-current", 0));
        check(result instanceof VerifyResult.NotRecovered, "old success from another refund must not confirm current refund");
    }

    private static void onlyNewSuccessAfterBaselineCanConfirmRecovery() {
        long now = 1_700_000_000_000L;
        var port = new InMemoryRefundLogReadPort();
        port.add(new RefundLogRecord(10, "rf-current", "order-2", "CHANNEL_SUCCESS", "ALIPAY", true, null, null, "t-old", now));
        var verifier = new RecoveryVerifier(java.util.List.of(new RefundLogFactSource(port)), new InMemoryEventLedger(), () -> now);
        VerificationPlan plan = new VerificationPlan("REFUND_LOG", "verify current refund", "rf-current", 10);
        VerifyResult before = verifier.verify("op-2a", "order-2", ActionType.REFUND_RETRY, plan);
        check(before instanceof VerifyResult.NotRecovered, "success at/before baseline must not count");
        port.add(new RefundLogRecord(11, "rf-current", "order-2", "CHANNEL_SUCCESS", "ALIPAY", true, null, null, "t-new", now + 1));
        VerifyResult after = verifier.verify("op-2b", "order-2", ActionType.REFUND_RETRY, plan);
        check(after instanceof VerifyResult.Recovered, "new success after baseline must count");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}

