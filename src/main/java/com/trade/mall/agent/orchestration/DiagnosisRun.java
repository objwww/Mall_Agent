package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.proposal.Proposal;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.understanding.Anchor;
import com.trade.mall.agent.verification.VerifyResult;

/**
 * 一次诊断运行到目前为止的完整快照——不是领域实体（没有构造期不变量校验），是
 * {@link DiagnosisOrchestrator} 每一步的返回值/断点，供调用方（测试、未来的 D9
 * `M-API-03` 查询接口）读取当前进展或把一次暂停在 `AWAITING_APPROVAL` 的运行接回去
 * （见 {@link #resumeAfterApproval}）。
 *
 * <p>字段大多可空——这是设计使然，不是疏漏：一次在 `ANCHOR_MISSING` 就终止的诊断，
 * 从未产出 {@code evidenceBundle}/{@code finding}/{@code proposal}，字段停留在
 * {@code null} 恰好如实反映"流程没走到那一步"，比每个字段都包一层 {@code Optional}
 * 更直接——调用方本来就该先看 {@code state()}，再决定该读哪些字段。</p>
 */
public record DiagnosisRun(
        String ticketSn,
        String diagnosisId,
        DiagnosisState state,
        int seq,
        Anchor anchor,
        EvidenceBundle evidenceBundle,
        FindingResult finding,
        Proposal proposal,
        String approvalId,
        VerifyResult verifyResult
) implements java.io.Serializable {
    public boolean isPausedAtApproval() { return state == DiagnosisState.AWAITING_APPROVAL; }
    public boolean isTerminal() { return state.isTerminal(); }
}

