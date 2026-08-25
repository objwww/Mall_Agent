package com.trade.mall.agent.runtime;

import com.trade.mall.agent.alert.AlertPort;
import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.ApprovalRepository;
import com.trade.mall.agent.approval.AuthorizationPort;
import com.trade.mall.agent.approval.infrastructure.JdbcApprovalRepository;
import com.trade.mall.agent.config.KillSwitch;
import com.trade.mall.agent.evidence.application.EvidenceCollectionService;
import com.trade.mall.agent.evidence.collector.AfterSaleEvidenceCollector;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.collector.OrderEvidenceCollector;
import com.trade.mall.agent.evidence.collector.PaymentGatewayEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundEvidenceCollector;
import com.trade.mall.agent.evidence.collector.RefundLogEvidenceCollector;
import com.trade.mall.agent.evidence.infrastructure.JdbcAfterSaleReadPort;
import com.trade.mall.agent.evidence.infrastructure.JdbcOrderReadPort;
import com.trade.mall.agent.evidence.infrastructure.JdbcRefundLogReadPort;
import com.trade.mall.agent.evidence.infrastructure.JdbcRefundReadPort;
import com.trade.mall.agent.execution.application.ActionExecutionRepository;
import com.trade.mall.agent.execution.application.DefaultActionDispatcher;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.infrastructure.JdbcActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.JdbcAttemptSequence;
import com.trade.mall.agent.execution.infrastructure.JdbcHangingExecutionSource;
import com.trade.mall.agent.execution.infrastructure.JdbcReconcileQueue;
import com.trade.mall.agent.execution.port.ActionPort;
import com.trade.mall.agent.execution.reconcile.ReconcileRunReport;
import com.trade.mall.agent.execution.reconcile.ReconcileScheduler;
import com.trade.mall.agent.execution.recovery.DefaultCrashRecoveryScanner;
import com.trade.mall.agent.execution.recovery.RecoveryReport;
import com.trade.mall.agent.ledger.EventLedger;
import com.trade.mall.agent.ledger.infrastructure.JdbcEventLedger;
import com.trade.mall.agent.llm.DefaultLlmRegistry;
import com.trade.mall.agent.llm.LlmClientFactory;
import com.trade.mall.agent.llm.LlmRegistry;
import com.trade.mall.agent.llm.PromptVersionStore;
import com.trade.mall.agent.llm.SkillVersionStore;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.llm.infrastructure.InMemorySkillVersionStore;
import com.trade.mall.agent.orchestration.DiagnosisOrchestrator;
import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisRunStore;
import com.trade.mall.agent.orchestration.DiagnosisState;
import com.trade.mall.agent.orchestration.NonFundActionExecutor;
import com.trade.mall.agent.orchestration.DurableNonFundActionExecutor;
import com.trade.mall.agent.orchestration.infrastructure.JdbcDiagnosisRunStore;
import com.trade.mall.agent.orchestration.infrastructure.JdbcNonFundExecutionStore;
import com.trade.mall.agent.orchestration.infrastructure.HttpMallOrderStatusResyncExecutor;
import com.trade.mall.agent.proposal.RemediationProposerService;
import com.trade.mall.agent.reasoning.ReasoningService;
import com.trade.mall.agent.understanding.TicketUnderstandingService;
import com.trade.mall.agent.verification.IndependentFactSource;
import com.trade.mall.agent.verification.RecoveryVerifier;
import com.trade.mall.agent.verification.infrastructure.RefundLogFactSource;
import com.trade.mall.agent.verification.infrastructure.HttpMallPaymentGatewayQuery;
import com.trade.mall.agent.verification.infrastructure.PaymentGatewayFactSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * MallAgent（商城智能代理）的最小生产组装入口。
 *
 * <p>它不定义任何新的业务规则，只把已经存在的 Domain/Application（领域/应用）组件与 V3/V4
 * JDBC（数据库）适配器用<strong>同一个 DataSource（数据库数据源）</strong>接起来，避免生产启动
 * 时遗漏某一个仓储、又悄悄退回 InMemory（内存）实现。调用方仍然负责提供真正的 LLM client
 * factory（大语言模型客户端工厂）、AuthorizationPort（审批授权）和 ActionPort（真实动作端口）；
 * EvidenceCollector（证据采集器）既可由调用方显式传入，也可使用本类的双 DataSource（数据源）构造器
 * 自动接入 mall MySQL（商城数据库）只读证据适配器。</p>
 */
public final class DurableMallAgentRuntime implements AutoCloseable {

    private static final int DEFAULT_EVIDENCE_THREADS = 4;
    private static final Duration DEFAULT_EVIDENCE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration DEFAULT_LLM_GRACE_SHUTDOWN = Duration.ofSeconds(5);

    private final ExecutorService evidencePool;
    private final DiagnosisRunStore diagnosisRunStore;
    private final DiagnosisOrchestrator orchestrator;
    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;
    private final ActionExecutionRepository executionRepository;
    private final EventLedger eventLedger;
    private final ReconcileScheduler reconcileScheduler;
    private final DefaultCrashRecoveryScanner crashRecoveryScanner;
    private final AlertPort alertPort;
    private final DefaultLlmRegistry llmRegistry;
    private final LongSupplier clock;
    private final ToolManifest toolManifest;

    /**
     * 完整生产便捷构造：同时接通退款与订单支付状态恢复。支付动作与独立验证分别走不同 endpoint；
     * Evidence（证据）仍使用 SELECT-only（只读）数据源。
     */
    public DurableMallAgentRuntime(DataSource runtimeDataSource,
                                   DataSource evidenceReadDataSource,
                                   LlmClientFactory llmClientFactory,
                                   PromptVersionStore promptVersionStore,
                                   String initialModelId,
                                   String toolSchemaVersion,
                                   AuthorizationPort authorizationPort,
                                   KillSwitch.ConfigReader configReader,
                                   ActionPort refundActionPort,
                                   URI mallBaseUri,
                                   String tenantId,
                                   String agentApiKey,
                                   AlertPort alertPort,
                                   LongSupplier clock) {
        this(runtimeDataSource, evidenceReadDataSource, llmClientFactory, promptVersionStore, legacySkills(),
            initialModelId, toolSchemaVersion, authorizationPort, configReader, refundActionPort,
            mallBaseUri, tenantId, agentApiKey, alertPort, clock);
    }

    public DurableMallAgentRuntime(DataSource runtimeDataSource, DataSource evidenceReadDataSource,
                                   LlmClientFactory llmClientFactory, PromptVersionStore promptVersionStore,
                                   SkillVersionStore skillVersionStore, String initialModelId, String toolSchemaVersion,
                                   AuthorizationPort authorizationPort, KillSwitch.ConfigReader configReader,
                                   ActionPort refundActionPort, URI mallBaseUri, String tenantId, String agentApiKey,
                                   AlertPort alertPort, LongSupplier clock) {
        this(runtimeDataSource, llmClientFactory, promptVersionStore, skillVersionStore, initialModelId, toolSchemaVersion,
            jdbcEvidenceCollectors(evidenceReadDataSource, mallBaseUri, tenantId, agentApiKey),
            jdbcVerificationSources(evidenceReadDataSource, mallBaseUri, tenantId, agentApiKey),
            authorizationPort, configReader, refundActionPort,
            new HttpMallOrderStatusResyncExecutor(mallBaseUri, tenantId, agentApiKey, Duration.ofSeconds(5)),
            alertPort, clock);
    }

    /**
     * 生产便捷构造：runtimeDataSource（运行时读写数据源）与 evidenceReadDataSource（商城证据只读数据源）
     * 分开传入。后者应使用 MySQL（数据库）层 SELECT-only（只读）账号；本构造只接当前 REFUND_STUCK
     * （退款卡住）闭环需要的 ORDER/REFUND/AFTER_SALE/REFUND_LOG（订单/退款/售后/退款日志）四类证据，
     * 同时用 REFUND_LOG 作为 Independent Verification（独立验证）事实源。
     */
    public DurableMallAgentRuntime(DataSource runtimeDataSource,
                                   DataSource evidenceReadDataSource,
                                   LlmClientFactory llmClientFactory,
                                   PromptVersionStore promptVersionStore,
                                   String initialModelId,
                                   String toolSchemaVersion,
                                   AuthorizationPort authorizationPort,
                                   KillSwitch.ConfigReader configReader,
                                   ActionPort actionPort,
                                   NonFundActionExecutor nonFundActionExecutor,
                                   AlertPort alertPort,
                                   LongSupplier clock) {
        this(runtimeDataSource, llmClientFactory, promptVersionStore, initialModelId, toolSchemaVersion,
            jdbcEvidenceCollectors(evidenceReadDataSource),
            jdbcVerificationSources(evidenceReadDataSource),
            authorizationPort, configReader, actionPort, nonFundActionExecutor, alertPort, clock);
    }

    public DurableMallAgentRuntime(DataSource dataSource,
                                   LlmClientFactory llmClientFactory,
                                   PromptVersionStore promptVersionStore,
                                   String initialModelId,
                                   String toolSchemaVersion,
                                   List<EvidenceCollector<?>> evidenceCollectors,
                                   List<IndependentFactSource> verificationSources,
                                   AuthorizationPort authorizationPort,
                                   KillSwitch.ConfigReader configReader,
                                   ActionPort actionPort,
                                   NonFundActionExecutor nonFundActionExecutor,
                                   AlertPort alertPort,
                                   LongSupplier clock) {
        this(dataSource, llmClientFactory, promptVersionStore, legacySkills(), initialModelId, toolSchemaVersion,
            evidenceCollectors, verificationSources, authorizationPort, configReader, actionPort,
            nonFundActionExecutor, alertPort, clock);
    }

    public DurableMallAgentRuntime(DataSource dataSource, LlmClientFactory llmClientFactory,
                                   PromptVersionStore promptVersionStore, SkillVersionStore skillVersionStore,
                                   String initialModelId, String toolSchemaVersion,
                                   List<EvidenceCollector<?>> evidenceCollectors,
                                   List<IndependentFactSource> verificationSources,
                                   AuthorizationPort authorizationPort, KillSwitch.ConfigReader configReader,
                                   ActionPort actionPort, NonFundActionExecutor nonFundActionExecutor,
                                   AlertPort alertPort, LongSupplier clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(llmClientFactory, "llmClientFactory");
        Objects.requireNonNull(promptVersionStore, "promptVersionStore");
        Objects.requireNonNull(skillVersionStore, "skillVersionStore");
        Objects.requireNonNull(evidenceCollectors, "evidenceCollectors");
        Objects.requireNonNull(verificationSources, "verificationSources");
        Objects.requireNonNull(authorizationPort, "authorizationPort");
        Objects.requireNonNull(configReader, "configReader");
        Objects.requireNonNull(actionPort, "actionPort");
        Objects.requireNonNull(nonFundActionExecutor, "nonFundActionExecutor");
        Objects.requireNonNull(alertPort, "alertPort");
        Objects.requireNonNull(clock, "clock");

        // 同一个 DataSource 是这个组装入口最重要的不变量：Diagnosis/Approval/Execution/Event
        // 全部进入同一数据库，Approval/Execution 仓储才能兑现“状态 + 事件同事务”的现有契约。
        JdbcEventLedger ledger = new JdbcEventLedger(dataSource);
        JdbcDiagnosisRunStore runStore = new JdbcDiagnosisRunStore(dataSource, clock);
        JdbcApprovalRepository approvalRepo = new JdbcApprovalRepository(dataSource, clock);
        JdbcActionExecutionRepository executionRepo = new JdbcActionExecutionRepository(dataSource, clock);
        JdbcReconcileQueue reconcileQueue = new JdbcReconcileQueue(dataSource);
        JdbcAttemptSequence attemptSequence = new JdbcAttemptSequence(dataSource);
        JdbcHangingExecutionSource hangingSource = new JdbcHangingExecutionSource(dataSource, clock);

        ApprovalGate gate = new ApprovalGate(approvalRepo, authorizationPort, clock);
        ExecutionApplicationService executionService = new ExecutionApplicationService(executionRepo, clock);
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
            new KillSwitch(configReader), executionService, ledger, actionPort, attemptSequence);

        this.evidencePool = Executors.newFixedThreadPool(
            Math.max(1, Math.min(DEFAULT_EVIDENCE_THREADS, Math.max(1, evidenceCollectors.size()))));

        ToolManifest toolManifest = ToolManifest.from(toolSchemaVersion, evidenceCollectors, verificationSources);
        DefaultLlmRegistry llmRegistry = new DefaultLlmRegistry(
            llmClientFactory, ledger, alertPort, promptVersionStore, skillVersionStore, toolSchemaVersion,
            toolManifest.digest(),
            initialModelId, DEFAULT_LLM_GRACE_SHUTDOWN, clock,
            diagnosisId -> runStore.find(diagnosisId).flatMap(DurableMallAgentRuntime::versionSnapshotOf));
        TicketUnderstandingService understandingService = new TicketUnderstandingService(llmRegistry, ledger, clock);
        EvidenceCollectionService evidenceService = new EvidenceCollectionService(
            evidenceCollectors, ledger, evidencePool, DEFAULT_EVIDENCE_TIMEOUT, clock);
        ReasoningService reasoningService = new ReasoningService(llmRegistry, ledger, clock);
        RemediationProposerService proposerService = new RemediationProposerService(ledger, clock);
        RecoveryVerifier recoveryVerifier = new RecoveryVerifier(verificationSources, ledger, clock);

        NonFundActionExecutor durableNonFund = new DurableNonFundActionExecutor(
            new JdbcNonFundExecutionStore(dataSource, clock), nonFundActionExecutor);

        DiagnosisOrchestrator diagnosisOrchestrator = new DiagnosisOrchestrator(
            understandingService, evidenceService, reasoningService, proposerService,
            gate, executionRepo, dispatcher, durableNonFund, recoveryVerifier,
            ledger, clock, runStore, llmRegistry::release);

        this.diagnosisRunStore = runStore;
        this.orchestrator = diagnosisOrchestrator;
        this.approvalRepository = approvalRepo;
        this.approvalGate = gate;
        this.executionRepository = executionRepo;
        this.eventLedger = ledger;
        this.reconcileScheduler = new ReconcileScheduler(
            executionService, actionPort, reconcileQueue, alertPort, clock);
        this.crashRecoveryScanner = new DefaultCrashRecoveryScanner(
            hangingSource, executionService, reconcileQueue, alertPort, clock);
        this.alertPort = alertPort;
        this.llmRegistry = llmRegistry;
        this.clock = clock;
        this.toolManifest = toolManifest;
    }

    private static SkillVersionStore legacySkills() {
        return new InMemorySkillVersionStore(VersionSnapshot.LEGACY_SKILL_VERSION, "");
    }

    private static List<EvidenceCollector<?>> jdbcEvidenceCollectors(DataSource evidenceReadDataSource) {
        DataSource readOnly = Objects.requireNonNull(evidenceReadDataSource, "evidenceReadDataSource");
        return List.of(
            new OrderEvidenceCollector(new JdbcOrderReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new RefundEvidenceCollector(new JdbcRefundReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new AfterSaleEvidenceCollector(new JdbcAfterSaleReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new RefundLogEvidenceCollector(new JdbcRefundLogReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT))
        );
    }

    private static List<EvidenceCollector<?>> jdbcEvidenceCollectors(DataSource evidenceReadDataSource,
                                                                       URI mallBaseUri, String tenantId, String apiKey) {
        DataSource readOnly = Objects.requireNonNull(evidenceReadDataSource, "evidenceReadDataSource");
        HttpMallPaymentGatewayQuery gateway = new HttpMallPaymentGatewayQuery(
            Objects.requireNonNull(mallBaseUri, "mallBaseUri"), tenantId, apiKey, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT);
        return List.of(
            new OrderEvidenceCollector(new JdbcOrderReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new RefundEvidenceCollector(new JdbcRefundReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new AfterSaleEvidenceCollector(new JdbcAfterSaleReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new RefundLogEvidenceCollector(new JdbcRefundLogReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new PaymentGatewayEvidenceCollector(gateway)
        );
    }

    private static List<IndependentFactSource> jdbcVerificationSources(DataSource evidenceReadDataSource) {
        DataSource readOnly = Objects.requireNonNull(evidenceReadDataSource, "evidenceReadDataSource");
        return List.of(new RefundLogFactSource(
            new JdbcRefundLogReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)));
    }

    private static List<IndependentFactSource> jdbcVerificationSources(DataSource evidenceReadDataSource,
                                                                        URI mallBaseUri, String tenantId, String apiKey) {
        DataSource readOnly = Objects.requireNonNull(evidenceReadDataSource, "evidenceReadDataSource");
        JdbcOrderReadPort orders = new JdbcOrderReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT);
        return List.of(
            new RefundLogFactSource(new JdbcRefundLogReadPort(readOnly, DEFAULT_EVIDENCE_DB_QUERY_TIMEOUT)),
            new PaymentGatewayFactSource(
                new HttpMallPaymentGatewayQuery(mallBaseUri, tenantId, apiKey, Duration.ofSeconds(5)), orders)
        );
    }

    private static java.util.Optional<com.trade.mall.agent.llm.VersionSnapshot> versionSnapshotOf(DiagnosisRun run) {
        if (run.finding() instanceof com.trade.mall.agent.reasoning.FindingResult.Concluded c) return java.util.Optional.of(c.versionSnapshot());
        if (run.finding() instanceof com.trade.mall.agent.reasoning.FindingResult.NoConclusion n) return java.util.Optional.of(n.versionSnapshot());
        return java.util.Optional.empty();
    }

    public DiagnosisOrchestrator orchestrator() { return orchestrator; }
    public DiagnosisRunStore diagnosisRunStore() { return diagnosisRunStore; }
    public ApprovalRepository approvalRepository() { return approvalRepository; }
    public ApprovalGate approvalGate() { return approvalGate; }
    public ActionExecutionRepository executionRepository() { return executionRepository; }
    public EventLedger eventLedger() { return eventLedger; }
    public ToolManifest toolManifest() { return toolManifest; }

    /**
     * 一次耐久恢复周期：先把“已发送但未落结果”的执行推进到 UNKNOWN（结果未知）并入对账，
     * 再处理到期对账，最后扫描所有 EXECUTING（执行中）Diagnosis（诊断）并从既有执行事实继续。
     * 最后一步不会盲目重发：是否允许继续 dispatch（发送）仍由 DiagnosisOrchestrator 原来的
     * PENDING（待执行）安全规则决定。
     */
    public RecoveryCycleReport recover(int reconcileLimit, int diagnosisLimit) {
        RecoveryReport crash = crashRecoveryScanner.scan();
        ReconcileRunReport reconcile = reconcileScheduler.runDue(reconcileLimit);

        int scanned = 0;
        int resumed = 0;
        int failed = 0;
        for (DiagnosisRun run : diagnosisRunStore.findByState(DiagnosisState.EXECUTING, diagnosisLimit)) {
            scanned++;
            try {
                orchestrator.resumeAfterExecution(run);
                resumed++;
            } catch (RuntimeException e) {
                failed++;
            }
        }
        int reasoningScanned = 0;
        int reasoningResumed = 0;
        int reasoningFailed = 0;
        for (DiagnosisRun run : diagnosisRunStore.findByState(DiagnosisState.REASONING, diagnosisLimit)) {
            reasoningScanned++;
            try { orchestrator.resumeReasoning(run.diagnosisId()); reasoningResumed++; }
            catch (RuntimeException e) { reasoningFailed++; }
        }
        if (failed > 0 || reasoningFailed > 0) {
            alertPort.critical("diagnosis.resume.failed",
                "恢复诊断失败 execution=" + failed + ", reasoning=" + reasoningFailed + "；需人工确认。");
        }
        return new RecoveryCycleReport(crash, reconcile, scanned, resumed, failed,
            reasoningScanned, reasoningResumed, reasoningFailed);
    }

    /** 扫描真正到期的 Approval（审批），并把对应等待审批 Diagnosis 同步推进到 EXPIRED（过期终态）。 */
    public int expireApprovals(int limit) {
        int expired=0;
        for (com.trade.mall.agent.approval.Approval approval : approvalRepository.findDueToExpire(clock.getAsLong(), limit)) {
            try { if (approvalGate.expireIfDue(approval.id().value())) expired++; } catch (RuntimeException ignored) { /* 并发由 CAS 决定赢家 */ }
        }
        for (DiagnosisRun run : diagnosisRunStore.findByState(DiagnosisState.AWAITING_APPROVAL, limit)) {
            if (run.approvalId()==null) continue;
            com.trade.mall.agent.approval.Approval approval=approvalRepository.load(com.trade.mall.agent.approval.ApprovalId.of(run.approvalId())).orElse(null);
            if (approval!=null && approval.state()==com.trade.mall.agent.approval.ApprovalState.EXPIRED) {
                try { orchestrator.resumeAfterApproval(run, com.trade.mall.agent.orchestration.ApprovalDecision.LET_EXPIRE, "system"); }
                catch (RuntimeException ignored) { /* 另一个实例可能已经推进 Diagnosis */ }
            }
        }
        return expired;
    }

    /** 一次常驻维护周期：审批过期 + crash/reconcile + Diagnosis 恢复。 */
    public MaintenanceReport maintain(int limit) {
        int expired=expireApprovals(limit);
        RecoveryCycleReport recovery=recover(limit,limit);
        return new MaintenanceReport(expired,recovery);
    }

    @Override
    public void close() {
        evidencePool.shutdown();
        llmRegistry.close();
    }

    public record RecoveryCycleReport(
        RecoveryReport crashRecovery, ReconcileRunReport reconcile,
        int diagnosesScanned, int diagnosesResumed, int diagnosisResumeFailures,
        int reasoningScanned, int reasoningResumed, int reasoningResumeFailures
    ) {}

    public record MaintenanceReport(int approvalsExpired, RecoveryCycleReport recovery) {}
}
