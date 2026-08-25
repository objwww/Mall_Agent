package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

/**
 * EvidenceResult —— `M-CAP-02` 采集器方法的**唯一**合法返回类型（`ARCH-EVID-001`）。
 *
 * <p>sealed，三态且只有三态，与 {@link com.trade.mall.agent.evidence.AcquireState} 一一对应，
 * 强迫调用方在 switch/instanceof 里穷举处理——这与 {@code execution.port.PortOutcome}
 * 是同一个设计动作在证据域的重演：**用类型系统而不是运行时约定去挡住"裸集合"这个坑**。</p>
 *
 * <p>验收标准原文"证据采集方法返回类型非 Collection"说的正是这个类型本身：
 * {@code EvidenceCollector.collect()} 的返回类型是 {@code EvidenceResult<T>}，
 * 不是 {@code List<T>}——即使 {@link Present} 装的 payload 内部恰好是一个列表
 * （见 {@code RefundLogEvidenceCollector}，一次查询本就该返回多条日志），
 * **外层方法签名**依然是 {@code EvidenceResult}，调用方在拿到载荷之前必须先经过
 * 三态判断，不可能对着一个"看起来只是查询结果"的返回值直接 {@code .isEmpty()}
 * 就把"查不到"和"查失败"混为一谈。</p>
 */
public sealed interface EvidenceResult<T extends EvidencePayload>
    permits EvidenceResult.Present, EvidenceResult.Empty, EvidenceResult.Unavailable {

    /** 查到了。 */
    record Present<T extends EvidencePayload>(T value) implements EvidenceResult<T> {
        public Present {
            if (value == null) throw new IllegalArgumentException("value must not be null for Present");
        }
    }

    /** 确认没有——查询成功执行，结果集就是空的。 */
    record Empty<T extends EvidencePayload>() implements EvidenceResult<T> {}

    /** 能力不可用——数据源连不上，或查询本身没能问出去。 */
    record Unavailable<T extends EvidencePayload>(String reason) implements EvidenceResult<T> {
        public Unavailable {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank for Unavailable");
        }
    }
}

