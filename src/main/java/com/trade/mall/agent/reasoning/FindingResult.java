package com.trade.mall.agent.reasoning;

import com.trade.mall.agent.llm.VersionSnapshot;

import java.util.List;

/**
 * 一次 {@code ReasoningService.reason()} 调用的结局——sealed 两态，`domain_events.md` §2.3
 * 原文的 `Finding.Concluded`/`Finding.NoConclusion` 直接对应。
 *
 * <p>与 D6 {@code UnderstandingResult}（三态：Understood/AnchorMissing/Escalated）刻意
 * 不同——这里没有第三个 "Escalated" 变体。原因见 {@code ReasoningService} 类头 §设计说明：
 * 判定层的"给不出结论"只有一种合法结局，`NG-002` 原文就是"无法判定是合法输出"，
 * 没有区分"LLM 主动说证据不够"和"重试多次都不满足要求所以放弃"——两者对下游编排层
 * 而言是同一件事：这次诊断在判定这一步没能产出可用的结论，不能强行拼一个。文档里
 * 也没有定义过 `Finding.Escalated` 这个事件，硬造一个未落在 `domain_events.md` 事件清单
 * 里的类型，比把两种"没结论"的原因都写进同一个 `NoConclusion.reason()` 字段更不诚实。</p>
 */
public sealed interface FindingResult extends java.io.Serializable permits FindingResult.Concluded, FindingResult.NoConclusion {

    /**
     * 判定成功——必须引用真实存在的证据（`INV-EVID-002`，由 {@code ReasoningService}
     * 在构造前校验，不是这个 record 自己校验，因为"这个 evidenceId 是否真实存在"
     * 依赖当次诊断的 {@code EvidenceBundle}，不是 `Finding` 自身能回答的问题）。
     */
    record Concluded(
            String findingId,
            FindingType findingType,
            List<String> evidenceIds,
            double confidence,
            VersionSnapshot versionSnapshot
    ) implements FindingResult {
        public Concluded {
            if (findingId == null || findingId.isBlank()) throw new IllegalArgumentException("findingId must not be blank");
            if (findingType == null) throw new IllegalArgumentException("findingType must not be null");
            if (evidenceIds == null || evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("Concluded 必须至少引用一条证据（INV-EVID-002）");
            }
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    /** 证据不足以支持任何判定，或判定过程多次未能产出合法结论——合法输出，不是错误（`NG-002`）。 */
    record NoConclusion(String reason, List<String> collectedEvidenceIds, VersionSnapshot versionSnapshot)
        implements FindingResult {
        public NoConclusion {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            collectedEvidenceIds = List.copyOf(collectedEvidenceIds == null ? List.of() : collectedEvidenceIds);
        }
    }
}

