package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.RefundReadPort;
import com.trade.mall.agent.evidence.port.RefundRecord;

/** `oms_order_refund` 证据采集器——`sourceType = "REFUND"`。行为模式与 {@link OrderEvidenceCollector} 完全同构。 */
public final class RefundEvidenceCollector implements EvidenceCollector<RefundRecord> {

    public static final String SOURCE_TYPE = "REFUND";
    private static final String TABLE = "oms_order_refund";

    private final RefundReadPort readPort;

    public RefundEvidenceCollector(RefundReadPort readPort) { this.readPort = readPort; }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public EvidenceResult<RefundRecord> collect(String anchor) {
        try {
            return readPort.findByOrderSn(anchor)
                .<EvidenceResult<RefundRecord>>map(EvidenceResult.Present::new)
                .orElseGet(EvidenceResult.Empty::new);
        } catch (RuntimeException ex) {
            return new EvidenceResult.Unavailable<>(describe(ex));
        }
    }

    private static String describe(RuntimeException ex) {
        String msg = ex.getMessage();
        return TABLE + " 查询失败：" + (msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg);
    }
}

