package com.trade.mall.agent.alert.infrastructure;

import com.trade.mall.agent.alert.AlertPort;

import java.time.Instant;

/** standalone（独立进程）最小告警实现；真实部署可替换成现有告警平台。 */
public final class StderrAlertPort implements AlertPort {
    @Override public void critical(String code, String message) { emit("CRITICAL", code, message); }
    @Override public void warning(String code, String message) { emit("WARNING", code, message); }
    private static void emit(String level, String code, String message) {
        System.err.println(Instant.now() + " [" + level + "] " + code + " " + (message == null ? "" : message));
    }
}

