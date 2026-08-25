package com.trade.mall.agent.execution.reconcile;

import com.trade.mall.agent.alert.AlertPort;
import com.trade.mall.agent.execution.application.DependencyUnavailableClassifier;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.application.TransitionCommand;
import com.trade.mall.agent.execution.domain.TransitionContext;
import com.trade.mall.agent.execution.domain.TransitionTrigger;
import com.trade.mall.agent.execution.port.ActionPort;
import com.trade.mall.agent.execution.port.PortOutcome;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * ReconcileScheduler —— M-EXEC-04。UNKNOWN 唯一合法的出路（ADR-001）：只 query，
 * 从不 execute。这是它与 {@code DefaultActionDispatcher} 的根本区别，也是为什么
 * {@link ActionPort} 要拆成 execute()/query() 两个方法（M-ADP-02 的设计动机，D2）。
 *
 * <p>三种 query 结局 + 一种"问不出去"，四条出路（见 §3.3 的易漏分支）：</p>
 * <pre>
 *   Success          → RECONCILE_SUCCESS      → SUCCEEDED（移出队列）
 *   BusinessFailure  → RECONCILE_FAILURE      → FAILED（移出队列）
 *   Inconclusive     → RECONCILE_INCONCLUSIVE → UNKNOWN（自环 T9，退避重排）
 *   Unavailable/抛"确定未发出"类异常 → 不转移！状态原地不动，退避后重试查询
 * </pre>
 *
 * <p>最后一条最容易漏：对账查询本身也是一次外部调用，也会"问不出去"。这和
 * "渠道也说不清"（Inconclusive）是两回事——问不出去时，我们连"渠道怎么说"都不知道，
 * 谈不上"渠道也不确定"，所以不能套用 T9；而 BLOCKED 是给"动作没发出"用的语义，
 * 这里动作**已经**发出了，只是这次查询没发出去——唯一正确的处理是**什么都不改**，
 * 保持 UNKNOWN，只把这次失败计入退避节奏，下一轮再问。</p>
 */
public final class ReconcileScheduler {

    private final ExecutionApplicationService service;
    private final ActionPort actionPort;
    private final ReconcileQueue queue;
    private final AlertPort alertPort;
    private final LongSupplier clock;

    public ReconcileScheduler(ExecutionApplicationService service, ActionPort actionPort,
                               ReconcileQueue queue, AlertPort alertPort, LongSupplier clock) {
        this.service = service;
        this.actionPort = actionPort;
        this.queue = queue;
        this.alertPort = alertPort;
        this.clock = clock;
    }

    public ReconcileRunReport runDue(int limit) {
        long now = clock.getAsLong();
        List<ReconcileQueueEntry> dueEntries = queue.due(now, limit);
        int resolved = 0, stillUnknown = 0, escalated = 0, queryUnavailable = 0, failed = 0;

        for (ReconcileQueueEntry e : dueEntries) {
            try {
                Outcome o = processOne(e, now);
                switch (o) {
                    case RESOLVED -> resolved++;
                    case STILL_UNKNOWN -> stillUnknown++;
                    case ESCALATED -> escalated++;
                    case QUERY_UNAVAILABLE -> queryUnavailable++;
                }
            } catch (Exception ex) {
                failed++;
                // 单条失败不中断整批——理由同 M-EXEC-05 §2.1
            }
        }

        if (failed > 0) {
            alertPort.critical("reconcile.failed",
                "对账处理失败 " + failed + " 条，需人工确认（可能长期停留在 UNKNOWN）。");
        }
        return new ReconcileRunReport(dueEntries.size(), resolved, stillUnknown, escalated, queryUnavailable, failed);
    }

    private enum Outcome { RESOLVED, STILL_UNKNOWN, ESCALATED, QUERY_UNAVAILABLE }

    private Outcome processOne(ReconcileQueueEntry e, long now) {
        Duration elapsed = Duration.ofMillis(now - e.firstUnknownAt());

        // 24h 上限先查：已经超时就不必再多问一次渠道，直接升级人工。
        if (ReconcilePolicy.shouldEscalate(elapsed)) {
            service.transition(TransitionCommand.of(e.operationId(), TransitionTrigger.ESCALATE,
                TransitionContext.reconcile(e.reconcileCount(), "24h reconcile timeout")));
            queue.remove(e.operationId());
            return Outcome.ESCALATED;
        }

        PortOutcome outcome;
        try {
            outcome = actionPort.query(e.operationId());
        } catch (Throwable t) {
            if (DependencyUnavailableClassifier.isDependencyUnavailable(t)) {
                return backoffQueryUnavailable(e, now);
            }
            // 其余异常按"说不清"处理，等价于渠道也不确定：仍然退避重排，但要走 RECONCILE_INCONCLUSIVE
            return inconclusive(e, now);
        }

        if (outcome instanceof PortOutcome.Success) {
            service.transition(TransitionCommand.of(e.operationId(), TransitionTrigger.RECONCILE_SUCCESS,
                TransitionContext.reconcile(e.reconcileCount(), "reconciled")));
            queue.remove(e.operationId());
            return Outcome.RESOLVED;
        }
        if (outcome instanceof PortOutcome.BusinessFailure f) {
            service.transition(TransitionCommand.of(e.operationId(), TransitionTrigger.RECONCILE_FAILURE,
                TransitionContext.reconcile(e.reconcileCount(), f.errorCode())));
            queue.remove(e.operationId());
            return Outcome.RESOLVED;
        }
        if (outcome instanceof PortOutcome.Unavailable) {
            // 查询请求本身没发出去：与"渠道也说不清"不是一回事，见类头注释。
            return backoffQueryUnavailable(e, now);
        }
        // Inconclusive：渠道确实回答了，只是回答"我也不知道"。
        return inconclusive(e, now);
    }

    private Outcome inconclusive(ReconcileQueueEntry e, long now) {
        service.transition(TransitionCommand.of(e.operationId(), TransitionTrigger.RECONCILE_INCONCLUSIVE,
            TransitionContext.reconcile(e.reconcileCount(), "channel also unsure")));
        int next = e.reconcileCount() + 1;
        queue.reschedule(e.operationId(), next, now + ReconcilePolicy.nextDelay(next).toMillis());
        return Outcome.STILL_UNKNOWN;
    }

    private Outcome backoffQueryUnavailable(ReconcileQueueEntry e, long now) {
        // 状态原地不动：不调用 service.transition，因为没有任何一个 TransitionTrigger
        // 描述"对账查询问不出去"这件事——这不是执行状态机要关心的事实，只是调度层
        // 的一次失败重试，所以只退避重排，不落任何转移。
        int next = e.reconcileCount() + 1;
        queue.reschedule(e.operationId(), next, now + ReconcilePolicy.nextDelay(next).toMillis());
        return Outcome.QUERY_UNAVAILABLE;
    }
}

