package com.trade.mall.agent.ledger;

import java.util.List;

/**
 * 事件账本（端口）。M-LED-01。
 *
 * 职责：不可变追加；eventId 幂等；供崩溃恢复查询。
 * 不投递、不做事件溯源（ADR-005）——状态表是权威，账本是审计与恢复依据。
 */
public interface EventLedger {

    /**
     * 幂等追加。返回 true=首次写入；false=eventId 已存在（幂等，不抛异常）。
     * 见 domain_events.md：重复写入被数据库主键拒绝即幂等。
     */
    boolean append(DomainEvent event);

    /** 事件是否已存在（M-EXEC-03 的“先记录后发出”运行时断言要用）。 */
    boolean exists(String eventId);

    /** 按聚合取事件（崩溃恢复扫描要用）。 */
    List<DomainEvent> eventsOf(String aggregateId);
}

