package com.trade.mall.agent.alert.infrastructure;

import com.trade.mall.agent.alert.AlertPort;

import java.util.ArrayList;
import java.util.List;

/** 内存告警记录器（测试用）：记住每一次 critical()/warning() 调用，供自检断言"确实告警了"。 */
public final class InMemoryAlertPort implements AlertPort {
    public enum Severity { CRITICAL, WARNING }
    public record Alert(Severity severity, String code, String message) {}

    private final List<Alert> alerts = new ArrayList<>();

    @Override
    public synchronized void critical(String code, String message) {
        alerts.add(new Alert(Severity.CRITICAL, code, message));
    }

    @Override
    public synchronized void warning(String code, String message) {
        alerts.add(new Alert(Severity.WARNING, code, message));
    }

    public synchronized List<Alert> alerts() { return List.copyOf(alerts); }
    public synchronized List<Alert> alerts(Severity severity) {
        return alerts.stream().filter(a -> a.severity() == severity).toList();
    }
    public synchronized int count() { return alerts.size(); }
}

