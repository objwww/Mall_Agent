package com.trade.mall.agent.orchestration;

/** 非资金动作的明确业务失败；只有这类结果才允许把耐久状态落成 FAILED。 */
public final class NonFundActionBusinessFailureException extends RuntimeException {
    public NonFundActionBusinessFailureException(String message) { super(message); }
    public NonFundActionBusinessFailureException(String message, Throwable cause) { super(message, cause); }
}

