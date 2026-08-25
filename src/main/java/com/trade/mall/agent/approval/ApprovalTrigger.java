package com.trade.mall.agent.approval;

/** 批准转移触发（值对象/枚举）。四种，语义上分别对应人的决定、消费、系统超时。 */
public enum ApprovalTrigger {
    GRANT,
    REJECT,
    CONSUME,
    EXPIRE
}

