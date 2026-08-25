package com.trade.mall.agent.reasoning;

import com.trade.mall.agent.ledger.DomainEvent;

/**
 * 判定结果的业务语义 eventId 构造器——与 `understanding.TicketEvents`/`evidence.EvidenceEventIds`
 * 同一套幂等哲学。`aggregateId` 用 {@code diagnosisId}——这是 D5（用 `anchor`）、D6（用
 * `ticketSn`）两次"真正的 `Diagnosis` 聚合还不存在，先用手头已有的、最贴近的标识撑住账本
 * 边界"之后，D7 第一次真正用上 `diagnosisId` 本身：D6 的 `TicketUnderstandingService.understand()`
 * 已经把 `diagnosisId` 作为参数贯穿始终（用于 `LlmRegistry.pin()`），`reasoning`/`proposal`
 * 两个包沿用同一个 `diagnosisId` 作为账本聚合边界，是三次简化里最接近"真正设计"的一次——
 * 等 D8 `Diagnosis` 聚合出现时，这里不需要改，`diagnosisId` 本来就是它的身份。
 */
final class FindingEvents {
    private FindingEvents() {}

    static DomainEvent concluded(String diagnosisId, int seq, String findingType, String evidenceIdsJoined,
                                  double confidence, String modelVersion, String promptVersion, long now) {
        return new DomainEvent(eventId(diagnosisId, "FINDING", seq), diagnosisId, "Finding.Concluded",
            seq, findingType + " evidenceIds=[" + evidenceIdsJoined + "] confidence=" + confidence
                + " modelVersion=" + modelVersion + " promptVersion=" + promptVersion, now);
    }

    static DomainEvent noConclusion(String diagnosisId, int seq, String reason, String evidenceIdsJoined, long now) {
        return new DomainEvent(eventId(diagnosisId, "NO_CONCLUSION", seq), diagnosisId, "Finding.NoConclusion",
            seq, reason + " collectedEvidenceIds=[" + evidenceIdsJoined + "]", now);
    }

    private static String eventId(String diagnosisId, String suffix, int seq) {
        return diagnosisId + ":" + suffix + ":" + seq;
    }
}

