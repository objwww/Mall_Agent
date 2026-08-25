package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.reconcile.ReconcileQueue;
import com.trade.mall.agent.execution.reconcile.ReconcileQueueEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存对账队列（测试/演示用；生产读写 agent_action_execution 的
 * reconcile_count/next_reconcile_at 字段，走 idx_exec_state_next 索引）。
 */
public final class InMemoryReconcileQueue implements ReconcileQueue {

    private static final long CLAIM_LEASE_MILLIS = 30_000L;
    private final Map<String, ReconcileQueueEntry> store = new ConcurrentHashMap<>();

    @Override
    public void enqueueFirstIfAbsent(String operationId, long now) {
        store.computeIfAbsent(operationId, id -> new ReconcileQueueEntry(id, 0, now, now));
    }

    @Override
    public void reschedule(String operationId, int newReconcileCount, long nextAt) {
        store.computeIfPresent(operationId, (id, old) ->
            new ReconcileQueueEntry(id, newReconcileCount, nextAt, old.firstUnknownAt()));
    }

    @Override
    public void remove(String operationId) {
        store.remove(operationId);
    }

    @Override
    public Optional<ReconcileQueueEntry> entry(String operationId) {
        return Optional.ofNullable(store.get(operationId));
    }

    @Override
    public synchronized List<ReconcileQueueEntry> due(long now, int limit) {
        List<ReconcileQueueEntry> out = new ArrayList<>();
        for (ReconcileQueueEntry e : store.values()) {
            if (out.size() >= limit) break;
            if (e.nextReconcileAt() <= now) {
                out.add(e);
                // 与 JDBC 生产实现保持同一失败语义：due() 同时完成短租约认领；
                // 若调用方在 query/remove/reschedule 前崩溃，租约到期后还能再次被发现。
                store.put(e.operationId(), new ReconcileQueueEntry(
                    e.operationId(), e.reconcileCount(), now + CLAIM_LEASE_MILLIS, e.firstUnknownAt()));
            }
        }
        return out;
    }
}

