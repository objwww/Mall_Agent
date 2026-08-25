package com.trade.mall.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MallAgentMainTest {

    @Test
    void 造题模型和处理模型不同才能启动() {
        MallAgentMain.Env env = new MallAgentMain.Env(Map.of("CASE_AUTHOR_MODEL_ID", "case-author-v1"));

        assertDoesNotThrow(() -> MallAgentMain.ensureModelRoleIsolation(env, "mall-handler-v1"));
    }

    @Test
    void 造题模型和处理模型相同应拒绝启动() {
        MallAgentMain.Env env = new MallAgentMain.Env(Map.of("CASE_AUTHOR_MODEL_ID", "shared-model"));

        assertThrows(IllegalArgumentException.class,
            () -> MallAgentMain.ensureModelRoleIsolation(env, "SHARED-MODEL"));
    }

    @Test
    void 未声明造题模型应拒绝启动() {
        MallAgentMain.Env env = new MallAgentMain.Env(Map.of());

        assertThrows(IllegalArgumentException.class,
            () -> MallAgentMain.ensureModelRoleIsolation(env, "mall-handler-v1"));
    }
}
