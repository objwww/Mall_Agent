package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.SkillSnapshot;
import com.trade.mall.agent.llm.SkillVersionStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 测试与本地运行使用的 Skill 版本库。 */
public final class InMemorySkillVersionStore implements SkillVersionStore {
    private final ConcurrentHashMap<String, SkillSnapshot> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> createdAt = new ConcurrentHashMap<>();
    private volatile SkillSnapshot current;

    public InMemorySkillVersionStore(String version, String instructions) { publish(version, instructions); }

    @Override public synchronized void publish(String version, String instructions) {
        SkillSnapshot snapshot = new SkillSnapshot(version, instructions);
        if (history.putIfAbsent(version, snapshot) != null) throw new IllegalStateException("skill version already exists: " + version);
        createdAt.put(version, System.currentTimeMillis());
        current = snapshot;
    }

    @Override public List<SkillVersionInfo> history(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        String active = current.version();
        return history.keySet().stream()
            .map(version -> new SkillVersionInfo(version, version.equals(active), createdAt.get(version)))
            .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt())).limit(limit).toList();
    }

    @Override public synchronized SkillSnapshot activate(String version) {
        SkillSnapshot snapshot = history.get(version);
        if (snapshot == null) throw new IllegalArgumentException("skill version not found: " + version);
        current = snapshot;
        return snapshot;
    }

    @Override public SkillSnapshot current() { return current; }
    @Override public Optional<SkillSnapshot> find(String version) { return Optional.ofNullable(history.get(version)); }
}
