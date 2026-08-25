package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.EvidencePayload;
import com.trade.mall.agent.evidence.port.EvidenceResult;

/**
 * `M-CAP-02`：证据采集器的统一契约。**唯一**允许的返回类型是 {@link EvidenceResult}
 * （`ARCH-EVID-001`）——`SelfCheck` 里有一条源码扫描（§49）专门盯着这一条，防止未来
 * 有人在这个包下面新增一个"图省事直接返回 List"的采集器。
 *
 * <p>{@code sourceType()} 是这条证据在 {@code Evidence.sourceType()}/事件账本里的身份标签
 * （如 `"ORDER"`/`"REFUND"`），与具体的 `oms_*` 表名一一对应但不相同——留一层间接是因为
 * 同一张表将来可能被不止一个采集器以不同角度查询（比如 D9 按 traceId 查 `oms_order_refund`
 * 又是另一个 sourceType），`sourceType` 描述的是"这条证据在诊断里扮演什么角色"，
 * 不是"这行数据物理上存在哪张表"。</p>
 */
public interface EvidenceCollector<T extends EvidencePayload> {

    String sourceType();

    EvidenceResult<T> collect(String anchor);
}

