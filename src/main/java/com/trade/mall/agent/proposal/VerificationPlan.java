package com.trade.mall.agent.proposal;

/**
 * 验证方案（值对象）。
 *
 * @param independentSourceType 独立事实来源，例如 REFUND_LOG（退款日志）
 * @param description 人可读的验证说明
 * @param correlationKey 需要验证的具体业务对象，例如 refundSn（退款单号）；可空表示旧调用未提供
 * @param baselineSequence 执行动作前已经观察到的最大日志序号；验证只接受更晚的新事实
 */
public record VerificationPlan(
        String independentSourceType,
        String description,
        String correlationKey,
        long baselineSequence
) implements java.io.Serializable {
    public VerificationPlan(String independentSourceType, String description) {
        this(independentSourceType, description, null, -1L);
    }

    public VerificationPlan {
        if (independentSourceType == null || independentSourceType.isBlank()) {
            throw new IllegalArgumentException("independentSourceType must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (baselineSequence < -1L) {
            throw new IllegalArgumentException("baselineSequence must be >= -1");
        }
    }

    public boolean hasCorrelation() {
        return correlationKey != null && !correlationKey.isBlank();
    }
}

