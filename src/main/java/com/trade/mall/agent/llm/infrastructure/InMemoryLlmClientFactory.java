package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.LlmClient;
import com.trade.mall.agent.llm.LlmClientFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 内存版工厂（测试/演示用）：按 modelId 注册一个"如何构建客户端"的供应商。
 * {@link #registerFailure} 模拟"配置非法/凭据缺失"（`SwitchResult.Aborted(BUILD_FAILED)`
 * 的触发源）——每次 {@code create()} 都调用供应商，不是缓存单例，与真实工厂"每次都
 * 新建一个客户端实例"的语义一致（{@code healthy()}/{@code shutdown()} 的状态因此
 * 天然只属于那一次构建出的实例，不会跨调用互相污染）。
 */
public final class InMemoryLlmClientFactory implements LlmClientFactory {

    private final Map<String, Supplier<LlmClient>> suppliers = new ConcurrentHashMap<>();

    public InMemoryLlmClientFactory register(String modelId, Supplier<LlmClient> supplier) {
        suppliers.put(modelId, supplier);
        return this;
    }

    public InMemoryLlmClientFactory registerFailure(String modelId, RuntimeException failure) {
        suppliers.put(modelId, () -> { throw failure; });
        return this;
    }

    @Override
    public LlmClient create(String modelId) {
        Supplier<LlmClient> supplier = suppliers.get(modelId);
        if (supplier == null) {
            throw new IllegalArgumentException("no client registered for modelId=" + modelId);
        }
        return supplier.get();
    }
}

