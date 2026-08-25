package com.trade.mall.agent.understanding;

import com.trade.mall.agent.ledger.DomainEvent;

/**
 * 工单理解结果的业务语义 eventId 构造器——`domain_events.md` §2.1 原文的
 * `Ticket.AnchorExtracted`/`Ticket.AnchorMissing`/`Ticket.Escalated` 三个事件，
 * eventId 构造沿用文档给出的确切格式。D6 只落地这三个（理解层产出的结局），
 * `Ticket.Received`/`Ticket.Closed` 属于更完整的工单生命周期管理，D6 范围之外
 * （见 `D6-REPORT.md` §4）。
 */
final class TicketEvents {
    private TicketEvents() {}

    static DomainEvent anchorExtracted(String ticketSn, String diagnosisId, int seq, String anchorType, String anchorValue, double confidence, long now) {
        return new DomainEvent(diagnosisId + ":TICKET_ANCHOR:" + seq, diagnosisId, "Ticket.AnchorExtracted",
            seq, "ticketSn=" + ticketSn + " " + anchorType + "=" + anchorValue + " confidence=" + confidence, now);
    }

    static DomainEvent anchorMissing(String ticketSn, String diagnosisId, String reason, long now) {
        return new DomainEvent(diagnosisId + ":TICKET_ANCHOR_MISSING:1", diagnosisId, "Ticket.AnchorMissing",
            1, "ticketSn=" + ticketSn + " " + reason, now);
    }

    static DomainEvent escalated(String ticketSn, String diagnosisId, int stage, String escalationReason, long now) {
        return new DomainEvent(diagnosisId + ":TICKET_ESCALATED:" + stage, diagnosisId, "Ticket.Escalated",
            stage, "ticketSn=" + ticketSn + " " + escalationReason, now);
    }
}

