package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;
import com.trade.mall.agent.evidence.port.OrderReadPort;
import com.trade.mall.agent.evidence.port.OrderRecord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 `oms_order` 只读端口（测试/演示用；生产是 MyBatis Mapper + 只读 DB 账号）。
 *
 * <p>两种独立的"坏"可以模拟，对应验收标准要求区分的两类失败：</p>
 * <ul>
 *   <li>{@link #disconnect()} —— 数据源整体不可用（连接池耗尽/目标库宕机），
 *       抛 {@link DataSourceUnavailableException}</li>
 *   <li>{@link #poisonWith(RuntimeException)} —— 数据源本身连着，但**这一次查询**
 *       意外抛出了某个具体异常（SQL 语法错误、序列化异常等）——用来验证
 *       `ARCH-EVID-002` 的翻译逻辑不是只认识"断开"这一种失败信号，
 *       而是**任何**运行时异常都会被同样翻译成 UNAVAILABLE（`SelfCheck §45`）</li>
 * </ul>
 */
public final class InMemoryOrderReadPort implements OrderReadPort {

    private final Map<String, OrderRecord> rows = new ConcurrentHashMap<>();
    private volatile boolean connected = true;
    private volatile RuntimeException poison;
    private volatile long artificialDelayMillis = 0L;

    public InMemoryOrderReadPort put(OrderRecord record) { rows.put(record.orderSn(), record); return this; }
    public InMemoryOrderReadPort disconnect() { this.connected = false; return this; }
    public InMemoryOrderReadPort reconnect() { this.connected = true; return this; }
    public InMemoryOrderReadPort poisonWith(RuntimeException ex) { this.poison = ex; return this; }
    public InMemoryOrderReadPort clearPoison() { this.poison = null; return this; }
    /** §47 超时测试用：模拟一次会比调用方超时预算更慢的查询。 */
    public InMemoryOrderReadPort withArtificialDelay(long millis) { this.artificialDelayMillis = millis; return this; }

    @Override
    public Optional<OrderRecord> findByOrderSn(String orderSn) {
        if (!connected) {
            throw new DataSourceUnavailableException("oms_order 数据源不可用：连接被拒绝");
        }
        if (poison != null) {
            throw poison;
        }
        if (artificialDelayMillis > 0) {
            try { Thread.sleep(artificialDelayMillis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        return Optional.ofNullable(rows.get(orderSn));
    }
}

