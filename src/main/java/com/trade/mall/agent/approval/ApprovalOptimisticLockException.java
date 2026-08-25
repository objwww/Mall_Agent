package com.trade.mall.agent.approval;

/** 并发转移：版本已被别人改掉——同 execution 域 OptimisticLockException 同构（D1）。 */
public class ApprovalOptimisticLockException extends RuntimeException {
    public ApprovalOptimisticLockException(String message) { super(message); }
}

