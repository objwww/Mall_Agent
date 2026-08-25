package com.trade.mall.agent.llm;

import java.util.Optional;
import java.util.List;

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

    /** 按发布时间倒序返回版本元数据，不返回提示词正文。 */
    List<PromptVersionInfo> history(int limit);

    /** 发布不可变新版本，并立即供新 Diagnosis 使用。 */
    void publish(String version, String prompt);

    /** 重新激活历史版本；不复制、不覆盖历史内容。 */
    PromptSnapshot activate(String version);

    record PromptVersionInfo(String version, boolean current, long createdAt) {}
}
