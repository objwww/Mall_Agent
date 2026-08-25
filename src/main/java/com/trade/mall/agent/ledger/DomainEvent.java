package com.trade.mall.agent.ledger;

/**
 * 领域事件（不可变值）。由聚合根产出，经账本持久化。
 *
 * <p><b>eventId 是业务语义构造的幂等键，不是随机 UUID</b>（domain_events.md §1.3）：
 * 崩溃重放会重跑逻辑，随机 eventId 会写重复事件、账本自毁。业务语义 eventId + 主键唯一，
 * 使“同一业务事实重放两次”在账本层是一条，不是两条。</p>
 */
public record DomainEvent(
        String eventId,      // 业务语义幂等键，如 op-1:DISPATCHING:1
        String aggregateId,  // 所属聚合，如 operationId
        String eventType,    // 如 Attempt.Dispatching
        int seqNo,           // 尝试序号 / 对账次数，用于崩溃恢复查询
        String payload,      // 事件特有数据（此处简化为字符串）
        long occurredAt      // 发生时间（非身份，仅记录）
) {
    public DomainEvent {
        if (eventId == null || eventId.isBlank())
            throw new IllegalArgumentException("eventId must not be blank");
    }
}

