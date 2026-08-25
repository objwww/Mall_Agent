package com.trade.mall.agent.llm;

import java.io.Serializable;

/**
 * PromptSnapshot（提示词快照）：一次原子读取到的“版本号 + 提示词正文”。
 *
 * <p>版本号和正文必须作为一个不可分割的值读取，否则发布新版本时可能出现
 * “记录的是 v2、实际发送的却还是 v1 正文”这种审计错配。</p>
 */
public record PromptSnapshot(String version, String prompt) implements Serializable {
    public PromptSnapshot {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("prompt version must not be blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt content must not be blank");
        }
    }
}

