package com.trade.mall.agent.llm;

import com.trade.mall.agent.llm.infrastructure.InMemoryPromptVersionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptVersionStoreTest {

    @Test
    void 发布不可覆盖且历史版本可重新激活() {
        InMemoryPromptVersionStore store = new InMemoryPromptVersionStore("v1", "提示词一");
        store.publish("v2", "提示词二");

        assertEquals("v2", store.currentVersion());
        assertEquals(2, store.history(50).size());
        assertTrue(store.history(50).stream().filter(PromptVersionStore.PromptVersionInfo::current)
            .allMatch(version -> version.version().equals("v2")));
        assertThrows(IllegalStateException.class, () -> store.publish("v2", "覆盖内容"));

        assertEquals("v1", store.activate("v1").version());
        assertEquals("提示词一", store.currentPrompt());
        assertThrows(IllegalArgumentException.class, () -> store.activate("不存在"));
    }
}
