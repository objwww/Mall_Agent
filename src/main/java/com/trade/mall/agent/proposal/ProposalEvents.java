package com.trade.mall.agent.proposal;

import com.trade.mall.agent.ledger.DomainEvent;

/**
 * 处置提议的业务语义 eventId 构造器——与 `reasoning.FindingEvents` 同一套幂等哲学，
 * `aggregateId` 同样用 {@code diagnosisId}。
 */
final class ProposalEvents {
    private ProposalEvents() {}

    static DomainEvent created(String diagnosisId, int seq, String actionType, String paramsHash,
                                String basedOnFindingId, String independentSourceType, long now) {
        return new DomainEvent(diagnosisId + ":PROPOSAL:" + seq, diagnosisId, "Proposal.Created",
            seq, actionType + " paramsHash=" + paramsHash + " basedOnFindingId=" + basedOnFindingId
                + " independentSourceType=" + independentSourceType, now);
    }
}

