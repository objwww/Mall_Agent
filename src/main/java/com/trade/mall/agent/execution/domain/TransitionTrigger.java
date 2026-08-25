package com.trade.mall.agent.execution.domain;

/**
 * 转移触发（Value Object / 枚举）。11 种触发。
 * 调用方判断用哪个触发，只需回答一个问题：请求离开本进程了吗？
 *  - 没有 → DEPENDENCY_UNAVAILABLE（BLOCKED，确定无副作用）
 *  - 离开了/说不清 → TIMEOUT（UNKNOWN，可能有副作用）
 */
public enum TransitionTrigger {
    DEPENDENCY_UNAVAILABLE,
    DISPATCH,
    ACK_SUCCESS,
    ACK_FAILURE,
    TIMEOUT,
    CRASH_RECOVERED,
    RECONCILE_SUCCESS,
    RECONCILE_FAILURE,
    RECONCILE_INCONCLUSIVE,
    ESCALATE,
    DEPENDENCY_RESTORED
}

