package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.port.AfterSaleReadPort;
import com.trade.mall.agent.evidence.port.AfterSaleRecord;
import com.trade.mall.agent.evidence.port.EvidenceResult;

/** `oms_order_return_apply` 证据采集器——`sourceType = "AFTER_SALE"`。行为模式与 {@link OrderEvidenceCollector} 完全同构。 */
public final class AfterSaleEvidenceCollector implements EvidenceCollector<AfterSaleRecord> {

    public static final String SOURCE_TYPE = "AFTER_SALE";
    private static final String TABLE = "oms_order_return_apply";

    private final AfterSaleReadPort readPort;

    public AfterSaleEvidenceCollector(AfterSaleReadPort readPort) { this.readPort = readPort; }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public EvidenceResult<AfterSaleRecord> collect(String anchor) {
        try {
            return readPort.findByOrderSn(anchor)
                .<EvidenceResult<AfterSaleRecord>>map(EvidenceResult.Present::new)
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

