package com.trade.mall.agent.execution.domain;

/**
 * 执行状态（Value Object / 枚举）。七态，含 UNKNOWN 与 BLOCKED。
 * 见 domain_model_and_invariants.md §3.1。
 * terminal=true 的三个态一旦进入不可变（INV-EXEC-003）。
 */
public enum ExecutionState {
    PENDING(false),
    DISPATCHED(false),
    UNKNOWN(false),
    BLOCKED(false),
    SUCCEEDED(true),
    FAILED(true),
    ESCALATED(true);

    private final boolean terminal;
    ExecutionState(boolean terminal) { this.terminal = terminal; }
    public boolean isTerminal() { return terminal; }
}

