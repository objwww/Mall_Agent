package com.trade.mall.agent.evidence.port;

import java.util.Optional;

/** `M-ADP-01`：`oms_order_refund` 的只读端口。语义约定同 {@link OrderReadPort}。 */
public interface RefundReadPort {
    Optional<RefundRecord> findByOrderSn(String orderSn);
}

