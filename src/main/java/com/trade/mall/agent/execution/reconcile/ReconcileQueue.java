package com.trade.mall.agent.execution.reconcile;

import java.util.List;
import java.util.Optional;

/**
 * ReconcileQueue —— 端口。记录"哪些 UNKNOWN 执行还等着被对账、什么时候该再问一次"。
 * 生产实现读写 `agent_action_execution` 上的 `reconcile_count`/`next_reconcile_at`
 * 字段（`idx_exec_state_next` 索引支撑扫描），不是独立的表。
 */
public interface ReconcileQueue {

    /** 首次入队（reconcileCount=0），如果已经在队列里则什么也不做（幂等）。 */
    void enqueueFirstIfAbsent(String operationId, long now);

    /** 退避重排：写入新的 reconcileCount 与下一次到期时间，firstUnknownAt 保持不变。 */
    void reschedule(String operationId, int newReconcileCount, long nextAt);

    /** 已收敛（SUCCEEDED/FAILED/ESCALATED）或已恢复为其他态：移出队列。 */
    void remove(String operationId);

    Optional<ReconcileQueueEntry> entry(String operationId);

    /** 到期（nextReconcileAt <= now）的条目，供 ReconcileScheduler 逐条处理。 */
    List<ReconcileQueueEntry> due(long now, int limit);
}

