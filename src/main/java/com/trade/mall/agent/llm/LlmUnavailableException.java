package com.trade.mall.agent.llm;

/** 请求根本没发出去——可安全换实例/等恢复重试（`M-LLM-01` §4.2）。 */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) { super(message); }
    public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
}

