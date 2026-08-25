package com.trade.mall.agent.proposal;

import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.reasoning.FindingType;
import com.trade.mall.agent.understanding.Anchor;
import com.trade.mall.agent.understanding.AnchorType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §71-77 一一对应。§73/§74 验证 {@link Proposal} 构造器自身的两条守卫
 * （INV-VERIFY-001 同源验证被拒 + paramsHash 一致性）——"规则本身要有自测"，与 D5
 * `INV-EVAL-001` 反例夹具、D6 `pin()`/`forPinned()` 幂等自测是同一条纪律。
 */
class RemediationProposerServiceTest {

    static final long NOW = 1_700_000_000_000L;
    static final VersionSnapshot SNAPSHOT = new VersionSnapshot("modelA", "v1", "tool-schema-v1");

    @Test void refundStuck_mapsToRefundRetry_withIndependentVerificationSource() {
        var anchor = new Anchor(AnchorType.ORDER, "order-71");
        var finding = new FindingResult.Concluded("diag-71:FINDING:1", FindingType.REFUND_STUCK_NEEDS_RETRY,
            List.of(EvidenceEventIds.collected("order-71", "REFUND")), 0.9, SNAPSHOT);
        var ledger = new InMemoryEventLedger();
        var service = new RemediationProposerService(ledger, () -> NOW);

        Proposal proposal = service.propose("diag-71", anchor, finding);

        assertEquals(ActionType.REFUND_RETRY, proposal.actionType());
        assertEquals("order-71", proposal.params().get("orderSn"));
        assertEquals(ParamsHashing.sha256(proposal.params()), proposal.paramsHash());
        assertEquals("diag-71:FINDING:1", proposal.basedOnFindingId());
        assertEquals("REFUND_LOG", proposal.verificationPlan().independentSourceType());
        assertNotEquals(proposal.verificationPlan().independentSourceType(), proposal.actionType().sourceType());
        assertTrue(ledger.exists("diag-71:PROPOSAL:1"));
    }

    @Test void orderStatusNotSynced_mapsToOrderStatusResync() {
        var anchor = new Anchor(AnchorType.ORDER, "order-72");
        var finding = new FindingResult.Concluded("diag-72:FINDING:1", FindingType.ORDER_STATUS_NOT_SYNCED,
            List.of(EvidenceEventIds.collected("order-72", "ORDER")), 0.75, SNAPSHOT);
        var ledger = new InMemoryEventLedger();
        var service = new RemediationProposerService(ledger, () -> NOW);

        Proposal proposal = service.propose("diag-72", anchor, finding);

        assertEquals(ActionType.ORDER_STATUS_RESYNC, proposal.actionType());
        assertEquals("PAYMENT_GATEWAY_QUERY", proposal.verificationPlan().independentSourceType());
        assertNotEquals(proposal.verificationPlan().independentSourceType(), proposal.actionType().sourceType());
    }

    @Test void proposalConstructor_rejectsSameSourceVerification_INV_VERIFY_001() {
        assertThrows(IllegalArgumentException.class, () -> new Proposal(
            "p-73", ActionType.REFUND_RETRY, Map.of("orderSn", "x"),
            ParamsHashing.sha256(Map.of("orderSn", "x")), "f-73",
            new VerificationPlan("REFUND_CHANNEL_API", "用同一个渠道 API 的返回值验证自己")));
    }

    @Test void proposalConstructor_rejectsMismatchedParamsHash() {
        assertThrows(IllegalArgumentException.class, () -> new Proposal(
            "p-74", ActionType.REFUND_RETRY, Map.of("orderSn", "x"), "0000deadbeef0000", "f-74",
            new VerificationPlan("REFUND_LOG", "对比重试前后的渠道日志")));
    }

    @Test void findingConstructor_rejectsEmptyEvidenceIds_INV_EVID_002() {
        assertThrows(IllegalArgumentException.class, () ->
            new FindingResult.Concluded("f-77", FindingType.REFUND_STUCK_NEEDS_RETRY, List.of(), 0.5, SNAPSHOT));
    }

    @Test void paramsHashing_orderIndependent_butValueSensitive() {
        var a = new java.util.LinkedHashMap<String, String>();
        a.put("b", "2"); a.put("a", "1");
        var aReordered = new java.util.LinkedHashMap<String, String>();
        aReordered.put("a", "1"); aReordered.put("b", "2");
        var changed = Map.of("a", "1", "b", "3");

        assertEquals(ParamsHashing.sha256(a), ParamsHashing.sha256(aReordered));
        assertNotEquals(ParamsHashing.sha256(a), ParamsHashing.sha256(changed));
    }

    @Test void archUnit_proposalPackage_hasNoDependencyOnLlmOrExecution() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/trade/mall/agent/proposal");
        if (!java.nio.file.Files.exists(root)) return;
        try (var stream = java.nio.file.Files.walk(root)) {
            List<String> contents = new java.util.ArrayList<>();
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try { contents.add(java.nio.file.Files.readString(p)); } catch (Exception ignored) { }
            });
            assertTrue(contents.stream().noneMatch(s -> s.contains("import com.trade.mall.agent.llm.")),
                "proposal 不依赖 llm——RemediationProposer 是确定性策略表，不调 LLM");
            assertTrue(contents.stream().noneMatch(s -> s.contains("import com.trade.mall.agent.execution.")),
                "proposal 不依赖 execution——提议阶段物理上拿不到能执行的协作者");
        }
    }
}

