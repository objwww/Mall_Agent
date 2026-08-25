package com.trade.mall.agent.evidence.application;

import com.trade.mall.agent.evidence.AcquireState;
import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.evidence.collector.AfterSaleEvidenceCollector;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.collector.OrderEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundLogEvidenceCollector;
import com.trade.mall.agent.evidence.infrastructure.InMemoryAfterSaleReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryOrderReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundLogReadPort;
import com.trade.mall.agent.evidence.infrastructure.InMemoryRefundReadPort;
import com.trade.mall.agent.evidence.port.OrderRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** 与 SelfCheck §46/§47 一一对应：并行扇出集成 + 超时不拖垮整体。 */
class EvidenceCollectionServiceTest {

    static final long NOW = 1_700_000_000_000L;
    ExecutorService pool;

    @AfterEach void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    @Test void mixed_present_empty_unavailable_are_each_recorded_once() {
        var ledger = new InMemoryEventLedger();
        var orderPort = new InMemoryOrderReadPort().put(new OrderRecord(1, "order-1", 1, "alice", BigDecimal.TEN, NOW));
        var refundPort = new InMemoryRefundReadPort().put(new RefundRecord(1, "refund-1", "order-1", 2, BigDecimal.TEN, null, NOW));
        var afterSalePort = new InMemoryAfterSaleReadPort(); // 空：EMPTY
        var refundLogPort = new InMemoryRefundLogReadPort().disconnect(); // UNAVAILABLE
        pool = Executors.newFixedThreadPool(4);

        List<EvidenceCollector<?>> collectors = List.of(
            new OrderEvidenceCollector(orderPort),
            new RefundEvidenceCollector(refundPort),
            new AfterSaleEvidenceCollector(afterSalePort),
            new RefundLogEvidenceCollector(refundLogPort)
        );
        var service = new EvidenceCollectionService(collectors, ledger, pool, Duration.ofSeconds(2), () -> NOW);

        var bundle = service.collect("order-1");

        assertEquals(4, bundle.items().size());
        assertEquals(2, bundle.withState(AcquireState.PRESENT).size());
        assertEquals(1, bundle.withState(AcquireState.EMPTY).size());
        assertEquals(1, bundle.withState(AcquireState.UNAVAILABLE).size());
        assertTrue(bundle.hasAnyUnavailable());

        assertEquals(2, ledger.countOfType("order-1", "Evidence.Collected"));
        assertEquals(1, ledger.countOfType("order-1", "Evidence.Empty"));
        assertEquals(1, ledger.countOfType("order-1", "Evidence.Unavailable"));
        assertTrue(ledger.exists(EvidenceEventIds.collected("order-1", "ORDER")));
        assertTrue(ledger.exists(EvidenceEventIds.unavailable("order-1", "REFUND_LOG")));
    }

    @Test void one_slow_collector_does_not_block_the_whole_bundle() {
        var ledger = new InMemoryEventLedger();
        var orderPort = new InMemoryOrderReadPort().withArtificialDelay(2000);
        var refundPort = new InMemoryRefundReadPort();
        var afterSalePort = new InMemoryAfterSaleReadPort();
        var refundLogPort = new InMemoryRefundLogReadPort();
        pool = Executors.newFixedThreadPool(4);

        List<EvidenceCollector<?>> collectors = List.of(
            new OrderEvidenceCollector(orderPort),
            new RefundEvidenceCollector(refundPort),
            new AfterSaleEvidenceCollector(afterSalePort),
            new RefundLogEvidenceCollector(refundLogPort)
        );
        var service = new EvidenceCollectionService(collectors, ledger, pool, Duration.ofMillis(200), () -> NOW);

        long start = System.nanoTime();
        var bundle = service.collect("order-timeout");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 1500, "不该等满 2000ms 那个慢查询，实际耗时=" + elapsedMs + "ms");
        var orderEvidence = bundle.items().stream()
            .filter(e -> e.sourceType().equals("ORDER")).findFirst().orElseThrow();
        assertEquals(AcquireState.UNAVAILABLE, orderEvidence.acquireState());
        assertTrue(orderEvidence.unavailableReason().contains("超时"));
    }
}

