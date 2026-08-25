package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

/**
 * PAYMENT_GATEWAY（支付网关）只读事实投影。当前诊断只需要订单号与渠道交易状态；
 * 不把 Mall 订单本地状态混进来，避免证据源自我确认。
 */
public record PaymentGatewayRecord(
        String orderSn,
        String tradeStatus
) implements EvidencePayload {
    public PaymentGatewayRecord {
        if (orderSn == null || orderSn.isBlank()) throw new IllegalArgumentException("orderSn must not be blank");
        if (tradeStatus == null || tradeStatus.isBlank()) throw new IllegalArgumentException("tradeStatus must not be blank");
    }
}

