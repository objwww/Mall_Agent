package com.trade.mall.agent.llm;

import com.trade.mall.agent.ledger.DomainEvent;

/**
 * 热更新事件的业务语义 eventId 构造器——与 `approval.ApprovalEventIds`/
 * `evidence.EvidenceEventIds` 同一套幂等哲学。
 *
 * <p>`M-LLM-01-llm-registry.md` §6 原文用 `llm:{configVersion}:SWITCH_REQ`——
 * {@code configVersion} 来自 Nacos 配置版本号，D6 没有真实 Nacos，用
 * {@link DefaultLlmRegistry} 内部维护的单调递增 {@code switchSeq}（每次
 * {@code switchTo()} 调用自增一次，不论结局是 Switched/Aborted/NoOp）代替——
 * 序号来源不同，但"同一次切换尝试产出的多条事件共享同一个序号前缀"这条设计意图
 * 完全保留：`SwitchRequested(seq=3)` 和 `Switched(seq=3)`/`Aborted(seq=3)` 永远配对，
 * 读账本的人能一眼看出哪几条事件属于同一次切换尝试。</p>
 */
public final class LlmEvents {
    private LlmEvents() {}

    private static final String AGGREGATE_ID = "llm-registry";

    public static DomainEvent switchRequested(long switchSeq, String from, String to, long now) {
        return new DomainEvent(eventId(switchSeq, "SWITCH_REQ"), AGGREGATE_ID, "Llm.SwitchRequested",
            (int) switchSeq, from + "->" + to, now);
    }

    public static DomainEvent healthCheckPassed(long switchSeq, String to, long costMs, long now) {
        return new DomainEvent(eventId(switchSeq, "HC_OK"), AGGREGATE_ID, "Llm.HealthCheckPassed",
            (int) switchSeq, to + ":" + costMs + "ms", now);
    }

    public static DomainEvent healthCheckFailed(long switchSeq, String to, long costMs, long now) {
        return new DomainEvent(eventId(switchSeq, "HC_FAIL"), AGGREGATE_ID, "Llm.HealthCheckFailed",
            (int) switchSeq, to + ":" + costMs + "ms", now);
    }

    public static DomainEvent switched(long switchSeq, String from, String to, long healthCheckMs, long now) {
        return new DomainEvent(eventId(switchSeq, "SWITCHED"), AGGREGATE_ID, "Llm.Switched",
            (int) switchSeq, from + "->" + to + " (" + healthCheckMs + "ms)", now);
    }

    public static DomainEvent switchAborted(long switchSeq, String from, String attempted, String reason, long now) {
        return new DomainEvent(eventId(switchSeq, "ABORTED"), AGGREGATE_ID, "Llm.SwitchAborted",
            (int) switchSeq, from + "->" + attempted + " reason=" + reason, now);
    }

    private static String eventId(long switchSeq, String suffix) {
        return "llm:" + switchSeq + ":" + suffix;
    }
}

