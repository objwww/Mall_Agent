package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.PaymentGatewayReadPort;
import com.trade.mall.agent.evidence.port.PaymentGatewayRecord;

/** PAYMENT_GATEWAY（支付网关）证据采集器；底层必须是无副作用的纯查询接口。 */
public final class PaymentGatewayEvidenceCollector implements EvidenceCollector<PaymentGatewayRecord> {
    public static final String SOURCE_TYPE = "PAYMENT_GATEWAY";
    private final PaymentGatewayReadPort readPort;

    public PaymentGatewayEvidenceCollector(PaymentGatewayReadPort readPort) { this.readPort = readPort; }

    @Override public String sourceType() { return SOURCE_TYPE; }

    @Override
    public EvidenceResult<PaymentGatewayRecord> collect(String anchor) {
        try {
            return readPort.findByOrderSn(anchor)
                .<EvidenceResult<PaymentGatewayRecord>>map(EvidenceResult.Present::new)
                .orElseGet(EvidenceResult.Empty::new);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            return new EvidenceResult.Unavailable<>("payment gateway 查询失败："
                + (msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg));
        }
    }
}

