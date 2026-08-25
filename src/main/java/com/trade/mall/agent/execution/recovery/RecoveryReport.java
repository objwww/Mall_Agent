package com.trade.mall.agent.execution.recovery;

/** 一次 scan() 的结果统计。见 M-EXEC-05-recovery.md §2。 */
public record RecoveryReport(int scanned, int recovered, int skippedLocked, int failed) {}

