package com.trade.mall.agent.execution.reconcile;

/**
 * 一条对账排队记录：operationId 独立于 ActionExecution 聚合，是纯粹的调度元数据——
 * "什么时候该再问一次"不是领域事实，是运行时调度关注点，所以不放进聚合、单独一张表
 * （生产：`agent_action_execution.reconcile_count/next_reconcile_at`）。
 */
public record ReconcileQueueEntry(String operationId, int reconcileCount, long nextReconcileAt, long firstUnknownAt) {}

