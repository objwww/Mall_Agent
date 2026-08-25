package com.trade.mall.agent.evidence.application;

import com.trade.mall.agent.evidence.AcquireState;
import com.trade.mall.agent.evidence.ConfidenceLevel;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.EvidenceEventIds;
import com.trade.mall.agent.evidence.EvidencePayload;
import com.trade.mall.agent.evidence.SourceLocator;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventLedger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * EvidenceCollectionService —— `M-CAP-02`：并行取证的应用服务。
 *
 * <p>三件事，顺序固定：① 按锚点向注册的每个 {@link EvidenceCollector} 并行扇出
 * （`CON-EXT-003`：平台线程池，调用方注入的 {@link ExecutorService}，本类不隐式创建
 * 也不隐式关闭它——线程池的生命周期由组装这个服务的一方负责）；② 每个采集器有独立的
 * 超时预算，超时**不等待**，直接把那一条证据判定为 UNAVAILABLE（不能因为一个源慢
 * 就拖垮整个 bundle）；③ 把每条 {@code EvidenceResult} 翻译成 {@link Evidence} 之后
 * 写入账本（`Evidence.Collected`/`Evidence.Empty`/`Evidence.Unavailable`，三个独立事件，
 * `domain_events.md` §2.2），最后组装成 {@link EvidenceBundle} 返回。
 *
 * <p><b>关于 {@code TABLE_OF} 这张表为什么放在这里，而不是让每个 collector 自己暴露
 * 一个 {@code tableName()}</b>：`architecture_rules.md §2.8` 的 `collectors_must_return_evidence_result`
 * 规则字面意思是"`evidence.collector` 包下**每一个** public 非静态方法都必须返回
 * `EvidenceResult`"——按字面执行，连 {@code sourceType()} 这种纯元数据访问器都会违规。
 * 这条规则的验收标准原文其实是"证据采集方法返回类型非 Collection"，指的应该只是
 * `collect()` 这一个方法，规则的 ArchUnit 伪代码写得比实际意图更宽。D5 选择不再往
 * `EvidenceCollector` 接口上加方法（`sourceType()` 已经是必要的最小暴露面），
 * 把"sourceType → 真实表名"这张查表放进本类——这样即使按最严格的字面意思读这条规则，
 * `EvidenceCollector` 实现类也只有 {@code collect()} 一个业务方法，`sourceType()` 是
 * 唯一的例外，比"再加一个 tableName()"更克制。这个规则文本本身的精度问题已经在
 * `D5-REPORT.md` §4 里作为文档缺口标注，留给 D11 的 ArchUnit 整体接入一起修。</p>
 */
public final class EvidenceCollectionService {

    private static final Map<String, String> TABLE_OF = Map.of(
        "ORDER", "oms_order",
        "REFUND", "oms_order_refund",
        "AFTER_SALE", "oms_order_return_apply",
        "REFUND_LOG", "oms_order_refund_log",
        "PAYMENT_GATEWAY", "payment_gateway_api"
    );

    private final List<EvidenceCollector<?>> collectors;
    private final EventLedger ledger;
    private final ExecutorService pool;
    private final Duration perCollectorTimeout;
    private final LongSupplier clock;

    public EvidenceCollectionService(List<EvidenceCollector<?>> collectors, EventLedger ledger,
                                      ExecutorService pool, Duration perCollectorTimeout, LongSupplier clock) {
        this.collectors = List.copyOf(collectors);
        this.ledger = ledger;
        this.pool = pool;
        this.perCollectorTimeout = perCollectorTimeout;
        this.clock = clock;
    }

    /** 对一个 Diagnosis（诊断）围绕一个锚点并行取证；旧入口代表第 1 轮。 */
    public EvidenceBundle collect(String diagnosisId, String anchor) {
        return collect(diagnosisId, 1, anchor);
    }

    /** 同一 Diagnosis 重新取证时显式传 round，避免第 2 轮事件与第 1 轮碰撞。 */
    public EvidenceBundle collect(String diagnosisId, int round, String anchor) {
        if (diagnosisId == null || diagnosisId.isBlank()) throw new IllegalArgumentException("diagnosisId must not be blank");
        if (round < 1) throw new IllegalArgumentException("round must be >= 1");
        List<CompletableFuture<Evidence>> futures = collectors.stream()
            .map(c -> collectOneAsync(c, diagnosisId, round, anchor))
            .toList();

        List<Evidence> items = futures.stream().map(CompletableFuture::join).toList();

        for (Evidence e : items) ledger.append(eventFor(diagnosisId, e));
        return EvidenceBundle.of(diagnosisId, anchor, items, round);
    }

    /** D1-D8 独立样例兼容；生产编排必须传 diagnosisId。 */
    public EvidenceBundle collect(String anchor) {
        return collect(anchor, 1, anchor);
    }

    private <T extends EvidencePayload> CompletableFuture<Evidence> collectOneAsync(
            EvidenceCollector<T> collector, String diagnosisId, int round, String anchor) {
        return CompletableFuture
            .supplyAsync(() -> collector.collect(anchor), pool)
            .orTimeout(perCollectorTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .handle((result, ex) -> {
                long acquiredAt = clock.getAsLong();
                return ex != null
                    ? timeoutEvidence(diagnosisId, round, collector.sourceType(), anchor, ex, acquiredAt)
                    : toEvidence(diagnosisId, round, collector.sourceType(), anchor, result, acquiredAt);
            });
    }

    private Evidence timeoutEvidence(String diagnosisId, int round, String sourceType, String anchor, Throwable ex, long acquiredAt) {
        String table = tableOf(sourceType);
        return Evidence.unavailable(
            EvidenceEventIds.unavailable(diagnosisId, sourceType, round),
            sourceType, SourceLocator.tableOnly(table), ConfidenceLevel.VERIFIED,
            "查询超时或调度异常（预算 " + perCollectorTimeout.toMillis() + "ms）：" + rootMessage(ex), acquiredAt);
    }

    private static <T extends EvidencePayload> Evidence toEvidence(
            String diagnosisId, int round, String sourceType, String anchor, EvidenceResult<T> result, long acquiredAt) {
        String table = tableOf(sourceType);
        SourceLocator locator = SourceLocator.of(table, "orderSn=" + anchor);
        if (result instanceof EvidenceResult.Present<T> present) {
            return Evidence.present(EvidenceEventIds.collected(diagnosisId, sourceType, round), sourceType, locator,
                ConfidenceLevel.VERIFIED, present.value(), acquiredAt, acquiredAt);
        }
        if (result instanceof EvidenceResult.Empty<T>) {
            return Evidence.empty(EvidenceEventIds.empty(diagnosisId, sourceType, round), sourceType, locator,
                ConfidenceLevel.VERIFIED, acquiredAt, acquiredAt);
        }
        if (result instanceof EvidenceResult.Unavailable<T> unavailable) {
            return Evidence.unavailable(EvidenceEventIds.unavailable(diagnosisId, sourceType, round), sourceType, SourceLocator.tableOnly(table),
                ConfidenceLevel.VERIFIED, unavailable.reason(), acquiredAt);
        }
        throw new IllegalStateException("unreachable: unknown EvidenceResult variant for sourceType=" + sourceType);
    }

    private DomainEvent eventFor(String diagnosisId, Evidence e) {
        long now = e.acquiredAtEpochMillis() > 0 ? e.acquiredAtEpochMillis() : clock.getAsLong();
        String payload = e.sourceType() + " locator=" + e.sourceLocator()
            + " observedAt=" + (e.observedAtEpochMillis() == null ? "NONE" : e.observedAtEpochMillis())
            + " acquiredAt=" + now;
        if (e.acquireState() == AcquireState.UNAVAILABLE) {
            payload += " reason=" + (e.unavailableReason() == null ? "" : e.unavailableReason());
        }
        return new DomainEvent(e.evidenceId(), diagnosisId, switch (e.acquireState()) {
            case PRESENT -> "Evidence.Collected";
            case EMPTY -> "Evidence.Empty";
            case UNAVAILABLE -> "Evidence.Unavailable";
        }, 0, payload, now);
    }

    private static String tableOf(String sourceType) {
        return TABLE_OF.getOrDefault(sourceType, sourceType);
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}

