package com.trade.mall.agent.approval;

/**
 * 批准状态（值对象/枚举）。五态：`PENDING`（待决）/`GRANTED`（已批准未消费）/
 * `REJECTED`/`CONSUMED`/`EXPIRED`（三个终态）。
 */
public enum ApprovalState {
    PENDING(false),
    GRANTED(false),
    REJECTED(true),
    CONSUMED(true),
    EXPIRED(true);

    private final boolean terminal;
    ApprovalState(boolean terminal) { this.terminal = terminal; }
    public boolean isTerminal() { return terminal; }
}

