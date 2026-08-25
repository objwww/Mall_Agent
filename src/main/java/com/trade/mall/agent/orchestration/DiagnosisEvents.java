package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.ledger.DomainEvent;

/**
 * 诊断流程状态变化的业务语义 eventId 构造器——与 `reasoning.FindingEvents`/
 * `proposal.ProposalEvents` 同一套幂等哲学，`aggregateId` 同样是 `diagnosisId`。
 *
 * <p>`Diagnosis.StateChanged` 不是 `domain_events.md` §2 事件清单里写出来的事件——
 * 该文档定义的是各个能力域自己的事件（`Ticket.*`/`Evidence.*`/`Finding.*`/
 * `Proposal.*`/`Approval.*`/`Attempt.*`/`Verification.*`），"诊断流程状态"本身
 * 只在 M-ORCH-01 12 维度卡"09 数据/接口"一栏被提到（"诊断流程状态 + Ticket.* 事件"），
 * 没有给出具体的事件名。`DiagnosisOrchestrator` 每次调用 {@link
 * com.trade.mall.agent.orchestration.DiagnosisTransitionPolicy#apply} 之后
 * 额外写一条 `Diagnosis.StateChanged`，是 D8 在文档留白处做出的补充设计——好处是
 * "崩溃后这次诊断走到哪一步了"从此有一份显式、独立于任何单个能力域事件的记录，
 * 不需要靠"扫描七个不同事件类型、猜哪个最后发生"来倒推诊断阶段。</p>
 */
final class DiagnosisEvents {
    private DiagnosisEvents() {}

    static DomainEvent stateChanged(String diagnosisId, int seq, String trigger, String toState, long now) {
        return new DomainEvent(diagnosisId + ":STATE:" + seq, diagnosisId, "Diagnosis.StateChanged",
            seq, trigger + " -> " + toState, now);
    }
}

