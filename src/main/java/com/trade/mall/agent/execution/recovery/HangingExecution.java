package com.trade.mall.agent.execution.recovery;

import com.trade.mall.agent.execution.domain.ExecutionState;

/**
 * 一条悬挂候选：state 是"发现时"的状态快照（DISPATCHED 或 UNKNOWN），
 * seqNo 是那次未落地终态的尝试序号（用于 CRASH_RECOVERED 转移的 TransitionContext）。
 */
public record HangingExecution(String operationId, ExecutionState state, int seqNo) {}

