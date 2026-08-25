package com.trade.mall.agent.execution.domain;

/**
 * 转移上下文（VO）：携带一次转移需要的元数据。
 * seq 用于 Attempt 类事件的 eventId；reconcileCount 用于对账类事件；reason 记录来因。
 */
public final class TransitionContext {
    private final int seq;
    private final int reconcileCount;
    private final String reason;

    private TransitionContext(int seq, int reconcileCount, String reason) {
        this.seq = seq; this.reconcileCount = reconcileCount; this.reason = reason;
    }
    public static TransitionContext of(int seq, String reason) {
        return new TransitionContext(seq, 0, reason);
    }
    public static TransitionContext reconcile(int reconcileCount, String reason) {
        return new TransitionContext(0, reconcileCount, reason);
    }
    public static TransitionContext none() { return new TransitionContext(0, 0, ""); }

    public int seq() { return seq; }
    public int reconcileCount() { return reconcileCount; }
    public String reason() { return reason == null ? "" : reason; }
}

