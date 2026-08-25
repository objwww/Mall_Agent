package com.trade.mall.agent.ledger;

import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.TransitionContext;
import com.trade.mall.agent.execution.domain.TransitionTrigger;

import java.util.List;

/**
 * 把一次 (trigger, to, ctx) 转移映射为它应产出的领域事件（纯函数，零 I/O）。
 * 一次转移可能产出多条事件（如 ACK_SUCCESS → Attempt.Succeeded + Execution.Settled）。
 *
 * 说明：occurredAt 由调用侧注入，保持工厂纯粹、可测（不在工厂内取系统时钟）。
 */
public final class TransitionEventFactory {

    private TransitionEventFactory() {}

    public static List<DomainEvent> build(String opId, TransitionTrigger trigger,
                                          ExecutionState to, TransitionContext ctx, long now) {
        int seq = ctx.seq();
        int n = ctx.reconcileCount();
        return switch (trigger) {
            case DISPATCH -> List.of(
                ev(EventIds.attemptDispatching(opId, seq), opId, "Attempt.Dispatching", seq, ctx.reason(), now));
            case ACK_SUCCESS -> List.of(
                ev(EventIds.attemptOk(opId, seq), opId, "Attempt.Succeeded", seq, ctx.reason(), now),
                ev(EventIds.settled(opId), opId, "Execution.Settled", 0, "SUCCEEDED", now));
            case ACK_FAILURE -> List.of(
                ev(EventIds.attemptFail(opId, seq), opId, "Attempt.Failed", seq, ctx.reason(), now),
                ev(EventIds.settled(opId), opId, "Execution.Settled", 0, "FAILED", now));
            case TIMEOUT -> List.of(
                ev(EventIds.attemptUnknown(opId, seq), opId, "Attempt.Unknown", seq, ctx.reason(), now),
                ev(EventIds.unknownTimeout(opId, seq), opId, "Execution.Unknown", seq, "TIMEOUT", now));
            case CRASH_RECOVERED -> List.of(
                ev(EventIds.unknownCrash(opId, seq), opId, "Execution.Unknown", seq, "CRASH_RECOVERY", now));
            case RECONCILE_SUCCESS -> List.of(
                ev(EventIds.reconcileResolved(opId, n), opId, "Reconcile.Resolved", n, "SUCCEEDED", now),
                ev(EventIds.settled(opId), opId, "Execution.Settled", 0, "SUCCEEDED", now));
            case RECONCILE_FAILURE -> List.of(
                ev(EventIds.reconcileResolved(opId, n) + ":F", opId, "Reconcile.Resolved", n, "FAILED", now),
                ev(EventIds.settled(opId), opId, "Execution.Settled", 0, "FAILED", now));
            case RECONCILE_INCONCLUSIVE -> List.of(
                ev(EventIds.reconcileStillUnknown(opId, n), opId, "Reconcile.StillUnknown", n, ctx.reason(), now));
            case ESCALATE -> List.of(
                ev(EventIds.escalated(opId), opId, "Execution.Escalated", 0, ctx.reason(), now),
                ev(EventIds.settled(opId), opId, "Execution.Settled", 0, "ESCALATED", now));
            case DEPENDENCY_UNAVAILABLE -> List.of(
                ev(EventIds.blocked(opId, seq), opId, "Execution.Blocked", seq, ctx.reason(), now));
            case DEPENDENCY_RESTORED -> List.of(
                ev(EventIds.unblocked(opId, seq), opId, "Execution.Unblocked", seq, ctx.reason(), now));
        };
    }

    private static DomainEvent ev(String id, String agg, String type, int seq, String payload, long now) {
        return new DomainEvent(id, agg, type, seq, payload, now);
    }
}

