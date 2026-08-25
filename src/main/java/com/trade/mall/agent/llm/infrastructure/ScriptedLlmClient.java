package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.LlmClient;
import com.trade.mall.agent.llm.LlmRequest;
import com.trade.mall.agent.llm.LlmResponse;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可编排的测试替身（测试/演示用；生产实现是真实的 HTTP 客户端），与 D2 的
 * `ScriptedActionPort` 同一设计取向：`scriptResponse`/`scriptThrow` 摆好剧本，
 * `callCount` 供 `verify(times(1))` 的等价断言。
 *
 * <p>{@code shutdown()} 仍只做记账，便于老 SelfCheck（自检）观察调用；V8 已经不再依赖
 * “shutdown 后还能调用”这个测试替身特性，真正的 lifecycle regression（生命周期反例）
 * 使用一个 shutdown 后立即拒绝 complete 的严格客户端，确保 retired client（退役客户端）
 * 只有在最后一个 diagnosis pin（诊断钉住引用）释放之后才会被关闭。</p>
 */
public final class ScriptedLlmClient implements LlmClient {

    private final String modelId;
    private volatile boolean healthy = true;
    private final Deque<String> scriptedResponses = new ArrayDeque<>();
    private volatile RuntimeException scriptedThrow;
    private final AtomicInteger callCount = new AtomicInteger();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean shutdownCalled = false;
    private volatile Duration lastGrace;

    public ScriptedLlmClient(String modelId) { this.modelId = modelId; }

    public ScriptedLlmClient healthy(boolean healthy) { this.healthy = healthy; return this; }
    public ScriptedLlmClient scriptResponse(String content) { scriptedResponses.addLast(content); return this; }
    public ScriptedLlmClient scriptThrow(RuntimeException ex) { this.scriptedThrow = ex; return this; }

    @Override
    public String modelId() { return modelId; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        callCount.incrementAndGet();
        if (scriptedThrow != null) {
            RuntimeException ex = scriptedThrow;
            throw ex;
        }
        String content = scriptedResponses.isEmpty() ? "" : scriptedResponses.pollFirst();
        return new LlmResponse(content, modelId);
    }

    @Override
    public boolean healthy() { return healthy; }

    @Override
    public void shutdown(Duration grace) {
        this.shutdownCalled = true;
        this.lastGrace = grace;
    }

    public int callCount() { return callCount.get(); }
    public List<LlmRequest> requests() { return List.copyOf(requests); }
    public LlmRequest lastRequest() { return requests.isEmpty() ? null : requests.get(requests.size() - 1); }
    public boolean shutdownCalled() { return shutdownCalled; }
    public Duration lastGrace() { return lastGrace; }
}

