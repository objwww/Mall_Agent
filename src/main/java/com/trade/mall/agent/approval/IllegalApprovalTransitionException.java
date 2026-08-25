package com.trade.mall.agent.approval;

/** 终态不可变，或转移不在转移表中——同 execution 域的 IllegalTransitionException 同构。 */
public class IllegalApprovalTransitionException extends RuntimeException {
    public IllegalApprovalTransitionException(String message) { super(message); }
}

