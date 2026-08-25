package com.trade.mall.agent.llm;

import com.trade.mall.agent.llm.infrastructure.InMemorySkillVersionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillVersionStoreTest {
    @Test void 发布不可覆盖且可回滚并真实组合系统指令() {
        InMemorySkillVersionStore store = new InMemorySkillVersionStore("v1", "先核验证据");
        store.publish("v2", "禁止无证据结论");

        assertEquals("v2", store.currentVersion());
        assertEquals("基础提示\n\n【当前技能指令】\n禁止无证据结论", store.current().applyTo("基础提示"));
        assertTrue(store.history(50).stream().filter(SkillVersionStore.SkillVersionInfo::current)
            .allMatch(version -> version.version().equals("v2")));
        assertThrows(IllegalStateException.class, () -> store.publish("v2", "覆盖"));
        assertEquals("v1", store.activate("v1").version());
    }
}
