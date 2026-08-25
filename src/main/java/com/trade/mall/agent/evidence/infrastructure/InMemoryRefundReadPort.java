package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;
import com.trade.mall.agent.evidence.port.RefundReadPort;
import com.trade.mall.agent.evidence.port.RefundRecord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存版 `oms_order_refund` 只读端口。行为约定同 {@link InMemoryOrderReadPort}。 */
public final class InMemoryRefundReadPort implements RefundReadPort {

    private final Map<String, RefundRecord> rows = new ConcurrentHashMap<>();
    private volatile boolean connected = true;
    private volatile RuntimeException poison;

    public InMemoryRefundReadPort put(RefundRecord record) { rows.put(record.orderSn(), record); return this; }
    public InMemoryRefundReadPort disconnect() { this.connected = false; return this; }
    public InMemoryRefundReadPort reconnect() { this.connected = true; return this; }
    public InMemoryRefundReadPort poisonWith(RuntimeException ex) { this.poison = ex; return this; }

    @Override
    public Optional<RefundRecord> findByOrderSn(String orderSn) {
        if (!connected) throw new DataSourceUnavailableException("oms_order_refund 数据源不可用：连接被拒绝");
        if (poison != null) throw poison;
        return Optional.ofNullable(rows.get(orderSn));
    }
}

