package com.trade.mall.agent.orchestration;

/**
 * 诊断流程状态（值对象/枚举）——`domain_model_and_invariants.md` §4 的 stateDiagram
 * 逐一对应，19 态，5 个终态。与 `execution.domain.ExecutionState`（D1，7 态 3 终态）
 * 是同一种建模纪律在不同层次的重复：状态本身只是数据，转移规则集中在
 * {@link DiagnosisTransitionPolicy} 一张表里，本枚举不携带任何转移逻辑。
 */
public enum DiagnosisState {
    RECEIVED(false),
    UNDERSTANDING(false),
    ANCHOR_MISSING(false),
    COLLECTING(false),
    EVIDENCE_INSUFFICIENT(false),
    REASONING(false),
    NO_CONCLUSION(false),
    CONCLUDED(false),
    PROPOSED(false),
    AWAITING_APPROVAL(false),
    EXECUTING(false),
    VERIFYING(false),
    NOT_RECOVERED(false),
    VERIFY_UNAVAILABLE(false),
    RESOLVED(true),
    CLOSED_NO_ACTION(true),
    REJECTED(true),
    EXPIRED(true),
    ESCALATED_HUMAN(true);

    private final boolean terminal;
    DiagnosisState(boolean terminal) { this.terminal = terminal; }
    public boolean isTerminal() { return terminal; }
}

