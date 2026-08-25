package com.trade.mall.agent.config;

import com.trade.mall.agent.config.infrastructure.InMemoryConfigReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 与 SelfCheck §8 一一对应：默认值写死为 false + 读配置失败 fail-closed (INV-CFG-001)。 */
class KillSwitchTest {

    @Test void default_is_hardcoded_false() {
        assertFalse(KillSwitch.DEFAULT_MONEY_ACTION_ALLOWED);
    }

    @Test void reads_true_and_false_normally() {
        var cfg = new InMemoryConfigReader().set(true);
        assertTrue(new KillSwitch(cfg).moneyActionAllowed());
        cfg.set(false);
        assertFalse(new KillSwitch(cfg).moneyActionAllowed());
    }

    @Test void unreachable_config_source_fails_closed() {
        var cfg = new InMemoryConfigReader().set(true).breakReading();
        assertFalse(new KillSwitch(cfg).moneyActionAllowed(),
            "配置源不可达必须 fail-closed，不能因为异常被吞就意外放行");
    }

    @Test void missing_config_key_fails_closed() {
        var cfg = new InMemoryConfigReader().set(null);
        assertFalse(new KillSwitch(cfg).moneyActionAllowed());
    }
}

