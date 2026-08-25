package com.trade.mall.agent.verification;

/**
 * 一次 {@code RecoveryVerifier.verify()} 调用的结局——sealed 三态，`domain_events.md` §2.6
 * 与 `INV-VERIFY-002` 原文直接对应。
 *
 * <p>{@link #VerifyUnavailable} 与 {@link #NotRecovered} 是两件完全不同的事，
 * 这条区分是本类存在的唯一理由（`G-004`）：监控/独立事实源本身连不上，不等于
 * "问题真的没解决"。二者一旦被合并，一次监控故障会被放大成"所有故障都判定未恢复"，
 * 进而触发大量无意义的重新诊断——`domain_model_and_invariants.md` §4.1 把这列为
 * "三个容易做错的分支"之一。</p>
 */
public sealed interface VerifyResult extends java.io.Serializable permits VerifyResult.Recovered, VerifyResult.NotRecovered, VerifyResult.VerifyUnavailable {

    /** 独立事实确认已恢复。 */
    record Recovered(String independentSourceType, String description) implements VerifyResult {}

    /** 独立事实确认未恢复——不是"验证失败"，是"验证成功地告诉你问题还在"。 */
    record NotRecovered(String independentSourceType, String description) implements VerifyResult {}

    /** 独立事实源不可用（不可用本身，或没有为这个来源类型注册任何适配器）——绝不等价于 NotRecovered。 */
    record VerifyUnavailable(String reason) implements VerifyResult {}
}

