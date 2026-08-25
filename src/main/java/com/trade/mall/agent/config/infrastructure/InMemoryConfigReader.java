package com.trade.mall.agent.config.infrastructure;

import com.trade.mall.agent.config.KillSwitch;

/**
 * 内存配置源（测试/演示用）：可任意切换"当前值"与"读取即抛异常"，
 * 用来驱动 KillSwitch 的两条路径——正常读到 true/false，以及"配置源不可达"。
 * 生产实现见 {@code NacosConfigReader}（本 sandbox 因 Maven Central 被墙，
 * 未接入真实 Nacos SDK，诚实标注为 D2 未完成项，见 D2-REPORT.md）。
 */
public final class InMemoryConfigReader implements KillSwitch.ConfigReader {
    private volatile Boolean value = KillSwitch.DEFAULT_MONEY_ACTION_ALLOWED;
    private volatile boolean throwOnRead = false;

    public InMemoryConfigReader set(Boolean v) { this.value = v; return this; }
    public InMemoryConfigReader breakReading() { this.throwOnRead = true; return this; }
    public InMemoryConfigReader repair() { this.throwOnRead = false; return this; }

    @Override
    public Boolean readMoneyActionAllowed() throws Exception {
        if (throwOnRead) throw new java.io.IOException("simulated: config source unreachable");
        return value;
    }
}

