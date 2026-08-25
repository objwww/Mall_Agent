package com.trade.mall.agent.evidence.port;

import java.util.Optional;

/** 支付网关纯查询端口：只能读取渠道交易事实，不允许顺带修改 Mall 订单状态。 */
public interface PaymentGatewayReadPort {
    Optional<PaymentGatewayRecord> findByOrderSn(String orderSn);
}

