package com.trade.mall.agent.evidence;

/**
 * Evidence（证据）事件/引用 id 构造器。
 *
 * <p>diagnosisId（诊断编号）隔离不同诊断；round（取证轮次）隔离同一诊断在
 * NOT_RECOVERED（未恢复）之后重新取证产生的新事实。旧两参方法继续表示第 1 轮。</p>
 */
public final class EvidenceEventIds {
    private EvidenceEventIds() {}

    public static String collected(String diagnosisId, String sourceType) {
        return collected(diagnosisId, sourceType, 1);
    }

    public static String collected(String diagnosisId, String sourceType, int round) {
        return id(diagnosisId, sourceType, "COLLECTED", round);
    }

    public static String empty(String diagnosisId, String sourceType) {
        return empty(diagnosisId, sourceType, 1);
    }

    public static String empty(String diagnosisId, String sourceType, int round) {
        return id(diagnosisId, sourceType, "EMPTY", round);
    }

    public static String unavailable(String diagnosisId, String sourceType) {
        return unavailable(diagnosisId, sourceType, 1);
    }

    public static String unavailable(String diagnosisId, String sourceType, int round) {
        return id(diagnosisId, sourceType, "UNAVAILABLE", round);
    }

    public static String forState(String diagnosisId, String sourceType, AcquireState state) {
        return forState(diagnosisId, sourceType, state, 1);
    }

    public static String forState(String diagnosisId, String sourceType, AcquireState state, int round) {
        return switch (state) {
            case PRESENT -> collected(diagnosisId, sourceType, round);
            case EMPTY -> empty(diagnosisId, sourceType, round);
            case UNAVAILABLE -> unavailable(diagnosisId, sourceType, round);
        };
    }

    private static String id(String diagnosisId, String sourceType, String suffix, int round) {
        if (round < 1) throw new IllegalArgumentException("round must be >= 1");
        return diagnosisId + ":" + sourceType + ":" + suffix + ":" + round;
    }
}

