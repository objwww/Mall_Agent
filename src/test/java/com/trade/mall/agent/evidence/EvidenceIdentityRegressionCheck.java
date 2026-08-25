package com.trade.mall.agent.evidence;

import com.trade.mall.agent.evidence.application.EvidenceCollectionService;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.OrderRecord;
import com.trade.mall.agent.evidence.port.RefundLogBundle;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** V7：Evidence Identity（证据身份）与时间语义最小反例检查。 */
public final class EvidenceIdentityRegressionCheck {
    private static int passed;

    public static void main(String[] args) {
        var ledger = new InMemoryEventLedger();
        var clock = new AtomicLong(1_700_000_000_000L);
        var pool = Executors.newFixedThreadPool(2);
        try {
            EvidenceCollector<OrderRecord> order = new EvidenceCollector<>() {
                @Override public String sourceType() { return "ORDER"; }
                @Override public EvidenceResult<OrderRecord> collect(String anchor) {
                    return new EvidenceResult.Present<>(new OrderRecord(
                        1, anchor, 1, "tester", new BigDecimal("100.00"), clock.get()));
                }
            };
            EvidenceCollector<RefundLogBundle> unavailableLog = new EvidenceCollector<>() {
                @Override public String sourceType() { return "REFUND_LOG"; }
                @Override public EvidenceResult<RefundLogBundle> collect(String anchor) {
                    return new EvidenceResult.Unavailable<>("refund log database unavailable");
                }
            };

            var service = new EvidenceCollectionService(
                List.of(order, unavailableLog), ledger, pool, Duration.ofSeconds(1), clock::incrementAndGet);

            EvidenceBundle first = service.collect("diag-A", "order-1");
            EvidenceBundle second = service.collect("diag-B", "order-1");

            Evidence firstOrder = source(first, "ORDER");
            Evidence secondOrder = source(second, "ORDER");
            require(!firstOrder.evidenceId().equals(secondOrder.evidenceId()),
                "同一订单的两个 Diagnosis 必须产生不同 evidenceId");
            require(firstOrder.evidenceId().equals(EvidenceEventIds.collected("diag-A", "ORDER"))
                    && secondOrder.evidenceId().equals(EvidenceEventIds.collected("diag-B", "ORDER")),
                "evidenceId 必须以 diagnosisId 而不是 orderSn 为作用域");
            require(ledger.countOfType("diag-A", "Evidence.Collected") == 1
                    && ledger.countOfType("diag-B", "Evidence.Collected") == 1,
                "同一 anchor 的第二个 Diagnosis 不能被事件账本当成第一次诊断的重复事件");

            require(firstOrder.observedAtEpochMillis() != null
                    && firstOrder.acquiredAtEpochMillis() >= firstOrder.observedAtEpochMillis(),
                "PRESENT 必须记录事实观察时间和证据获取时间");

            Evidence unavailable = source(first, "REFUND_LOG");
            require(unavailable.observedAtEpochMillis() == null && unavailable.acquiredAtEpochMillis() > 0,
                "UNAVAILABLE 没有观察到业务事实，observedAt 必须为空，但仍要记录 acquiredAt");
            require(unavailable.sourceLocator().primaryKey() == null,
                "UNAVAILABLE 不得伪造具体业务行定位");

            require(!ledger.exists(EvidenceEventIds.collected("order-1", "ORDER")),
                "真实采集路径不能再使用 anchor 作为 evidence/event identity");

            System.out.println("V7 evidence identity regression: " + passed + "/7 passed");
        } finally {
            pool.shutdownNow();
        }
    }

    private static Evidence source(EvidenceBundle bundle, String sourceType) {
        return bundle.items().stream().filter(e -> sourceType.equals(e.sourceType())).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}

