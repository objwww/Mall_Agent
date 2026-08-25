package com.trade.mall.agent.reasoning;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.evidence.ConfidenceLevel;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.evidence.SourceLocator;
import com.trade.mall.agent.evidence.port.RefundLogBundle;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.DefaultLlmRegistry;
import com.trade.mall.agent.llm.LlmUnavailableException;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §65-70 一一对应。§67 是本类最重要的一条测试
 * （{@code fabricatedEvidence_neverAcceptedAsResult}）——INV-EVID-002 的正确性证明就是它：
 * 无论 LLM 怎么坚持编造证据，{@link ReasoningService#reason} 都绝不会把带假引用的
 * Finding 当作返回值交给调用方。
 */
class ReasoningServiceTest {

    static final long NOW = 1_700_000_000_000L;

    private DefaultLlmRegistry newRegistry(InMemoryLlmClientFactory factory, InMemoryEventLedger ledger, String initialModelId) {
        return new DefaultLlmRegistry(factory, ledger, new InMemoryAlertPort(),
            new InMemoryPromptVersionStore("v1", "system prompt v1"), "tool-schema-v1",
            initialModelId, Duration.ofSeconds(30), () -> NOW);
    }

    private Evidence refundPresent(String orderSn, String refundSn) {
        return Evidence.present("REFUND", SourceLocator.of("oms_order_refund", refundSn), ConfidenceLevel.VERIFIED,
            new RefundRecord(1, refundSn, orderSn, 1, new BigDecimal("100.00"), null, null));
    }

    private Evidence refundLogPresent(String orderSn, String refundSn) {
        var entry = new RefundLogRecord(1, refundSn, orderSn, "CHANNEL_FAILED", "ALIPAY", false,
            "CHANNEL_ERR", "渠道返回失败", "trace-1", NOW);
        return Evidence.present("REFUND_LOG", SourceLocator.of("oms_order_refund_log", refundSn),
            ConfidenceLevel.VERIFIED, new RefundLogBundle(List.of(entry)));
    }

    @Test void happyPath_legitimateReferences_yieldsConcluded() {
        var bundle = EvidenceBundle.of("order-65", List.of(refundPresent("order-65", "rf-65"), refundLogPresent("order-65", "rf-65")));
        String idRefund = EvidenceEventIds.collected("order-65", "REFUND");
        String idLog = EvidenceEventIds.collected("order-65", "REFUND_LOG");
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + idRefund + "\",\"" + idLog + "\"],\"confidence\":0.82}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new ReasoningService(registry, ledger, () -> NOW);

        FindingResult result = service.reason("diag-65", bundle);

        assertInstanceOf(FindingResult.Concluded.class, result);
        var concluded = (FindingResult.Concluded) result;
        assertEquals(FindingType.REFUND_STUCK_NEEDS_RETRY, concluded.findingType());
        assertTrue(concluded.evidenceIds().containsAll(List.of(idRefund, idLog)));
        assertEquals(1, client.callCount());
        assertTrue(ledger.exists("diag-65:FINDING:1"));
    }

    @Test void fabricatedReference_mixedWithReal_wholeFindingRejected_thenRetrySucceeds() {
        var bundle = EvidenceBundle.of("order-66", List.of(
            refundPresent("order-66", "rf-66"), refundLogPresent("order-66", "rf-66")));
        String idRefund = EvidenceEventIds.collected("order-66", "REFUND");
        String idLog = EvidenceEventIds.collected("order-66", "REFUND_LOG");
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + idRefund + "\",\"order-66:FAKE:COLLECTED:1\"],\"confidence\":0.7}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\""
                + idRefund + "\",\"" + idLog + "\"],\"confidence\":0.7}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new ReasoningService(registry, ledger, () -> NOW);

        FindingResult result = service.reason("diag-66", bundle);

        assertInstanceOf(FindingResult.Concluded.class, result);
        assertEquals(2, client.callCount(), "第一条候选 Finding 混入假 id，应被整体拒绝并重试");
    }

    /** ★ INV-EVID-002 核心正确性证明：不管 LLM 怎么坚持编造，方法返回值永远不会是带假引用的 Finding。 */
    @Test void fabricatedEvidence_neverAcceptedAsResult() {
        var bundle = EvidenceBundle.of("order-67", List.of(refundPresent("order-67", "rf-67")));
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"order-67:GHOST_1:COLLECTED:1\"],\"confidence\":0.6}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"order-67:GHOST_2:COLLECTED:1\"],\"confidence\":0.6}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"order-67:GHOST_3:COLLECTED:1\"],\"confidence\":0.6}")
            .scriptResponse("{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"order-67:GHOST_4:COLLECTED:1\"],\"confidence\":0.6}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new ReasoningService(registry, ledger, () -> NOW);

        FindingResult result = service.reason("diag-67", bundle);

        assertInstanceOf(FindingResult.NoConclusion.class, result, "绝不能把编造证据的 Finding 当结果返回");
        assertEquals(3, client.callCount(), "只尝试 3 次就放弃，第 4 条脚本不应被消费");
        assertTrue(ledger.exists("diag-67:NO_CONCLUSION:1"));
    }

    @Test void llmReportsNoConclusion_isLegalOutput_notRetried() {
        var bundle = EvidenceBundle.of("order-68", List.of(refundPresent("order-68", "rf-68")));
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"noConclusion\":true,\"reason\":\"现有证据不足以支持任何判定\"}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new ReasoningService(registry, ledger, () -> NOW);

        FindingResult result = service.reason("diag-68", bundle);

        assertInstanceOf(FindingResult.NoConclusion.class, result);
        assertTrue(((FindingResult.NoConclusion) result).reason().contains("证据不足"));
        assertEquals(1, client.callCount(), "合法的'没结论'不是失败，不应重试");
    }

    @Test void llmUnavailable_yieldsNoConclusion_notUncaughtException() {
        var bundle = EvidenceBundle.of("order-69", List.of(refundPresent("order-69", "rf-69")));
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true).scriptThrow(new LlmUnavailableException("渠道超时"));
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new ReasoningService(registry, ledger, () -> NOW);

        FindingResult result = service.reason("diag-69", bundle);

        assertInstanceOf(FindingResult.NoConclusion.class, result);
        assertEquals(1, client.callCount(), "不可用直接判定，不重试等恢复");
    }

    @Test void archUnit_reasoningPackage_hasNoDependencyOnExecutionPackage() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/trade/mall/agent/reasoning");
        if (!java.nio.file.Files.exists(root)) return;
        try (var stream = java.nio.file.Files.walk(root)) {
            boolean anyImportsExecution = stream
                .filter(p -> p.toString().endsWith(".java"))
                .anyMatch(p -> {
                    try {
                        return java.nio.file.Files.readString(p).contains("import com.trade.mall.agent.execution.");
                    } catch (Exception e) {
                        return false;
                    }
                });
            assertFalse(anyImportsExecution, "agent.reasoning 不得依赖 agent.execution");
        }
    }
}

