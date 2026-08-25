package com.trade.mall.agent.evidence;

import java.io.Serial;
import java.util.List;

/**
 * EvidenceBundle —— 一次 Diagnosis（诊断）某一轮围绕业务锚点采集到的完整证据快照。
 * diagnosisId 隔离不同诊断，round 隔离同一诊断的重新取证轮次。
 */
public final class EvidenceBundle implements java.io.Serializable {
    @Serial
    private static final long serialVersionUID = 7530059105691267094L; // 保持 V6/V7 checkpoint 可读取

    private final String diagnosisId;
    private final String anchor;
    private final List<Evidence> items;
    private final int round;

    private EvidenceBundle(String diagnosisId, String anchor, List<Evidence> items, int round) {
        this.diagnosisId = diagnosisId;
        this.anchor = anchor;
        this.items = List.copyOf(items);
        this.round = round;
    }

    public static EvidenceBundle of(String diagnosisId, String anchor, List<Evidence> items) {
        return of(diagnosisId, anchor, items, 1);
    }

    public static EvidenceBundle of(String diagnosisId, String anchor, List<Evidence> items, int round) {
        if (diagnosisId == null || diagnosisId.isBlank()) throw new IllegalArgumentException("diagnosisId must not be blank");
        if (anchor == null || anchor.isBlank()) throw new IllegalArgumentException("anchor must not be blank");
        if (round < 1) throw new IllegalArgumentException("round must be >= 1");
        return new EvidenceBundle(diagnosisId, anchor, items, round);
    }

    /** D1-D8 手工样例兼容：旧代码没有 diagnosisId 时暂以 anchor 作为 scope。 */
    public static EvidenceBundle of(String anchor, List<Evidence> items) {
        return of(anchor, anchor, items, 1);
    }

    public String diagnosisId() { return diagnosisId == null || diagnosisId.isBlank() ? anchor : diagnosisId; }
    public String anchor() { return anchor; }
    public List<Evidence> items() { return items; }
    /** 旧 checkpoint 没有 round 字段时 Java 反序列化得到 0，按第 1 轮兼容。 */
    public int round() { return round < 1 ? 1 : round; }

    public String evidenceId(Evidence evidence) {
        if (evidence.evidenceId() != null && !evidence.evidenceId().isBlank()) return evidence.evidenceId();
        return EvidenceEventIds.forState(diagnosisId(), evidence.sourceType(), evidence.acquireState(), round());
    }

    public List<Evidence> withState(AcquireState state) {
        return items.stream().filter(e -> e.acquireState() == state).toList();
    }

    public boolean hasAnyUnavailable() {
        return items.stream().anyMatch(e -> e.acquireState() == AcquireState.UNAVAILABLE);
    }
}

