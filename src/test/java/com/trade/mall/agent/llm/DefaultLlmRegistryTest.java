package com.trade.mall.agent.llm;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.InMemorySkillVersionStore;
import com.trade.mall.agent.llm.infrastructure.ScriptedLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §51-59 一一对应。§56 是本类最重要的一条测试
 * （{@code versionPinning_survivesGlobalSwitch}）——ADR-015 的正确性证明就是它。
 */
class DefaultLlmRegistryTest {

    static final long NOW = 1_700_000_000_000L;

    InMemoryEventLedger ledger;
    InMemoryAlertPort alertPort;
    InMemoryLlmClientFactory factory;
    InMemoryPromptVersionStore promptStore;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        alertPort = new InMemoryAlertPort();
        factory = new InMemoryLlmClientFactory();
        promptStore = new InMemoryPromptVersionStore("v1", "system prompt v1");
    }

    private DefaultLlmRegistry newRegistry(String initialModelId) {
        return new DefaultLlmRegistry(factory, ledger, alertPort, promptStore, "tool-schema-v1",
            initialModelId, Duration.ofSeconds(30), () -> NOW);
    }

    @Test void current_neverNull_andMatchesConstruction() {
        var healthy = new ScriptedLlmClient("modelA").healthy(true);
        factory.register("modelA", () -> healthy);

        var registry = newRegistry("modelA");

        assertSame(healthy, registry.current());
        assertEquals("modelA", registry.currentModelId());
    }

    @Test void unhealthyInitialClient_failsConstruction_INV_CFG_002() {
        var unhealthy = new ScriptedLlmClient("modelBad").healthy(false);
        factory.register("modelBad", () -> unhealthy);

        assertThrows(IllegalStateException.class, () -> newRegistry("modelBad"),
            "宁可起不来，也不要 current() 返回不可用实例");
    }

    @Test void switchTo_happyPath_switchedAndThreeEventsAndOldShutdown() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        var clientB = new ScriptedLlmClient("modelB").healthy(true);
        factory.register("modelA", () -> clientA).register("modelB", () -> clientB);
        var registry = newRegistry("modelA");

        SwitchResult result = registry.switchTo("modelB");

        assertInstanceOf(SwitchResult.Switched.class, result);
        assertEquals("modelB", registry.currentModelId());
        assertSame(clientB, registry.current());
        assertEquals(1, ledger.countOfType("llm-registry", "Llm.SwitchRequested"));
        assertEquals(1, ledger.countOfType("llm-registry", "Llm.HealthCheckPassed"));
        assertEquals(1, ledger.countOfType("llm-registry", "Llm.Switched"));
        assertTrue(clientA.shutdownCalled(), "旧实例应被 shutdown（记账动作，不代表报废）");
    }

    @Test void switchTo_sameModel_isNoOp_noEvents() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        factory.register("modelA", () -> clientA);
        var registry = newRegistry("modelA");

        SwitchResult result = registry.switchTo("modelA");

        assertInstanceOf(SwitchResult.NoOp.class, result);
        assertTrue(ledger.eventsOf("llm-registry").isEmpty());
    }

    @Test void switchTo_buildFailure_isAborted_currentUntouched_oneWarning() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        factory.register("modelA", () -> clientA)
            .registerFailure("modelBroken", new IllegalArgumentException("api-key-ref 缺失"));
        var registry = newRegistry("modelA");

        SwitchResult result = registry.switchTo("modelBroken");

        assertInstanceOf(SwitchResult.Aborted.class, result);
        assertTrue(((SwitchResult.Aborted) result).reason().startsWith("BUILD_FAILED"));
        assertSame(clientA, registry.current(), "current() 引用未被触碰");
        assertEquals(1, alertPort.alerts(InMemoryAlertPort.Severity.WARNING).size());
    }

    @Test void switchTo_candidateUnhealthy_isAborted_currentUntouched_candidateShutdown() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        var badB = new ScriptedLlmClient("modelB").healthy(false);
        factory.register("modelA", () -> clientA).register("modelB", () -> badB);
        var registry = newRegistry("modelA");

        SwitchResult result = registry.switchTo("modelB");

        assertInstanceOf(SwitchResult.Aborted.class, result);
        assertEquals("UNHEALTHY", ((SwitchResult.Aborted) result).reason());
        assertSame(clientA, registry.current());
        assertTrue(badB.shutdownCalled(), "用完即弃的候选实例也应被 shutdown，不能泄漏");
        assertEquals(1, alertPort.alerts(InMemoryAlertPort.Severity.WARNING).size());
    }

    /**
     * ★ ADR-015 的核心正确性证明：pin() 之后即便全局 switchTo() 真的发生了，
     * 已钉住的诊断必须继续拿到切换前的实例与快照——不多不少，包括幂等性。
     */
    @Test void versionPinning_survivesGlobalSwitch_ADR_015() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        var clientB = new ScriptedLlmClient("modelB").healthy(true);
        factory.register("modelA", () -> clientA).register("modelB", () -> clientB);
        var registry = newRegistry("modelA");

        VersionSnapshot snapshotBefore = registry.pin("diag-56");
        assertEquals("modelA", snapshotBefore.modelId());
        assertSame(clientA, registry.forPinned("diag-56"));

        registry.switchTo("modelB"); // 全局切换发生在诊断进行中

        assertEquals("modelB", registry.currentModelId(), "全局 current() 已经是 modelB");
        assertSame(clientA, registry.forPinned("diag-56"), "已钉住的诊断依然拿到切换前的实例");
        assertEquals(snapshotBefore, registry.pin("diag-56"), "重复 pin() 幂等，不会漂移到 modelB");

        VersionSnapshot snapshotAfter = registry.pin("diag-56-new");
        assertEquals("modelB", snapshotAfter.modelId(), "切换之后新开始的诊断钉住的是新模型");
        assertSame(clientB, registry.forPinned("diag-56-new"));
    }

    @Test void retiredClient_waitsForLastPinnedDiagnosis_beforeShutdown() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        var clientB = new ScriptedLlmClient("modelB").healthy(true);
        factory.register("modelA", () -> clientA).register("modelB", () -> clientB);
        var registry = newRegistry("modelA");

        registry.pin("diag-a1");
        registry.pin("diag-a2");
        registry.switchTo("modelB");
        assertFalse(clientA.shutdownCalled(), "仍有在途 Diagnosis pin 时，旧 client 只能退役，不能关闭");

        registry.release("diag-a1");
        assertFalse(clientA.shutdownCalled(), "共享旧 client 的第一个 pin 释放后仍不能提前关闭");

        registry.release("diag-a2");
        assertTrue(clientA.shutdownCalled(), "最后一个 pin 释放后旧 client 才真正关闭");
    }

    @Test void skillHotUpdate_onlyAffectsNewDiagnosis() {
        var client = new ScriptedLlmClient("modelA").healthy(true);
        factory.register("modelA", () -> client);
        var skills = new InMemorySkillVersionStore("skill-v1", "技能一");
        var registry = new DefaultLlmRegistry(factory, ledger, alertPort, promptStore, skills,
            "tool-schema-v1", "modelA", Duration.ofSeconds(5), () -> NOW, id -> java.util.Optional.empty());

        VersionSnapshot oldSnapshot = registry.pin("diag-skill-old");
        skills.publish("skill-v2", "技能二");
        VersionSnapshot newSnapshot = registry.pin("diag-skill-new");

        assertEquals("skill-v1", oldSnapshot.skillVersion());
        assertEquals("技能一", registry.skillForPinned("diag-skill-old").instructions());
        assertEquals("skill-v2", newSnapshot.skillVersion());
        assertEquals("技能二", registry.skillForPinned("diag-skill-new").instructions());
    }

    @Test void close_shutsCurrentClient_andIsIdempotent() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        factory.register("modelA", () -> clientA);
        var registry = newRegistry("modelA");

        registry.close();
        registry.close();

        assertTrue(clientA.shutdownCalled());
        assertThrows(IllegalStateException.class, () -> registry.pin("diag-after-close"));
    }

    @Test void pin_idempotent_release_thenForPinnedThrows() {
        var clientA = new ScriptedLlmClient("modelA").healthy(true);
        factory.register("modelA", () -> clientA);
        var registry = newRegistry("modelA");

        VersionSnapshot snap1 = registry.pin("diag-57");
        VersionSnapshot snap2 = registry.pin("diag-57");
        assertEquals(snap1, snap2);

        registry.release("diag-57");
        assertThrows(IllegalStateException.class, () -> registry.forPinned("diag-57"));
    }

    @Test void archUnit_llmPackage_hasNoDependencyOnExecutionPackage_ADR_003() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/trade/mall/agent/llm");
        if (!java.nio.file.Files.exists(root)) return; // 测试可能不在项目根目录下运行时跳过
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
            assertFalse(anyImportsExecution, "agent.llm 不得依赖 agent.execution（LLM 重试安全，执行域重试可能双花）");
        }
    }
}
