package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

/** `oms_order_return_apply`（售后/退货申请）的只读行投影。 */
public record AfterSaleRecord(
        long id,
        String orderSn,
        int status,
        String reason,
        String handleNote
) implements EvidencePayload {
}

