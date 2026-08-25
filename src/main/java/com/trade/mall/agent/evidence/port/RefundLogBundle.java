package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

import java.util.List;

/**
 * 一次查询取回的完整退款日志序列，包装成单个 {@link EvidencePayload}——
 * 这是"`EvidenceResult` 外层不是裸集合，但内部载荷完全可以是个列表"这条设计原则
 * （见 `EvidenceResult` 类头）唯一实际用到列表的地方：`RefundLogEvidenceCollector`
 * 的返回类型是 `EvidenceResult<RefundLogBundle>`，不是 `EvidenceResult<List<RefundLogRecord>>`
 * ——多包一层是为了让"载荷是什么形状"这件事对 `Evidence.payload()` 的调用方保持统一
 * （拿到手先 instanceof 判断具体类型，而不必对某一种证据特殊处理"这个字段其实是个 List"）。
 */
public record RefundLogBundle(List<RefundLogRecord> entries) implements EvidencePayload {
    public RefundLogBundle {
        entries = List.copyOf(entries);
    }
}

