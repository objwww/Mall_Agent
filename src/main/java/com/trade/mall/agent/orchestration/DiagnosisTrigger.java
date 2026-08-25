package com.trade.mall.agent.orchestration;

/**
 * 诊断流程转移触发（值对象/枚举）——`DiagnosisTransitionPolicy` 转移表的第二个维度。
 *
 * <p>{@link #ESCALATE_TO_HUMAN} 被四条不同来源状态复用（`ANCHOR_MISSING`/
 * `EVIDENCE_INSUFFICIENT`/`NO_CONCLUSION`/`VERIFY_UNAVAILABLE` 均可触发它）——这四种
 * 情形本质上是同一件事："流程走到这里，没有可继续走下去的自动化路径，必须转人工"，
 * 没有必要为它们各自发明一个语义相同的触发器。{@link #EXECUTION_ESCALATED} 特意
 * **不**并入 `ESCALATE_TO_HUMAN`：它专指"执行域自己的状态机已经到达 `ESCALATED`
 * 终态"（D3 崩溃恢复对账多次未果后的最终出口），来源单一、含义更具体，与"判定/证据/
 * 验证阶段没有自动化路径"是两类不同的失败，保留区分，账本上更容易归因。</p>
 */
public enum DiagnosisTrigger {
    START_UNDERSTANDING,
    /** 触发目标状态 {@code DiagnosisState.ANCHOR_MISSING}——名字加 _DETECTED 后缀避免与该状态同名（Java 静态导入下会二义）。 */
    ANCHOR_MISSING_DETECTED,
    ANCHOR_EXTRACTED,
    /** 触发目标状态 {@code DiagnosisState.EVIDENCE_INSUFFICIENT}，同上加后缀避免同名冲突。 */
    EVIDENCE_INSUFFICIENT_DETECTED,
    EVIDENCE_COMPLETE,
    /** 触发目标状态 {@code DiagnosisState.NO_CONCLUSION}，同上加后缀避免同名冲突。 */
    NO_CONCLUSION_REACHED,
    FINDING_CONCLUDED,
    PROPOSAL_CREATED,
    NO_ACTION_NEEDED,
    REQUIRES_APPROVAL,
    NO_APPROVAL_NEEDED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,
    APPROVAL_EXPIRED,
    EXECUTION_TERMINAL,
    EXECUTION_ESCALATED,
    VERIFY_RECOVERED,
    VERIFY_NOT_RECOVERED,
    /** 触发目标状态 {@code DiagnosisState.VERIFY_UNAVAILABLE}，同上加后缀避免同名冲突。 */
    VERIFY_SOURCE_UNAVAILABLE,
    REOPEN_REASONING,
    ESCALATE_TO_HUMAN
}

