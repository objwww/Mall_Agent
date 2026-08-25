package com.trade.mall.agent.llm;

/**
 * LlmClientFactory —— 按 modelId 构建一个新客户端（`M-LLM-01` §5.1 `DefaultLlmRegistry`
 * 的协作者）。构建失败（配置非法、凭据缺失）应抛出任意 {@link RuntimeException}——
 * `DefaultLlmRegistry.switchTo()` 会捕获并翻译成 {@code SwitchResult.Aborted(BUILD_FAILED)}，
 * 不要求这里定义专门的异常类型。
 */
public interface LlmClientFactory {
    LlmClient create(String modelId);
}

