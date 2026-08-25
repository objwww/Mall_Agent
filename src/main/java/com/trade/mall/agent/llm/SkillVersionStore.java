package com.trade.mall.agent.llm;

import java.util.List;
import java.util.Optional;

/** Skill 版本库；发布和切换只影响之后新开始的诊断。 */
public interface SkillVersionStore {
    SkillSnapshot current();
    Optional<SkillSnapshot> find(String version);
    default String currentVersion() { return current().version(); }
    List<SkillVersionInfo> history(int limit);
    void publish(String version, String instructions);
    SkillSnapshot activate(String version);

    record SkillVersionInfo(String version, boolean current, long createdAt) {}
}
