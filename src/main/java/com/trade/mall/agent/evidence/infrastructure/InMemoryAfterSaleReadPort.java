package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.AfterSaleReadPort;
import com.trade.mall.agent.evidence.port.AfterSaleRecord;
import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存版 `oms_order_return_apply` 只读端口。行为约定同 {@link InMemoryOrderReadPort}。 */
public final class InMemoryAfterSaleReadPort implements AfterSaleReadPort {

    private final Map<String, AfterSaleRecord> rows = new ConcurrentHashMap<>();
    private volatile boolean connected = true;
    private volatile RuntimeException poison;

    public InMemoryAfterSaleReadPort put(AfterSaleRecord record) { rows.put(record.orderSn(), record); return this; }
    public InMemoryAfterSaleReadPort disconnect() { this.connected = false; return this; }
    public InMemoryAfterSaleReadPort reconnect() { this.connected = true; return this; }
    public InMemoryAfterSaleReadPort poisonWith(RuntimeException ex) { this.poison = ex; return this; }

    @Override
    public Optional<AfterSaleRecord> findByOrderSn(String orderSn) {
        if (!connected) throw new DataSourceUnavailableException("oms_order_return_apply 数据源不可用：连接被拒绝");
        if (poison != null) throw poison;
        return Optional.ofNullable(rows.get(orderSn));
    }
}

