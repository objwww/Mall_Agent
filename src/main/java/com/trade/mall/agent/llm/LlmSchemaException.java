package com.trade.mall.agent.llm;

/**
 * LLM 输出不满足约定的结构化 schema——`DEP-LLM-001` 失败语义③（`understanding` 包
 * 的 `TicketUnderstandingService` 捕获后按"重试 N 次（带修复提示）→ 超过次数转人工"
 * 处理，见该类类头）。
 */
public class LlmSchemaException extends RuntimeException {
    public LlmSchemaException(String message) { super(message); }
    public LlmSchemaException(String message, Throwable cause) { super(message, cause); }
}

