package com.trade.mall.agent.llm;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.evidence.ConfidenceLevel;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.evidence.SourceLocator;
import com.trade.mall.agent.evidence.port.RefundLogBundle;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.ScriptedLlmClient;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.reasoning.ReasoningService;
import com.trade.mall.agent.understanding.TicketUnderstandingService;
import com.trade.mall.agent.understanding.UnderstandingResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** V6 最小逻辑修复的无框架回归检查。 */
public final class LlmSafetyRegressionCheck {
    private static final long NOW = 1_700_000_000_000L;
    private static int passed;

    public static void main(String[] args) {
        promptContentIsActuallyPinnedAndUsed();
        understandingRejectsOutOfRangeConfidence();
        reasoningRejectsOutOfRangeConfidence();
        refundFindingRequiresPresentRefundLog();
        refundFindingMustReferenceRequiredEvidence();
        promptVersionAndContentSwitchAtomically();
        System.out.println("V6 LLM/evidence safety regression: " + passed + "/6 passed");
    }

    private static void promptContentIsActuallyPinnedAndUsed() {
        var ledger = new InMemoryEventLedger();
        var promptStore = new InMemoryPromptVersionStore("prompt-v1", "VERSIONED-PROMPT-V1");
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-p1\",\"symptoms\":[\"x\"],\"confidence\":0.8}")
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-p2\",\"symptoms\":[\"x\"],\"confidence\":0.8}");
        var factory = new InMemoryLlmClientFactory().register("modelA", () -> client);
        var registry = registry(factory, ledger, promptStore);
        registry.pin("diag-old");
        promptStore.publish("prompt-v2", "VERSIONED-PROMPT-V2");

        var service = new TicketUnderstandingService(registry, ledger, () -> NOW);
        service.understand("ticket-old", "diag-old", "订单 order-p1 有问题");
        service.understand("ticket-new", "diag-new", "订单 order-p2 有问题");

        require(client.requests().get(0).systemPrompt().contains("VERSIONED-PROMPT-V1")
            && !client.requests().get(0).systemPrompt().contains("VERSIONED-PROMPT-V2"),
            "旧诊断必须继续发送首次 pin 时的提示词正文");
        require(client.requests().get(1).systemPrompt().contains("VERSIONED-PROMPT-V2"),
            "新诊断必须使用发布后的新提示词正文");
        require(registry.pin("diag-old").promptVersion().equals("prompt-v1")
            && registry.pin("diag-new").promptVersion().equals("prompt-v2"),
            "提示词版本号必须与真正发送的正文同步");
        passed++;
    }

    private static void understandingRejectsOutOfRangeConfidence() {
        var ledger = new InMemoryEventLedger();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse(understandingJson(1.2))
            .scriptResponse(understandingJson(-0.1))
            .scriptResponse(understandingJson(3.14));
        var factory = new InMemoryLlmClientFactory().register("modelA", () -> client);
        var service = new TicketUnderstandingService(
            registry(factory, ledger, new InMemoryPromptVersionStore("v1", "base prompt")), ledger, () -> NOW);
        var result = service.understand("ticket-confidence", "diag-confidence", "order-c");
        require(result instanceof UnderstandingResult.Escalated, "理解阶段 confidence 越界必须拒绝并最终转人工");
        require(client.callCount() == 3, "confidence 越界应消耗结构修复重试预算，不能被接受");
        passed++;
    }

    private static void reasoningRejectsOutOfRangeConfidence() {
        var ledger = new InMemoryEventLedger();
        var bundle = refundBundle(true);
        String refund = EvidenceEventIds.collected(bundle.anchor(), "REFUND");
        String log = EvidenceEventIds.collected(bundle.anchor(), "REFUND_LOG");
        String json = "{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"" + refund + "\",\"" + log + "\"],\"confidence\":3.14}";
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse(json).scriptResponse(json).scriptResponse(json);
        var factory = new InMemoryLlmClientFactory().register("modelA", () -> client);
        var service = new ReasoningService(
            registry(factory, ledger, new InMemoryPromptVersionStore("v1", "base prompt")), ledger, () -> NOW);
        var result = service.reason("diag-reason-confidence", bundle);
        require(result instanceof FindingResult.NoConclusion, "推理阶段 confidence 越界绝不能产出可执行 Finding");
        passed++;
    }

    private static void refundFindingRequiresPresentRefundLog() {
        var ledger = new InMemoryEventLedger();
        var bundle = refundBundle(false);
        String refund = EvidenceEventIds.collected(bundle.anchor(), "REFUND");
        String unavailableLog = EvidenceEventIds.unavailable(bundle.anchor(), "REFUND_LOG");
        String json = "{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"" + refund + "\",\"" + unavailableLog + "\"],\"confidence\":0.9}";
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse(json).scriptResponse(json).scriptResponse(json);
        var factory = new InMemoryLlmClientFactory().register("modelA", () -> client);
        var service = new ReasoningService(
            registry(factory, ledger, new InMemoryPromptVersionStore("v1", "base prompt")), ledger, () -> NOW);
        var result = service.reason("diag-missing-log", bundle);
        require(result instanceof FindingResult.NoConclusion,
            "REFUND_LOG=UNAVAILABLE 时即使 evidenceId 合法也不能产生退款处置结论");
        passed++;
    }

    private static void refundFindingMustReferenceRequiredEvidence() {
        var ledger = new InMemoryEventLedger();
        var bundle = refundBundle(true);
        String refund = EvidenceEventIds.collected(bundle.anchor(), "REFUND");
        String json = "{\"findingType\":\"REFUND_STUCK_NEEDS_RETRY\",\"evidenceIds\":[\"" + refund + "\"],\"confidence\":0.9}";
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse(json).scriptResponse(json).scriptResponse(json);
        var factory = new InMemoryLlmClientFactory().register("modelA", () -> client);
        var service = new ReasoningService(
            registry(factory, ledger, new InMemoryPromptVersionStore("v1", "base prompt")), ledger, () -> NOW);
        var result = service.reason("diag-not-referenced", bundle);
        require(result instanceof FindingResult.NoConclusion,
            "即使 REFUND_LOG 已采集，Finding 不引用它也不能声称退款卡住");
        passed++;
    }

    private static void promptVersionAndContentSwitchAtomically() {
        var store = new InMemoryPromptVersionStore("v1", "content-v1");
        PromptSnapshot before = store.current();
        store.publish("v2", "content-v2");
        PromptSnapshot after = store.current();
        require(before.version().equals("v1") && before.prompt().equals("content-v1"), "旧快照必须保持不可变");
        require(after.version().equals("v2") && after.prompt().equals("content-v2"), "新版本号与正文必须一起切换");
        passed++;
    }

    private static DefaultLlmRegistry registry(InMemoryLlmClientFactory factory, InMemoryEventLedger ledger,
                                                InMemoryPromptVersionStore promptStore) {
        return new DefaultLlmRegistry(factory, ledger, new InMemoryAlertPort(), promptStore,
            "tool-schema-v1", "modelA", Duration.ofSeconds(5), () -> NOW);
    }

    private static String understandingJson(double confidence) {
        return "{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-c\",\"symptoms\":[\"x\"],\"confidence\":" + confidence + "}";
    }

    private static EvidenceBundle refundBundle(boolean logPresent) {
        String orderSn = logPresent ? "order-evidence-present" : "order-evidence-unavailable";
        String refundSn = logPresent ? "rf-present" : "rf-unavailable";
        Evidence refund = Evidence.present("REFUND", SourceLocator.of("oms_order_refund", refundSn), ConfidenceLevel.VERIFIED,
            new RefundRecord(1, refundSn, orderSn, 1, new BigDecimal("100.00"), null, null));
        Evidence log = logPresent
            ? Evidence.present("REFUND_LOG", SourceLocator.of("oms_order_refund_log", refundSn), ConfidenceLevel.VERIFIED,
                new RefundLogBundle(List.of()))
            : Evidence.unavailable("REFUND_LOG", SourceLocator.tableOnly("oms_order_refund_log"), ConfidenceLevel.VERIFIED,
                "database unavailable");
        return EvidenceBundle.of(orderSn, List.of(refund, log));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

