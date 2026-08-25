package com.trade.mall.agent.evidence;

/**
 * 证据载荷的标记接口——每个只读适配器（`M-ADP-01`）的行投影类型
 * （`OrderRecord`/`RefundRecord`/`AfterSaleRecord`/`RefundLogBundle`，见 `evidence.port`）
 * 都实现它，使 {@code EvidenceCollector<T extends EvidencePayload>} 能用一个统一的上界泛型
 * 约束所有采集器，而不必对每种证据类型各写一套采集服务。
 *
 * <p>刻意不用 sealed：这里不是在建模一个需要穷举 switch 的封闭状态集合
 * （那是 {@link AcquireState}/{@code EvidenceResult} 该做的事），只是给"这是一条可以
 * 装进 {@link Evidence#payload()} 的东西"这件事一个类型标记，新增证据源时只需要新增
 * 一个实现类，不需要改这个接口。</p>
 */
public interface EvidencePayload extends java.io.Serializable {
}

