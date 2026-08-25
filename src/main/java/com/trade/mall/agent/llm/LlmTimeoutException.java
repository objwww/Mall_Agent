package com.trade.mall.agent.llm;

/**
 * 请求发出但没等到应答——**无副作用，可直接重试**（`M-LLM-01` §1.3）。
 * 与 D2 `execution.port.DependencyUnavailableException`/D5
 * `evidence.port.DataSourceUnavailableException` 刻意不共享类型：三者触发场景相似，
 * 但归属的重试安全性完全不同（发钱超时绝不能重试，LLM 超时可以），共享类型会诱使
 * 未来有人写出"同一个 catch 分支统一重试"这种跨越安全边界的代码。
 */
public class LlmTimeoutException extends RuntimeException {
    public LlmTimeoutException(String message) { super(message); }
    public LlmTimeoutException(String message, Throwable cause) { super(message, cause); }
}

