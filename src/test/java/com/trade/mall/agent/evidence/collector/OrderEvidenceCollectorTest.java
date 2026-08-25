package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.infrastructure.InMemoryOrderReadPort;
import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.OrderRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §43/§45 一一对应：其余三个采集器（Refund/AfterSale/RefundLog）走的是
 * 完全同构的三态机制，不再各写一份近乎重复的测试类——`SelfCheck §44` 已经现场验证过
 * 它们复现了这里验证的同一套行为。
 */
class OrderEvidenceCollectorTest {

    static final long NOW = 1_700_000_000_000L;
    InMemoryOrderReadPort readPort;
    OrderEvidenceCollector collector;

    @BeforeEach void setup() {
        readPort = new InMemoryOrderReadPort();
        collector = new OrderEvidenceCollector(readPort);
    }

    @Test void existing_order_yields_present() {
        readPort.put(new OrderRecord(1, "order-1", 1, "alice", BigDecimal.TEN, NOW));

        var result = collector.collect("order-1");

        assertInstanceOf(EvidenceResult.Present.class, result);
        assertEquals("order-1", ((EvidenceResult.Present<OrderRecord>) result).value().orderSn());
    }

    @Test void missing_order_yields_empty_not_unavailable() {
        var result = collector.collect("order-does-not-exist");

        assertInstanceOf(EvidenceResult.Empty.class, result, "查询成功但没这一行 == EMPTY，不是 UNAVAILABLE");
    }

    @Test void disconnected_data_source_yields_unavailable_INV_EVID_001() {
        readPort.put(new OrderRecord(1, "order-1", 1, "alice", BigDecimal.TEN, NOW));
        readPort.disconnect();

        var result = collector.collect("order-1");

        assertInstanceOf(EvidenceResult.Unavailable.class, result, "断开数据源必须是 UNAVAILABLE，绝不能悄悄退化成空集");
        assertTrue(((EvidenceResult.Unavailable<OrderRecord>) result).reason().contains("oms_order"));
    }

    @Test void unexpected_exception_is_also_translated_to_unavailable_ARCH_EVID_002() {
        readPort.poisonWith(new IllegalStateException("驱动内部错误"));

        var result = collector.collect("order-1");

        assertInstanceOf(EvidenceResult.Unavailable.class, result,
            "catch 到的不只是显式'已断开'标志位，任何运行时异常都必须被翻译成 UNAVAILABLE，不能被吞掉");
        assertTrue(((EvidenceResult.Unavailable<OrderRecord>) result).reason().contains("驱动内部错误"));
    }
}

