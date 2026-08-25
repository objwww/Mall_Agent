package com.trade.mall.agent.llm;

/**
 * 一次 LLM 应答（值对象）。{@code content} 是原始文本——D6 不在这一层做 JSON 解析，
 * 解析/schema 校验是调用方（`M-CAP-01` 的 `TicketUnderstandingService`）的职责，
 * `LlmClient` 只负责"问出去、拿到什么原样交回来"，不替调用方猜测输出的语义结构。
 */
public record LlmResponse(String content, String modelId) {
    public LlmResponse {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId must not be blank");
    }
}

