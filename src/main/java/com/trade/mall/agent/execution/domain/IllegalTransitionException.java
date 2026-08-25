package com.trade.mall.agent.execution.domain;

/**
 * 非法转移：转移不在转移表中，或对终态施加转移。
 * 语义是“调用方逻辑错误”，不可重试——与 OptimisticLockException 区别开，
 * 使调用方无法笼统地 catch(Exception){ retry(); }。
 */
public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(String message) { super(message); }
}

