package com.trade.mall.agent.llm;

import java.util.Optional;

/**
 * PromptVersionStore（提示词版本库）。current() 给新 Diagnosis 使用；find(version) 用于
 * JVM（Java 虚拟机）重启后按已持久化 VersionSnapshot（版本快照）恢复旧提示词。
 */
public interface PromptVersionStore {
    PromptSnapshot current();

    /** 历史版本读取；默认只支持当前版本，内存/JDBC 实现可保存完整历史。 */
    default Optional<PromptSnapshot> find(String version) {
        PromptSnapshot snapshot = current();
        return snapshot.version().equals(version) ? Optional.of(snapshot) : Optional.empty();
    }

    default String currentVersion() { return current().version(); }
    default String currentPrompt() { return current().prompt(); }
}

