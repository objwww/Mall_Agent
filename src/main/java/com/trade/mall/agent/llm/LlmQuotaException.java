package com.trade.mall.agent.llm;

/** 请求发出但被限流拒绝——可退避重试（`M-LLM-01` §4.2）。 */
public class LlmQuotaException extends RuntimeException {
    public LlmQuotaException(String message) { super(message); }
}

