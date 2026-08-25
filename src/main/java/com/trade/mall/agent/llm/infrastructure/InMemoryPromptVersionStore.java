package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.PromptSnapshot;
import com.trade.mall.agent.llm.PromptVersionStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存提示词版本库；publish（发布）保留历史版本，便于真实模拟重启恢复。 */
public final class InMemoryPromptVersionStore implements PromptVersionStore {
    private final ConcurrentHashMap<String, PromptSnapshot> history = new ConcurrentHashMap<>();
    private volatile PromptSnapshot current;

    public InMemoryPromptVersionStore(String initialVersion, String initialPrompt) {
        publish(initialVersion, initialPrompt);
    }

    public void publish(String newVersion, String newPrompt) {
        PromptSnapshot snapshot = new PromptSnapshot(newVersion, newPrompt);
        history.put(newVersion, snapshot);
        current = snapshot;
    }

    @Override public PromptSnapshot current() { return current; }
    @Override public Optional<PromptSnapshot> find(String version) { return Optional.ofNullable(history.get(version)); }
}

