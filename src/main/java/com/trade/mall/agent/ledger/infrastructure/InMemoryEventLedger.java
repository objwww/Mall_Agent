package com.trade.mall.agent.ledger.infrastructure;

import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存事件账本（测试/演示用；生产实现走 MySQL，eventId 为主键）。
 * putIfAbsent 精确模拟“主键唯一 → 重复写入返回 false”。
 */
public final class InMemoryEventLedger implements EventLedger {

    private final Map<String, DomainEvent> byId = new ConcurrentHashMap<>();
    private final List<DomainEvent> ordered = new ArrayList<>();

    @Override
    public synchronized boolean append(DomainEvent event) {
        if (byId.putIfAbsent(event.eventId(), event) != null) {
            return false;   // 已存在 → 幂等
        }
        ordered.add(event);
        return true;
    }

    @Override public boolean exists(String eventId) { return byId.containsKey(eventId); }

    @Override public synchronized List<DomainEvent> eventsOf(String aggregateId) {
        List<DomainEvent> out = new ArrayList<>();
        for (DomainEvent e : ordered) if (e.aggregateId().equals(aggregateId)) out.add(e);
        return out;
    }

    /** 测试辅助：某类型事件计数。 */
    public synchronized long countOfType(String aggregateId, String type) {
        return eventsOf(aggregateId).stream().filter(e -> e.eventType().equals(type)).count();
    }
}

