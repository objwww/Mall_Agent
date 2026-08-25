package com.trade.mall.agent.execution.reconcile;

/** 一次 runDue() 的结果统计。 */
public record ReconcileRunReport(int due, int resolved, int stillUnknown, int escalated,
                                  int queryUnavailable, int failed) {}

