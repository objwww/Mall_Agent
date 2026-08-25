package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.LlmClient;
import com.trade.mall.agent.llm.LlmClientFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI-compatible（兼容 OpenAI Chat Completions 协议）的真实 HTTP 客户端工厂。
 * 不引入 SDK：mall-agent 当前没有 JSON/HTTP 第三方依赖，JDK HttpClient 足够覆盖本项目的
 * system+user → JSON object 这一种真实调用形状。
 */
public final class OpenAiCompatibleLlmClientFactory implements LlmClientFactory {

    public record Endpoint(URI baseUri, String apiKey, String remoteModel, Duration timeout) {
        public Endpoint {
            if (baseUri == null) throw new IllegalArgumentException("baseUri required");
            if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
            if (remoteModel == null || remoteModel.isBlank()) throw new IllegalArgumentException("remoteModel required");
            if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private final Map<String, Endpoint> endpoints;

    public OpenAiCompatibleLlmClientFactory(Map<String, Endpoint> endpoints) {
        this.endpoints = Map.copyOf(endpoints);
    }

    @Override
    public LlmClient create(String modelId) {
        Endpoint endpoint = endpoints.get(modelId);
        if (endpoint == null) throw new IllegalArgumentException("unknown LLM modelId: " + modelId);
        return new OpenAiCompatibleLlmClient(modelId, endpoint);
    }
}

