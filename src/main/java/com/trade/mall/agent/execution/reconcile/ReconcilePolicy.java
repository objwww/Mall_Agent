package com.trade.mall.agent.execution.reconcile;

import java.time.Duration;

/**
 * ReconcilePolicy —— 纯策略（零 I/O），沿用既有 `RefundReconcilePolicy` 的退避节奏
 * （`FACT-REFUND-007`）：5/10/30/60 分钟退避，超过 24 小时升级为 CRITICAL。
 *
 * <p><b>首次延迟依赖 U-001（未决）</b>：当前沿用"超时后不能立即确定"的保守假设，
 * 首次对账延迟与退避第 0 档相同（5 分钟）。若 U-001 的结论是"超时后立即可查确定状态"，
 * 应把首次延迟单独缩到秒级——见 `domain_model_and_invariants.md §3.4`。</p>
 */
public final class ReconcilePolicy {

    private static final Duration[] BACKOFF = {
        Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(60)
    };

    /** 24h 上限：超过则不再退避重试，直接升级人工（T10）。 */
    public static final Duration CRITICAL_THRESHOLD = Duration.ofHours(24);

    private ReconcilePolicy() {}

    /** 第 reconcileCount 次退避后的延迟（超出表长则一律用最后一档，即 60 分钟）。 */
    public static Duration nextDelay(int reconcileCount) {
        int idx = Math.min(Math.max(reconcileCount, 0), BACKOFF.length - 1);
        return BACKOFF[idx];
    }

    /** 距首次进入 UNKNOWN 已经过 elapsed，是否应当升级为 ESCALATED（而不是继续退避）。 */
    public static boolean shouldEscalate(Duration elapsedSinceFirstUnknown) {
        return elapsedSinceFirstUnknown.compareTo(CRITICAL_THRESHOLD) >= 0;
    }
}

