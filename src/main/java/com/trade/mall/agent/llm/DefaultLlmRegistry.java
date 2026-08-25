package com.trade.mall.agent.llm;

import com.trade.mall.agent.alert.AlertPort;
import com.trade.mall.agent.ledger.EventLedger;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * DefaultLlmRegistry —— `M-LLM-01` §5.1-5.2 的落地，字段/流程与文档原文一致，
 * 两处刻意偏离在类内注释里各自说明：① 用内部单调递增的 {@code switchSeq} 代替
 * 文档里来自 Nacos 的 {@code configVersion}（D6 没有真实 Nacos）。V8 进一步把
 * client lifecycle（客户端生命周期）和 version pin（版本钉住）合并成同一条正确语义：
 * 切换只把旧实例标记为 retired（退役），仍有在途 Diagnosis（诊断）引用时绝不关闭；
 * 最后一个 pin release（钉住引用释放）以后才真正 {@code shutdown(grace)}。这样真实
 * HTTP client（HTTP 客户端）即使 shutdown 后不可再用，也不会破坏 ADR-015。</p>
 */
public final class DefaultLlmRegistry implements LlmRegistry, AutoCloseable {

    /**
     * 当前生效实例。用 volatile 而非任何"销毁重建"语义（`M-LLM-01` §5.1 对
     * `@RefreshScope` 的拒绝理由）：替换必须是原子的单次写，读路径完全无锁。
     */
    private volatile ClientLease currentLease;

    /** 切换串行化。读路径（current()）完全无锁。 */
    private final ReentrantLock switchLock = new ReentrantLock();

    private final LlmClientFactory factory;
    private final EventLedger ledger;
    private final AlertPort alertPort;
    private final PromptVersionStore promptVersionStore;
    private final SkillVersionStore skillVersionStore;
    private final String toolSchemaVersion;
    private final String toolManifestDigest;
    private final Duration graceShutdown;
    private final LongSupplier clock;
    /** JVM 重启后按 diagnosisId 找回先前持久化的版本快照；旧构造器默认不启用。 */
    private final Function<String, Optional<VersionSnapshot>> historicalSnapshotResolver;
    private final AtomicLong switchSeq = new AtomicLong(0);

    /**
     * 一个真实客户端实例的最小生命周期记录。refs 只统计 diagnosis pin（诊断钉住引用）；
     * retired=true 表示它已不再是 current（当前实例），但只要 refs>0 就必须继续可用。
     * 所有 refs/retired/shutdown 读写都在 switchLock（切换锁）内完成。
     */
    private static final class ClientLease {
        private final LlmClient client;
        private int refs;
        private boolean retired;
        private boolean shutdown;

        private ClientLease(LlmClient client) { this.client = client; }
    }

    /** ADR-015 版本钉住：diagnosisId → 客户端租约 + 版本/提示词快照。 */
    private record PinnedEntry(ClientLease lease, VersionSnapshot snapshot, PromptSnapshot promptSnapshot,
                               SkillSnapshot skillSnapshot) {}
    private final ConcurrentHashMap<String, PinnedEntry> pins = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public DefaultLlmRegistry(LlmClientFactory factory, EventLedger ledger, AlertPort alertPort,
                               PromptVersionStore promptVersionStore, String toolSchemaVersion,
                               String initialModelId, Duration graceShutdown, LongSupplier clock) {
        this(factory, ledger, alertPort, promptVersionStore, legacySkillStore(), toolSchemaVersion,
            VersionSnapshot.LEGACY_TOOL_MANIFEST_DIGEST, initialModelId,
            graceShutdown, clock, id -> Optional.empty());
    }

    public DefaultLlmRegistry(LlmClientFactory factory, EventLedger ledger, AlertPort alertPort,
                               PromptVersionStore promptVersionStore, String toolSchemaVersion,
                               String initialModelId, Duration graceShutdown, LongSupplier clock,
                               Function<String, Optional<VersionSnapshot>> historicalSnapshotResolver) {
        this(factory, ledger, alertPort, promptVersionStore, legacySkillStore(), toolSchemaVersion,
            VersionSnapshot.LEGACY_TOOL_MANIFEST_DIGEST,
            initialModelId, graceShutdown, clock, historicalSnapshotResolver);
    }

    public DefaultLlmRegistry(LlmClientFactory factory, EventLedger ledger, AlertPort alertPort,
                               PromptVersionStore promptVersionStore, SkillVersionStore skillVersionStore,
                               String toolSchemaVersion, String initialModelId, Duration graceShutdown,
                               LongSupplier clock, Function<String, Optional<VersionSnapshot>> historicalSnapshotResolver) {
        this(factory, ledger, alertPort, promptVersionStore, skillVersionStore, toolSchemaVersion,
            VersionSnapshot.LEGACY_TOOL_MANIFEST_DIGEST, initialModelId, graceShutdown, clock,
            historicalSnapshotResolver);
    }

    public DefaultLlmRegistry(LlmClientFactory factory, EventLedger ledger, AlertPort alertPort,
                               PromptVersionStore promptVersionStore, SkillVersionStore skillVersionStore,
                               String toolSchemaVersion, String toolManifestDigest, String initialModelId,
                               Duration graceShutdown, LongSupplier clock,
                               Function<String, Optional<VersionSnapshot>> historicalSnapshotResolver) {
        this.factory = factory;
        this.ledger = ledger;
        this.alertPort = alertPort;
        this.promptVersionStore = promptVersionStore;
        this.skillVersionStore = skillVersionStore;
        this.toolSchemaVersion = toolSchemaVersion;
        this.toolManifestDigest = toolManifestDigest;
        this.graceShutdown = graceShutdown;
        this.clock = clock;
        this.historicalSnapshotResolver = historicalSnapshotResolver == null ? id -> Optional.empty() : historicalSnapshotResolver;

        // 启动时必须构建成功，否则应用启动失败。
        // 宁可起不来，也不要起来了但 current() 返回 null（INV-CFG-002）。
        LlmClient initial = factory.create(initialModelId);
        if (!initial.healthy()) {
            try { initial.shutdown(Duration.ZERO); } catch (Exception ignore) { /* 启动失败，尽力释放 */ }
            throw new IllegalStateException("initial LLM client unhealthy: " + initialModelId);
        }
        this.currentLease = new ClientLease(initial);
    }

    @Override
    public LlmClient current() {
        ensureOpen();
        return currentLease.client; // volatile 读，无锁
    }

    @Override
    public String currentModelId() {
        ensureOpen();
        return currentLease.client.modelId();
    }

    @Override
    public SwitchResult switchTo(String targetModelId) {
        switchLock.lock();
        try {
            ensureOpen();
            final ClientLease oldLease = currentLease;
            final LlmClient old = oldLease.client;
            final String from = old.modelId();
            final long seq = switchSeq.incrementAndGet();

            if (from.equals(targetModelId)) {
                return new SwitchResult.NoOp(from);
            }

            ledger.append(LlmEvents.switchRequested(seq, from, targetModelId, clock.getAsLong()));

            // ---- 1. 构建 ----
            LlmClient candidate;
            try {
                candidate = factory.create(targetModelId);
            } catch (Exception e) {
                return abort(seq, from, targetModelId, "BUILD_FAILED: " + e.getMessage(), null);
            }

            // ---- 2. 健康探测（切换前）----
            long t0 = clock.getAsLong();
            boolean healthy;
            try {
                healthy = candidate.healthy();
            } catch (Exception e) {
                healthy = false;
            }
            long cost = clock.getAsLong() - t0;

            if (!healthy) {
                ledger.append(LlmEvents.healthCheckFailed(seq, targetModelId, cost, clock.getAsLong()));
                return abort(seq, from, targetModelId, "UNHEALTHY", candidate);
            }
            ledger.append(LlmEvents.healthCheckPassed(seq, targetModelId, cost, clock.getAsLong()));

            // ---- 3. 原子替换 ----
            currentLease = new ClientLease(candidate); // volatile 写：新诊断从此只会钉住新实例
            ledger.append(LlmEvents.switched(seq, from, targetModelId, cost, clock.getAsLong()));

            // ---- 4. 旧实例退役，而不是无条件立即关闭 ----
            // 只要仍有 Diagnosis（诊断）pin（钉住）旧实例，它就必须继续可用；
            // 最后一个 pin release（释放）后才真正 shutdown（关闭）。没有 pin 时则立即关闭。
            oldLease.retired = true;
            shutdownIfUnused(oldLease);

            return new SwitchResult.Switched(from, targetModelId, cost);

        } finally {
            switchLock.unlock();
        }
    }

    /** 中止切换。关键：不触碰 current 引用——一次配置写错不得导致系统整体不可用（INV-CFG-002）。 */
    private SwitchResult abort(long seq, String from, String attempted, String reason, LlmClient candidate) {
        if (candidate != null) {
            try { candidate.shutdown(Duration.ZERO); } catch (Exception ignore) { /* 候选实例从未生效 */ }
        }
        ledger.append(LlmEvents.switchAborted(seq, from, attempted, reason, clock.getAsLong()));

        // 切换失败必须告警：系统仍可用，但运维意图未被满足——沉默地维持现状会被误读为"操作成功"。
        alertPort.warning("llm.switch.aborted",
            "LLM 切换失败，保持旧模型 " + from + "。目标=" + attempted + "，原因=" + reason);

        return new SwitchResult.Aborted(from, attempted, reason);
    }

    @Override
    public VersionSnapshot pin(String diagnosisId) {
        if (diagnosisId == null || diagnosisId.isBlank()) {
            throw new IllegalArgumentException("diagnosisId must not be blank");
        }
        switchLock.lock();
        try {
            ensureOpen();
            PinnedEntry existing = pins.get(diagnosisId);
            if (existing != null) {
                return existing.snapshot(); // 幂等：重复 pin 不重复增加引用计数
            }
            Optional<VersionSnapshot> historical = historicalSnapshotResolver.apply(diagnosisId);
            if (historical.isPresent()) {
                VersionSnapshot snapshot = historical.get();
                if (!toolSchemaVersion.equals(snapshot.toolSchemaVersion())) {
                    throw new IllegalStateException("cannot resume diagnosis " + diagnosisId
                        + " with toolSchemaVersion=" + snapshot.toolSchemaVersion()
                        + "; runtime only has " + toolSchemaVersion + " (fail closed)");
                }
                if (!VersionSnapshot.LEGACY_TOOL_MANIFEST_DIGEST.equals(snapshot.toolManifestDigest())
                        && !toolManifestDigest.equals(snapshot.toolManifestDigest())) {
                    throw new IllegalStateException("cannot resume diagnosis " + diagnosisId
                        + " because tool manifest digest changed (fail closed)");
                }
                PromptSnapshot promptSnapshot = promptVersionStore.find(snapshot.promptVersion())
                    .orElseThrow(() -> new IllegalStateException(
                        "historical prompt version missing: " + snapshot.promptVersion()));
                SkillSnapshot skillSnapshot = historicalSkill(snapshot);
                ClientLease lease = leaseForHistoricalModel(snapshot.modelId());
                lease.refs++;
                pins.put(diagnosisId, new PinnedEntry(lease, snapshot, promptSnapshot, skillSnapshot));
                return snapshot;
            }

            ClientLease lease = currentLease;
            PromptSnapshot promptSnapshot = promptVersionStore.current();
            SkillSnapshot skillSnapshot = skillVersionStore.current();
            VersionSnapshot snapshot = new VersionSnapshot(
                lease.client.modelId(), promptSnapshot.version(), skillSnapshot.version(), toolSchemaVersion,
                toolManifestDigest);
            lease.refs++;
            pins.put(diagnosisId, new PinnedEntry(lease, snapshot, promptSnapshot, skillSnapshot));
            return snapshot;
        } finally {
            switchLock.unlock();
        }
    }

    @Override
    public LlmClient forPinned(String diagnosisId) {
        PinnedEntry entry = pins.get(diagnosisId);
        if (entry == null) {
            throw new IllegalStateException("no pinned snapshot for diagnosisId=" + diagnosisId + "; call pin() first");
        }
        return entry.lease().client;
    }

    @Override
    public PromptSnapshot promptForPinned(String diagnosisId) {
        PinnedEntry entry = pins.get(diagnosisId);
        if (entry == null) {
            throw new IllegalStateException("no pinned snapshot for diagnosisId=" + diagnosisId + "; call pin() first");
        }
        return entry.promptSnapshot();
    }

    @Override
    public SkillSnapshot skillForPinned(String diagnosisId) {
        PinnedEntry entry = pins.get(diagnosisId);
        if (entry == null) throw new IllegalStateException("no pinned snapshot for diagnosisId=" + diagnosisId + "; call pin() first");
        return entry.skillSnapshot();
    }

    private SkillSnapshot historicalSkill(VersionSnapshot snapshot) {
        if (VersionSnapshot.LEGACY_SKILL_VERSION.equals(snapshot.skillVersion())) {
            return new SkillSnapshot(VersionSnapshot.LEGACY_SKILL_VERSION, "");
        }
        return skillVersionStore.find(snapshot.skillVersion()).orElseThrow(() ->
            new IllegalStateException("historical skill version missing: " + snapshot.skillVersion()));
    }

    private static SkillVersionStore legacySkillStore() {
        return new com.trade.mall.agent.llm.infrastructure.InMemorySkillVersionStore(
            VersionSnapshot.LEGACY_SKILL_VERSION, "");
    }

    @Override
    public void release(String diagnosisId) {
        if (diagnosisId == null || diagnosisId.isBlank()) return;
        switchLock.lock();
        try {
            PinnedEntry removed = pins.remove(diagnosisId);
            if (removed == null) return; // 幂等释放
            ClientLease lease = removed.lease();
            if (lease.refs <= 0) {
                throw new IllegalStateException("LLM client pin ref-count underflow for diagnosisId=" + diagnosisId);
            }
            lease.refs--;
            shutdownIfUnused(lease);
        } finally {
            switchLock.unlock();
        }
    }

    private ClientLease leaseForHistoricalModel(String modelId) {
        if (currentLease.client.modelId().equals(modelId)) return currentLease;
        // 先复用仍在内存中、被其他诊断钉住的同版本客户端。
        for (PinnedEntry entry : pins.values()) {
            if (entry.lease().client.modelId().equals(modelId) && !entry.lease().shutdown) return entry.lease();
        }
        LlmClient client = factory.create(modelId);
        if (!client.healthy()) {
            try { client.shutdown(Duration.ZERO); } catch (RuntimeException ignored) {}
            throw new IllegalStateException("historical LLM client unhealthy: " + modelId);
        }
        ClientLease lease = new ClientLease(client);
        lease.retired = true; // 仅为旧 Diagnosis 恢复，不成为 current；最后一个引用释放后自动关闭。
        return lease;
    }

    /** Runtime（运行时）整体关闭时，不再等待 diagnosis pin；所有实际客户端只关闭一次。 */
    @Override
    public void close() {
        switchLock.lock();
        try {
            if (closed) return;
            closed = true;
            java.util.Set<ClientLease> leases = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            leases.add(currentLease);
            for (PinnedEntry entry : pins.values()) leases.add(entry.lease());
            pins.clear();
            for (ClientLease lease : leases) shutdownOnce(lease);
        } finally {
            switchLock.unlock();
        }
    }

    private void shutdownIfUnused(ClientLease lease) {
        if (lease.retired && lease.refs == 0) shutdownOnce(lease);
    }

    private void shutdownOnce(ClientLease lease) {
        if (lease == null || lease.shutdown) return;
        lease.shutdown = true;
        try {
            lease.client.shutdown(graceShutdown);
        } catch (RuntimeException closeFailure) {
            // 资源回收失败不能把已经完成的 model switch（模型切换）改写成“切换失败”；
            // current 已经原子指向新实例，只做独立告警，避免事实语义倒置。
            alertPort.warning("llm.shutdown.failed",
                "LLM client 关闭失败，model=" + lease.client.modelId() + "，原因=" + closeFailure.getMessage());
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("LLM registry is closed");
    }
}
