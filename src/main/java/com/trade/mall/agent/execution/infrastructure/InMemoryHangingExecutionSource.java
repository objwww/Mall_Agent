package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.domain.ActionAttempt;
import com.trade.mall.agent.execution.domain.AttemptOutcome;
import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.reconcile.ReconcileQueue;
import com.trade.mall.agent.execution.recovery.HangingExecution;
import com.trade.mall.agent.execution.recovery.HangingExecutionSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 内存版悬挂来源（测试/演示用；生产是独立只读 Mapper + `FOR UPDATE SKIP LOCKED`，
 * 见 {@link HangingExecutionSource} 类头关于"从三表 JOIN 简化为单表条件"的说明）。
 *
 * <p>两类候选：</p>
 * <ol>
 *   <li>{@code state == DISPATCHED}：真正悬挂，需要走 CRASH_RECOVERED；</li>
 *   <li>{@code state == UNKNOWN} 且不在对账队列里：上次恢复成功但排队失败，只需补排。</li>
 * </ol>
 *
 * <p>用一个 30 秒短租约模拟生产 JDBC 版 {@code recovery_claim_until}：同一 operationId
 * 在租约内只会被一个扫描器拿到；如果处理过程失败、状态没有推进，租约到期后必须重新可见。
 * 这样测试替身不会再使用“认领一次永久消失”这种与生产故障语义相反的简化。</p>
 */
public final class InMemoryHangingExecutionSource implements HangingExecutionSource {

    private static final long CLAIM_LEASE_MILLIS = 30_000L;

    private final InMemoryActionExecutionRepository repo;
    private final ReconcileQueue reconcileQueue;
    private final LongSupplier clock;
    private final Map<String, Long> claimedUntil = new ConcurrentHashMap<>();

    public InMemoryHangingExecutionSource(InMemoryActionExecutionRepository repo, ReconcileQueue reconcileQueue) {
        this(repo, reconcileQueue, System::currentTimeMillis);
    }

    public InMemoryHangingExecutionSource(InMemoryActionExecutionRepository repo, ReconcileQueue reconcileQueue, LongSupplier clock) {
        this.repo = repo;
        this.reconcileQueue = reconcileQueue;
        this.clock = clock;
    }

    @Override
    public List<HangingExecution> claimHanging(int limit) {
        long now = clock.getAsLong();
        List<HangingExecution> out = new ArrayList<>();
        for (var row : repo.snapshotAll()) {
            if (out.size() >= limit) break;
            HangingExecution candidate = classify(row);
            if (candidate == null) continue;
            long leaseUntil = now + CLAIM_LEASE_MILLIS;
            Long accepted = claimedUntil.compute(row.operationId(), (id, oldUntil) ->
                oldUntil == null || oldUntil <= now ? leaseUntil : oldUntil);
            if (accepted != null && accepted == leaseUntil) out.add(candidate);
        }
        return out;
    }

    private HangingExecution classify(InMemoryActionExecutionRepository.ExecutionRowSnapshot row) {
        if (row.state() == ExecutionState.DISPATCHED) {
            int seq = latestDispatchingSeq(row.attempts());
            return new HangingExecution(row.operationId(), row.state(), seq);
        }
        if (row.state() == ExecutionState.UNKNOWN && reconcileQueue.entry(row.operationId()).isEmpty()) {
            int seq = latestDispatchingSeq(row.attempts());
            return new HangingExecution(row.operationId(), row.state(), seq);
        }
        return null;
    }

    private static int latestDispatchingSeq(List<ActionAttempt> attempts) {
        int best = 0;
        for (ActionAttempt a : attempts) {
            if (a.outcome() == AttemptOutcome.DISPATCHING && a.seqNo() > best) best = a.seqNo();
        }
        if (best > 0) return best;
        // 没有仍处于 DISPATCHING 的尝试（比如已被上一次恢复结算为 UNKNOWN）——退化取最大 seq。
        int max = 0;
        for (ActionAttempt a : attempts) if (a.seqNo() > max) max = a.seqNo();
        return max;
    }
}

