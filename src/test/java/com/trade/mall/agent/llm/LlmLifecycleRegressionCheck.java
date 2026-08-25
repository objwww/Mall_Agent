package com.trade.mall.agent.llm;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.llm.infrastructure.InMemoryLlmClientFactory;
import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** V8：LLM client lifecycle（大语言模型客户端生命周期）最小反例检查。 */
public final class LlmLifecycleRegressionCheck {
    private static int passed;

    public static void main(String[] args) {
        var ledger = new InMemoryEventLedger();
        var alerts = new InMemoryAlertPort();
        var factory = new InMemoryLlmClientFactory();
        var a = new StrictClient("modelA");
        var b = new StrictClient("modelB");
        var c = new StrictClient("modelC");
        factory.register("modelA", () -> a).register("modelB", () -> b).register("modelC", () -> c);
        var registry = new DefaultLlmRegistry(factory, ledger, alerts,
            new InMemoryPromptVersionStore("prompt-v1", "system prompt"), "tool-v1",
            "modelA", Duration.ofSeconds(5), System::currentTimeMillis);

        registry.pin("diag-A1");
        registry.pin("diag-A2");
        registry.switchTo("modelB");
        require(a.shutdownCount() == 0,
            "旧模型仍有 diagnosis pin 时，switch 只能 retire（退役），不能 shutdown（关闭）");
        registry.forPinned("diag-A1").complete(new LlmRequest("s", "u", 16));
        require(a.completeCount() == 1,
            "全局切换后，旧 Diagnosis 必须还能真正调用旧 client，而不是拿到已关闭对象");

        registry.release("diag-A1");
        require(a.shutdownCount() == 0,
            "多个 Diagnosis 共用旧 client 时，释放第一个 pin 不能提前关闭共享 client");
        registry.release("diag-A2");
        require(a.shutdownCount() == 1,
            "最后一个旧 pin 释放后，retired client 必须恰好关闭一次");
        registry.release("diag-A2");
        require(a.shutdownCount() == 1,
            "release 必须幂等，重复释放不能重复 shutdown 或引用计数下溢");

        registry.switchTo("modelC");
        require(b.shutdownCount() == 1,
            "没有任何 pin 的旧 current client 在切换后应立即关闭，不能泄漏");

        registry.close();
        registry.close();
        require(c.shutdownCount() == 1,
            "Runtime close（运行时关闭）必须关闭当前 client，且 close 幂等只关闭一次");
        requireThrows(() -> registry.pin("after-close"),
            "registry close 后不得再接受新的 diagnosis pin");

        shutdownFailureDoesNotRewriteSuccessfulSwitch();

        System.out.println("V8 LLM lifecycle regression: " + passed + "/10 passed");
    }


    private static void shutdownFailureDoesNotRewriteSuccessfulSwitch() {
        var ledger = new InMemoryEventLedger();
        var alerts = new InMemoryAlertPort();
        var factory = new InMemoryLlmClientFactory();
        LlmClient badClose = new LlmClient() {
            @Override public String modelId() { return "bad-close"; }
            @Override public LlmResponse complete(LlmRequest request) { return new LlmResponse("{}", modelId()); }
            @Override public boolean healthy() { return true; }
            @Override public void shutdown(Duration grace) { throw new IllegalStateException("pool close failed"); }
        };
        var next = new StrictClient("next");
        factory.register("bad-close", () -> badClose).register("next", () -> next);
        var registry = new DefaultLlmRegistry(factory, ledger, alerts,
            new InMemoryPromptVersionStore("prompt-v1", "system prompt"), "tool-v1",
            "bad-close", Duration.ofSeconds(5), System::currentTimeMillis);

        SwitchResult result = registry.switchTo("next");
        require(result instanceof SwitchResult.Switched && registry.current() == next,
            "旧 client 关闭失败不能把已经完成的 current 原子切换改写成失败");
        require(alerts.alerts(InMemoryAlertPort.Severity.WARNING).stream()
                .anyMatch(a -> "llm.shutdown.failed".equals(a.code())),
            "旧 client 关闭失败必须独立告警，不能静默吞掉");
        registry.close();
    }

    private static final class StrictClient implements LlmClient {
        private final String modelId;
        private final AtomicInteger complete = new AtomicInteger();
        private final AtomicInteger shutdown = new AtomicInteger();
        private volatile boolean closed;

        private StrictClient(String modelId) { this.modelId = modelId; }
        @Override public String modelId() { return modelId; }
        @Override public LlmResponse complete(LlmRequest request) {
            if (closed) throw new IllegalStateException("client already shutdown: " + modelId);
            complete.incrementAndGet();
            return new LlmResponse("{}", modelId);
        }
        @Override public boolean healthy() { return !closed; }
        @Override public void shutdown(Duration grace) { closed = true; shutdown.incrementAndGet(); }
        int completeCount() { return complete.get(); }
        int shutdownCount() { return shutdown.get(); }
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            passed++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}

