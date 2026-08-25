package com.trade.mall.agent.orchestration;

/** 非资金动作结果不确定/依赖不可用；耐久记录必须保持 PENDING，以便利用动作幂等性安全重放。 */
public final class NonFundActionUnavailableException extends RuntimeException {
    public NonFundActionUnavailableException(String message) { super(message); }
    public NonFundActionUnavailableException(String message, Throwable cause) { super(message, cause); }
}

