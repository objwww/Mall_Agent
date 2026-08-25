package com.trade.mall.agent.understanding;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.DefaultLlmRegistry;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §60-64 一一对应。§64 是本类最重要的一条测试
 * （{@code pinnedVersion_notAffectedByConcurrentGlobalSwitch}）——落地了
 * `prior_art_and_design_influences.md` §8 那篇 Restate 博客讲的"沉默的版本
 * 错配比崩溃更难发现"的教训。
 */
class TicketUnderstandingServiceTest {

    static final long NOW = 1_700_000_000_000L;

    private DefaultLlmRegistry newRegistry(InMemoryLlmClientFactory factory, InMemoryEventLedger ledger,
                                            String initialModelId) {
        return new DefaultLlmRegistry(factory, ledger, new InMemoryAlertPort(),
            new InMemoryPromptVersionStore("v1", "system prompt v1"), "tool-schema-v1",
            initialModelId, Duration.ofSeconds(30), () -> NOW);
    }

    @Test void happyPath_validJson_yieldsUnderstood_withMatchingVersionSnapshot() {
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"REFUND\",\"anchorValue\":\"refund-60\","
                + "\"symptoms\":[\"退款一直显示处理中\",\"已经三天没到账\"],\"confidence\":0.86}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new TicketUnderstandingService(registry, ledger, () -> NOW);

        UnderstandingResult result = service.understand("ticket-60", "diag-60",
            "我的退款一直显示处理中，已经三天了还没到账");

        assertInstanceOf(UnderstandingResult.Understood.class, result);
        var understood = (UnderstandingResult.Understood) result;
        assertEquals(AnchorType.REFUND, understood.anchor().type());
        assertEquals("refund-60", understood.anchor().value());
        assertEquals(2, understood.symptoms().size());
        assertEquals(registry.pin("diag-60"), understood.versionSnapshot());
        assertEquals(1, client.callCount(), "一次就成功，不应有多余重试");
        assertTrue(ledger.exists("diag-60:TICKET_ANCHOR:1"));
    }

    @Test void schemaFailure_retriesWithRepairHint_thenSucceeds_DEP_LLM_001() {
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("这不是JSON")
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-61\","
                + "\"symptoms\":[\"下单后一直未发货\"],\"confidence\":0.7}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new TicketUnderstandingService(registry, ledger, () -> NOW);

        UnderstandingResult result = service.understand("ticket-61", "diag-61", "我的订单下了三天还没发货");

        assertInstanceOf(UnderstandingResult.Understood.class, result);
        assertEquals(2, client.callCount(), "第一次 schema 校验失败后应重试一次");
    }

    @Test void exceedsMaxAttempts_escalates_notUncaughtException() {
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("垃圾输出1").scriptResponse("垃圾输出2")
            .scriptResponse("垃圾输出3").scriptResponse("垃圾输出4");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new TicketUnderstandingService(registry, ledger, () -> NOW);

        UnderstandingResult result = service.understand("ticket-62", "diag-62", "不知道说什么");

        assertInstanceOf(UnderstandingResult.Escalated.class, result);
        var escalated = (UnderstandingResult.Escalated) result;
        assertEquals(3, escalated.attempts());
        assertEquals(3, client.callCount(), "只尝试 3 次就转人工，第 4 条脚本不应被消费");
        assertTrue(ledger.exists("diag-62:TICKET_ESCALATED:1"));
    }

    @Test void anchorMissing_isLegalOutput_notAnException_NG_002() {
        var factory = new InMemoryLlmClientFactory();
        var client = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorMissing\":true,\"reason\":\"工单里没有提到任何订单号或退款单号\"}");
        factory.register("modelA", () -> client);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new TicketUnderstandingService(registry, ledger, () -> NOW);

        UnderstandingResult result = service.understand("ticket-63", "diag-63", "东西质量不太好，有点失望");

        assertInstanceOf(UnderstandingResult.AnchorMissing.class, result);
        assertTrue(((UnderstandingResult.AnchorMissing) result).reason().contains("订单号"));
        assertEquals(1, client.callCount(), "AnchorMissing 不是失败，不应触发重试");
        assertTrue(ledger.exists("diag-63:TICKET_ANCHOR_MISSING:1"));
    }

    /**
     * ★ ADR-015 落到调用方代码的样子：诊断启动时 pin() 一次，之后哪怕运维在诊断
     * 进行中把全局模型切走，这次 understand() 也必须继续使用切换前钉住的那个客户端。
     */
    @Test void pinnedVersion_notAffectedByConcurrentGlobalSwitch() {
        var factory = new InMemoryLlmClientFactory();
        var clientA = new ScriptedLlmClient("modelA").healthy(true)
            .scriptResponse("{\"anchorType\":\"ORDER\",\"anchorValue\":\"order-64\","
                + "\"symptoms\":[\"支付成功但订单状态未更新\"],\"confidence\":0.75}");
        var clientB = new ScriptedLlmClient("modelB").healthy(true);
        factory.register("modelA", () -> clientA).register("modelB", () -> clientB);
        var ledger = new InMemoryEventLedger();
        var registry = newRegistry(factory, ledger, "modelA");
        var service = new TicketUnderstandingService(registry, ledger, () -> NOW);

        VersionSnapshot pinnedSnapshot = registry.pin("diag-64"); // 诊断启动时钉住
        registry.switchTo("modelB"); // 诊断进行中，运维把全局模型切到了 B

        UnderstandingResult result = service.understand("ticket-64", "diag-64", "付款成功了，但订单一直显示未支付");

        assertInstanceOf(UnderstandingResult.Understood.class, result);
        var understood = (UnderstandingResult.Understood) result;
        assertEquals("modelA", understood.versionSnapshot().modelId(),
            "结论使用的版本必须是诊断开始时钉住的 modelA，不是切换后的 modelB");
        assertEquals(pinnedSnapshot, understood.versionSnapshot());
        assertEquals(1, clientA.callCount());
        assertEquals(0, clientB.callCount(), "modelB 从未被这次诊断调用过");
        assertEquals("modelB", registry.currentModelId(), "全局确实已经在服役 modelB，证明不是切换没生效");
    }
}
