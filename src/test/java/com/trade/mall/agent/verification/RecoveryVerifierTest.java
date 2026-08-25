package com.trade.mall.agent.verification;

import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundLogReadPort;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.VerificationPlan;
import com.trade.mall.agent.verification.infrastructure.RefundLogFactSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §80-84 一一对应。核心论点：{@link VerifyResult.VerifyUnavailable} 与
 * {@link VerifyResult.NotRecovered} 是两件完全不同的事（`INV-VERIFY-002`）——独立事实源
 * 本身连不上，绝不能被误判为"问题真的没解决"；同源验证方案必须在 {@link RecoveryVerifier}
 * 自己的入口就被拒绝，不依赖调用方已经用 {@code Proposal} 构造过。
 */
class RecoveryVerifierTest {

    static final long NOW = 1_700_000_000_000L;

    @Test void sameSourceVerificationPlan_rejectedAtEntry_beforeAnyQueryOrEvent() {
        var ledger = new InMemoryEventLedger();
        var verifier = new RecoveryVerifier(List.of(new RefundLogFactSource(new InMemoryRefundLogReadPort())), ledger, () -> NOW);

        assertThrows(SameSourceVerificationException.class, () -> verifier.verify("op-80", "order-80",
            ActionType.REFUND_RETRY, new VerificationPlan("REFUND_CHANNEL_API", "同源，应被拒绝")));
        assertTrue(ledger.eventsOf("op-80").isEmpty(), "同源检查必须发生在任何查询/事件写入之前");
    }

    @Test void unregisteredSourceType_yieldsVerifyUnavailable_honestCapabilityGap() {
        var ledger = new InMemoryEventLedger();
        var verifier = new RecoveryVerifier(List.of(new RefundLogFactSource(new InMemoryRefundLogReadPort())), ledger, () -> NOW);

        VerifyResult result = verifier.verify("op-81", "order-81", ActionType.ORDER_STATUS_RESYNC,
            new VerificationPlan("PAYMENT_GATEWAY_QUERY", "查支付网关"));

        assertInstanceOf(VerifyResult.VerifyUnavailable.class, result);
        assertTrue(((VerifyResult.VerifyUnavailable) result).reason().contains("PAYMENT_GATEWAY_QUERY"));
        assertTrue(ledger.exists("op-81:VERIFY_STARTED:1"));
        assertTrue(ledger.exists("op-81:VERIFY_UNAVAILABLE:1"));
    }

    @Test void refundLogConfirmsChannelSuccess_yieldsRecovered() {
        var ledger = new InMemoryEventLedger();
        var port = new InMemoryRefundLogReadPort();
        port.add(new RefundLogRecord(1, "rf-82", "order-82", "CHANNEL_SUCCESS", "ALIPAY", true, null, null, "trace-82", NOW));
        var verifier = new RecoveryVerifier(List.of(new RefundLogFactSource(port)), ledger, () -> NOW);

        VerifyResult result = verifier.verify("op-82", "order-82", ActionType.REFUND_RETRY,
            new VerificationPlan("REFUND_LOG", "确认渠道成功", "rf-82", 0));

        assertInstanceOf(VerifyResult.Recovered.class, result);
        assertTrue(ledger.exists("op-82:VERIFY_RECOVERED:1"));
    }

    @Test void refundLogWithoutChannelSuccess_yieldsNotRecovered_notAFailedQuery() {
        var ledger = new InMemoryEventLedger();
        var port = new InMemoryRefundLogReadPort();
        port.add(new RefundLogRecord(1, "rf-83", "order-83", "CHANNEL_FAILED", "ALIPAY", false, "E001", "渠道超时", "trace-83", NOW));
        var verifier = new RecoveryVerifier(List.of(new RefundLogFactSource(port)), ledger, () -> NOW);

        VerifyResult result = verifier.verify("op-83", "order-83", ActionType.REFUND_RETRY,
            new VerificationPlan("REFUND_LOG", "确认渠道成功", "rf-83", 0));

        assertInstanceOf(VerifyResult.NotRecovered.class, result, "查询成功地告诉你问题还在，不是查询失败");
        assertTrue(ledger.exists("op-83:VERIFY_NOT_RECOVERED:1"));
    }

    /** ★ INV-VERIFY-002 核心正确性证明：数据源查询失败与"确认未恢复"绝不能被混为一谈。 */
    @Test void sourceQueryFails_yieldsVerifyUnavailable_neverMisreadAsNotRecovered() {
        var ledger = new InMemoryEventLedger();
        var port = new InMemoryRefundLogReadPort().disconnect();
        var verifier = new RecoveryVerifier(List.of(new RefundLogFactSource(port)), ledger, () -> NOW);

        VerifyResult result = verifier.verify("op-84", "order-84", ActionType.REFUND_RETRY,
            new VerificationPlan("REFUND_LOG", "确认渠道成功", "rf-84", 0));

        assertInstanceOf(VerifyResult.VerifyUnavailable.class, result);
        assertTrue(ledger.exists("op-84:VERIFY_UNAVAILABLE:1"));
    }

    @Test void archUnit_verificationPackage_hasNoDependencyOnLlmOrExecutionPackage() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/trade/mall/agent/verification");
        if (!java.nio.file.Files.exists(root)) return;
        try (var stream = java.nio.file.Files.walk(root)) {
            var files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            boolean anyImportsLlm = files.stream().anyMatch(p -> contains(p, "import com.trade.mall.agent.llm."));
            boolean anyImportsExecution = files.stream().anyMatch(p -> contains(p, "import com.trade.mall.agent.execution."));
            assertFalse(anyImportsLlm, "agent.verification 不得依赖 agent.llm");
            assertFalse(anyImportsExecution, "agent.verification 不得依赖 agent.execution（只需要 proposal.ActionType）");
        }
    }

    private static boolean contains(java.nio.file.Path p, String needle) {
        try {
            return java.nio.file.Files.readString(p).contains(needle);
        } catch (Exception e) {
            return false;
        }
    }
}

