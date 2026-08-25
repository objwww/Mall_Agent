package com.trade.mall.agent.proposal;

import java.util.Map;

/**
 * Proposal —— `M-CAP-05` 的产出物：结构化处置提议。诊断内是 `Diagnosis` 聚合内的实体，
 * 跨上下文（到未来的执行/批准）时是已发布语言（`ddd_design.md` §4）——D7 范围内只交付
 * "诊断内实体"这一半，跨上下文的稳定契约留给 D8 编排层真正接入批准/执行时验证。
 *
 * <p>构造器强制两条不变量，both 在**提议这一刻**、不是在批准/执行时才校验：</p>
 * <ol>
 *   <li>{@code paramsHash} 必须等于对 {@code params} 重新计算的哈希——防止调用方手滑传入
 *       一个和参数对不上的哈希字符串（这条不变量本来要到 D8 `ApprovalGate.consume()`
 *       才会被比对，D7 提前在源头就守住，比等到批准/执行时才发现"提议本身就是错的"更早
 *       止损）。</li>
 *   <li>{@code verificationPlan.independentSourceType()} 不得与 {@code actionType.sourceType()}
 *       相同（`INV-VERIFY-001`）——这条不变量本来是 D8 `M-CAP-06 RecoveryVerifier` 的
 *       入口校验，D7 提前在 `Proposal` 构造这一层就守住，是因为"验证方案与动作同源"
 *       是一个**结构性错误**，没有理由等到验证真正发生的那一刻才发现——提议阶段就该
 *       是一个不可能构造出这种错误提议的类型。</li>
 * </ol>
 */
public record Proposal(
        String proposalId,
        ActionType actionType,
        Map<String, String> params,
        String paramsHash,
        String basedOnFindingId,
        VerificationPlan verificationPlan
) implements java.io.Serializable {
    public Proposal {
        if (proposalId == null || proposalId.isBlank()) throw new IllegalArgumentException("proposalId must not be blank");
        if (actionType == null) throw new IllegalArgumentException("actionType must not be null");
        if (params == null || params.isEmpty()) throw new IllegalArgumentException("params must not be empty");
        if (paramsHash == null || paramsHash.isBlank()) throw new IllegalArgumentException("paramsHash must not be blank");
        if (basedOnFindingId == null || basedOnFindingId.isBlank()) throw new IllegalArgumentException("basedOnFindingId must not be blank");
        if (verificationPlan == null) throw new IllegalArgumentException("verificationPlan must not be null");

        params = Map.copyOf(params);

        String recomputed = ParamsHashing.sha256(params);
        if (!recomputed.equals(paramsHash)) {
            throw new IllegalArgumentException(
                "paramsHash 与 params 不匹配：given=" + paramsHash + " recomputed=" + recomputed);
        }

        if (verificationPlan.independentSourceType().equals(actionType.sourceType())) {
            throw new IllegalArgumentException(
                "验证方案的 independentSourceType 与动作来源相同（" + actionType.sourceType()
                    + "）——这是自我确认，不是独立验证（INV-VERIFY-001）");
        }
    }

    /**
     * 本提议对应的业务幂等操作编号。退款动作优先复用 VerificationPlan（验证方案）里已经
     * 固定的 refundSn（退款单号）；其他尚未提供业务幂等键的动作退回 proposalId（提议编号）。
     */
    public String operationId() {
        return verificationPlan.hasCorrelation() ? verificationPlan.correlationKey() : proposalId;
    }
}

