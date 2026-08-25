package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.PromptSnapshot;
import com.trade.mall.agent.llm.PromptVersionStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/** 内存提示词版本库；publish（发布）保留历史版本，便于真实模拟重启恢复。 */
public final class InMemoryPromptVersionStore implements PromptVersionStore {
    private final ConcurrentHashMap<String, PromptSnapshot> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> createdAt = new ConcurrentHashMap<>();
    private volatile PromptSnapshot current;

    public InMemoryPromptVersionStore(String initialVersion, String initialPrompt) {
        publish(initialVersion, initialPrompt);
    }

    @Override
    public synchronized void publish(String newVersion, String newPrompt) {
        PromptSnapshot snapshot = new PromptSnapshot(newVersion, newPrompt);
        if (history.putIfAbsent(newVersion, snapshot) != null) {
            throw new IllegalStateException("prompt version already exists: " + newVersion);
        }
        createdAt.put(newVersion, System.currentTimeMillis());
        current = snapshot;
    }

    @Override
    public List<PromptVersionInfo> history(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        String currentVersion = current.version();
        return history.keySet().stream()
            .map(version -> new PromptVersionInfo(version, version.equals(currentVersion), createdAt.get(version)))
            .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt()))
            .limit(limit).toList();
    }

    @Override
    public synchronized PromptSnapshot activate(String version) {
        PromptSnapshot snapshot = history.get(version);
        if (snapshot == null) throw new IllegalArgumentException("prompt version not found: " + version);
        current = snapshot;
        return snapshot;
    }

    @Override public PromptSnapshot current() { return current; }
    @Override public Optional<PromptSnapshot> find(String version) { return Optional.ofNullable(history.get(version)); }
}
