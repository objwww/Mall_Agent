package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

import java.math.BigDecimal;

/** `oms_order_refund` 的只读行投影——退款单本身的当前状态（不是执行历史，那是 {@link RefundLogRecord}）。 */
public record RefundRecord(
        long id,
        String refundSn,
        Long returnApplyId,
        String orderSn,
        int status,
        BigDecimal refundAmount,
        String errorMsg,
        Long finishTimeEpochMillis
) implements EvidencePayload {
    /** 兼容 D5-D8 既有测试数据；真实 Mall 适配器应填充 returnApplyId。 */
    public RefundRecord(long id, String refundSn, String orderSn, int status,
                        BigDecimal refundAmount, String errorMsg, Long finishTimeEpochMillis) {
        this(id, refundSn, null, orderSn, status, refundAmount, errorMsg, finishTimeEpochMillis);
    }
}

