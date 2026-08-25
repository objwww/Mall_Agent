package com.trade.mall.agent.evidence;

/**
 * 证据的置信等级（值对象/枚举）——复用既有 `support_ticket_context.source` 的三档设计
 * （`FACT-EVID-002`：`VERIFIED`/`AUTO`/`MANUAL`），不是 D5 另起炉灶发明的新概念。
 *
 * <p>D5 范围内的四个采集器（`OrderEvidenceCollector` 等）全部直接查 mall 只读库，
 * 不经过任何推断或人工录入，因此**恒定产出 {@link #VERIFIED}**——`AUTO`（LLM/规则推断）
 * 与 `MANUAL`（人工标注）这两档是留给 D6 起的判定/理解层使用的扩展位，D5 不产出它们，
 * 但类型上先把三档定下来，避免下游代码在 D6 接入时还要回头改这个枚举。</p>
 */
public enum ConfidenceLevel {
    VERIFIED,
    AUTO,
    MANUAL
}

