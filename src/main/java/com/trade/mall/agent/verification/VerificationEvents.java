package com.trade.mall.agent.verification;

import com.trade.mall.agent.ledger.DomainEvent;

/**
 * 验证的业务语义 eventId 构造器。`aggregateId` 用 `operationId`——与
 * `domain_events.md` §2.6 事件清单里 `Verification.Started` 的 payload 明确写着
 * `operationId` 一致（不是 `diagnosisId`）：验证针对的是"这一次具体的执行"，
 * 同一个诊断如果因为 `NOT_RECOVERED` 回到 `REASONING` 重新提出第二个提议、
 * 产生第二个 `operationId`，两次验证的账本记录必须能按各自的 `operationId`
 * 区分开，不能因为共用 `diagnosisId` 而混在一起。
 */
final class VerificationEvents {
    private VerificationEvents() {}

    static DomainEvent started(String operationId, int seq, String independentSourceType, long now) {
        return new DomainEvent(eventId(operationId, "STARTED", seq), operationId, "Verification.Started",
            seq, "independentSourceType=" + independentSourceType, now);
    }

    static DomainEvent recovered(String operationId, int seq, String independentSourceType, String description, long now) {
        return new DomainEvent(eventId(operationId, "RECOVERED", seq), operationId, "Verification.Recovered",
            seq, independentSourceType + ": " + description, now);
    }

    static DomainEvent notRecovered(String operationId, int seq, String independentSourceType, String description, long now) {
        return new DomainEvent(eventId(operationId, "NOT_RECOVERED", seq), operationId, "Verification.NotRecovered",
            seq, independentSourceType + ": " + description, now);
    }

    static DomainEvent unavailable(String operationId, int seq, String reason, long now) {
        return new DomainEvent(eventId(operationId, "UNAVAILABLE", seq), operationId, "Verification.Unavailable",
            seq, reason, now);
    }

    private static String eventId(String operationId, String suffix, int seq) {
        return operationId + ":VERIFY_" + suffix + ":" + seq;
    }
}

