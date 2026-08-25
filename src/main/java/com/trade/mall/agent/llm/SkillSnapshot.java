package com.trade.mall.agent.llm;

import java.io.Serializable;

/** 一次诊断实际使用的 Skill（技能指令）不可变快照。 */
public record SkillSnapshot(String version, String instructions) implements Serializable {
    public SkillSnapshot {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("skill version must not be blank");
        if (instructions == null) throw new IllegalArgumentException("skill instructions must not be null");
    }

    /** Skill 是系统约束的附加部分，不替换任务专用提示词。 */
    public String applyTo(String prompt) {
        return instructions.isBlank() ? prompt : prompt + "\n\n【当前技能指令】\n" + instructions;
    }
}
