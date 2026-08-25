package com.trade.mall.agent.understanding;

/**
 * 锚点类型（值对象/枚举）——与既有 `support_ticket_context.ref_type`
 * 的既定七值词表完全一致（`FACT-EVID-002`），不是本项目自己另起的分类。
 *
 * <p>D6 范围内只真正验证 `ORDER`/`REFUND`/`TRACE` 三种（与 D5 的四个证据采集器、
 * `FACT-EVID-003` 的全链路 traceId 呼应）——`OPERATION`/`AFTER_SALE`/`AGENT_RUN`/
 * `CASE_RUN` 先把词表定完整，避免下游存储/查询代码在未来接入时还要回头改这个枚举，
 * 但目前没有对应的取证/理解逻辑在产出它们，见 `D6-REPORT.md` §4"未做"。</p>
 */
public enum AnchorType {
    ORDER,
    REFUND,
    TRACE,
    OPERATION,
    AFTER_SALE,
    AGENT_RUN,
    CASE_RUN
}

