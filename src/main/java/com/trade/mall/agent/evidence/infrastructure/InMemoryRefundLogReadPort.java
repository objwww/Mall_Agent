package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;
import com.trade.mall.agent.evidence.port.RefundLogReadPort;
import com.trade.mall.agent.evidence.port.RefundLogRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 `oms_order_refund_log` 只读端口——底层就是裸 {@code List}（见
 * {@link RefundLogReadPort} 类头），空列表天然表示"确认没有日志"，不需要额外区分。
 */
public final class InMemoryRefundLogReadPort implements RefundLogReadPort {

    private final Map<String, List<RefundLogRecord>> rows = new ConcurrentHashMap<>();
    private volatile boolean connected = true;
    private volatile RuntimeException poison;

    public InMemoryRefundLogReadPort add(RefundLogRecord record) {
        rows.computeIfAbsent(record.orderSn(), k -> new ArrayList<>()).add(record);
        return this;
    }
    public InMemoryRefundLogReadPort disconnect() { this.connected = false; return this; }
    public InMemoryRefundLogReadPort reconnect() { this.connected = true; return this; }
    public InMemoryRefundLogReadPort poisonWith(RuntimeException ex) { this.poison = ex; return this; }

    @Override
    public List<RefundLogRecord> findByOrderSn(String orderSn) {
        if (!connected) throw new DataSourceUnavailableException("oms_order_refund_log 数据源不可用：连接被拒绝");
        if (poison != null) throw poison;
        return List.copyOf(rows.getOrDefault(orderSn, List.of()));
    }
}

