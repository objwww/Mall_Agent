package com.trade.mall.agent.execution.application;

/**
 * 并发转移：版本已被别人改掉。语义是“暂时性冲突”，调用方可重读后再决策，
 * 与 IllegalTransitionException（调用方逻辑错误，不可重试）明确区分。
 */
public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String message) { super(message); }
}

