package com.trade.mall.agent.evidence;

import java.io.Serial;

/**
 * Evidence —— 证据域（BC-3）聚合 {@link EvidenceBundle} 内的实体。
 *
 * <p>V7 起真实采集路径把 evidenceId / observedAt / acquiredAt 一起放进证据本身：
 * evidenceId 是下游 Finding 真正引用的稳定身份；observedAt 是数据源事实被成功观察到的时刻，
 * acquiredAt 是本地把这次观察包装成证据的时刻。UNAVAILABLE 没有观察到业务事实，所以
 * observedAt 为 null，而 acquiredAt 仍记录“确认该来源不可用”的时间。</p>
 *
 * <p>保留旧静态工厂只为 D1-D8 的手工测试/样例兼容；真实 {@code EvidenceCollectionService}
 * 一律使用带 evidenceId 和时间的工厂。下游如果遇到旧样例中的空 evidenceId，会由
 * {@code EvidenceBundle} 的 diagnosis scope（诊断作用域）推导兼容 id。</p>
 */
public record Evidence(
        String evidenceId,
        String sourceType,
        SourceLocator sourceLocator,
        AcquireState acquireState,
        ConfidenceLevel confidence,
        EvidencePayload payload,
        String unavailableReason,
        Long observedAtEpochMillis,
        long acquiredAtEpochMillis
) implements java.io.Serializable {
    @Serial
    private static final long serialVersionUID = 0L;

    public Evidence {
        if (sourceType == null || sourceType.isBlank()) throw new IllegalArgumentException("sourceType must not be blank");
        if (sourceLocator == null) throw new IllegalArgumentException("sourceLocator must not be null");
        if (acquireState == null) throw new IllegalArgumentException("acquireState must not be null");
        if (confidence == null) throw new IllegalArgumentException("confidence must not be null");
        if (acquireState == AcquireState.PRESENT && payload == null) {
            throw new IllegalArgumentException("payload must not be null for PRESENT");
        }
        if (acquireState != AcquireState.PRESENT && payload != null) {
            throw new IllegalArgumentException("payload is only allowed for PRESENT");
        }
        if (acquireState == AcquireState.UNAVAILABLE && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException("reason must not be blank for UNAVAILABLE");
        }
        if (acquireState != AcquireState.UNAVAILABLE && unavailableReason != null) {
            throw new IllegalArgumentException("unavailableReason is only allowed for UNAVAILABLE");
        }
        if (acquireState == AcquireState.UNAVAILABLE && observedAtEpochMillis != null) {
            throw new IllegalArgumentException("UNAVAILABLE must not claim a business fact observation time");
        }
    }

    public static Evidence present(String evidenceId, String sourceType, SourceLocator locator,
                                   ConfidenceLevel confidence, EvidencePayload payload,
                                   long observedAtEpochMillis, long acquiredAtEpochMillis) {
        if (evidenceId == null || evidenceId.isBlank()) throw new IllegalArgumentException("evidenceId must not be blank");
        return new Evidence(evidenceId, sourceType, locator, AcquireState.PRESENT, confidence, payload, null,
            observedAtEpochMillis, acquiredAtEpochMillis);
    }

    public static Evidence empty(String evidenceId, String sourceType, SourceLocator locator,
                                 ConfidenceLevel confidence, long observedAtEpochMillis, long acquiredAtEpochMillis) {
        if (evidenceId == null || evidenceId.isBlank()) throw new IllegalArgumentException("evidenceId must not be blank");
        return new Evidence(evidenceId, sourceType, locator, AcquireState.EMPTY, confidence, null, null,
            observedAtEpochMillis, acquiredAtEpochMillis);
    }

    public static Evidence unavailable(String evidenceId, String sourceType, SourceLocator locator,
                                       ConfidenceLevel confidence, String reason, long acquiredAtEpochMillis) {
        if (evidenceId == null || evidenceId.isBlank()) throw new IllegalArgumentException("evidenceId must not be blank");
        return new Evidence(evidenceId, sourceType, locator, AcquireState.UNAVAILABLE, confidence, null, reason,
            null, acquiredAtEpochMillis);
    }

    /** D1-D8 手工样例兼容入口；真实采集路径不使用。 */
    public static Evidence present(String sourceType, SourceLocator locator, ConfidenceLevel confidence, EvidencePayload payload) {
        return new Evidence(null, sourceType, locator, AcquireState.PRESENT, confidence, payload, null, null, 0L);
    }

    /** D1-D8 手工样例兼容入口；真实采集路径不使用。 */
    public static Evidence empty(String sourceType, SourceLocator locator, ConfidenceLevel confidence) {
        return new Evidence(null, sourceType, locator, AcquireState.EMPTY, confidence, null, null, null, 0L);
    }

    /** D1-D8 手工样例兼容入口；真实采集路径不使用。 */
    public static Evidence unavailable(String sourceType, SourceLocator locator, ConfidenceLevel confidence, String reason) {
        return new Evidence(null, sourceType, locator, AcquireState.UNAVAILABLE, confidence, null, reason, null, 0L);
    }
}

