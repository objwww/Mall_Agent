package com.trade.mall.agent.approval;

/**
 * 批准域的业务语义 eventId 构造器——与 {@code ledger.EventIds} 同一套规则（D1）：
 * 崩溃重放会重跑逻辑，业务语义 eventId + 账本主键唯一，使重放在账本层被吞为幂等。
 *
 * <p>{@code Approval.Granted} 与 {@code Approval.Consumed} 分事件写入同一个
 * {@code EventLedger}（与 ActionExecution 共用账本，ADR-008 同库不跨 MQ）——
 * 崩溃发生在"已批准但还没来得及消费"和"已消费"之间时，审计能据此分辨到底停在哪一步。</p>
 */
public final class ApprovalEventIds {
    private ApprovalEventIds() {}
    public static String requested(String approvalId) { return approvalId + ":REQUESTED:1"; }
    public static String granted(String approvalId)  { return approvalId + ":GRANTED:1"; }
    public static String rejected(String approvalId)  { return approvalId + ":REJECTED:1"; }
    public static String consumed(String approvalId)  { return approvalId + ":CONSUMED:1"; }
    public static String expired(String approvalId)   { return approvalId + ":EXPIRED:1"; }
}

