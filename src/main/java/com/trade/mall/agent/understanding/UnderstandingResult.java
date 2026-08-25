package com.trade.mall.agent.understanding;

import com.trade.mall.agent.llm.VersionSnapshot;

import java.util.List;

/**
 * 一次 {@code TicketUnderstandingService.understand()} 调用的结局——sealed 三态。
 *
 * <p>{@link AnchorMissing} 与异常的区别是 D6 最重要的一条设计决定（`domain_events.md`
 * §2.1、`implementation_plan.md` D6 验收标准原文）：**提不出锚点是一个合法的正确输出**
 * （`NG-002`"无法判定是合法输出"），不是"理解失败"——工单本来就可能写得语焉不详，
 * LLM 老老实实说"我看不出这是哪个订单"，是诚实，不是缺陷。{@link Escalated} 才是
 * 真正意义上的"这次没能把工单变成任何可用的结构化产出"（LLM 输出连续多次不满足
 * schema），需要转人工。</p>
 */
public sealed interface UnderstandingResult
    permits UnderstandingResult.Understood, UnderstandingResult.AnchorMissing, UnderstandingResult.Escalated {

    /** 成功提取锚点与症状。 */
    record Understood(Anchor anchor, List<Symptom> symptoms, double confidence, VersionSnapshot versionSnapshot)
        implements UnderstandingResult {
        public Understood {
            symptoms = List.copyOf(symptoms);
        }
    }

    /** 合法输出：工单文本本身不足以提取出锚点。 */
    record AnchorMissing(String reason, VersionSnapshot versionSnapshot) implements UnderstandingResult {}

    /** LLM 输出连续多次不满足结构化 schema，重试耗尽，转人工。 */
    record Escalated(String reason, int attempts, VersionSnapshot versionSnapshot) implements UnderstandingResult {}
}

