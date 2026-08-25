package com.trade.mall.agent.llm;

/**
 * 一次 LLM 请求（值对象）。D6 范围内只服务 `M-CAP-01` 的结构化理解场景，字段刻意精简——
 * 不建模多轮对话历史、不建模工具调用（tool calling），那些是 D7+ 判定/提议层接入时
 * 才需要的扩展面，现在加会是没有使用方验证过的猜测性设计。
 */
public record LlmRequest(String systemPrompt, String userPrompt, int maxTokens) {
    public LlmRequest {
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("systemPrompt must not be blank");
        if (userPrompt == null || userPrompt.isBlank()) throw new IllegalArgumentException("userPrompt must not be blank");
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive");
    }
}

