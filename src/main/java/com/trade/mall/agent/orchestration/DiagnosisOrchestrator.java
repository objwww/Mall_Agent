package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.ConsumedApproval;
import com.trade.mall.agent.evidence.AcquireState;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.application.EvidenceCollectionService;
import com.trade.mall.agent.execution.application.ActionDispatcher;
import com.trade.mall.agent.execution.application.ActionExecutionRepository;
import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.OperationId;
import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.ledger.EventLedger;
import com.trade.mall.agent.proposal.Proposal;
import com.trade.mall.agent.proposal.ParamsHashing;
import com.trade.mall.agent.proposal.RemediationProposerService;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.reasoning.ReasoningService;
import com.trade.mall.agent.understanding.Anchor;
import com.trade.mall.agent.understanding.TicketUnderstandingService;
import com.trade.mall.agent.understanding.UnderstandingResult;
import com.trade.mall.agent.verification.RecoveryVerifier;
import com.trade.mall.agent.verification.VerifyResult;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * DiagnosisOrchestrator —— `M-ORCH-01`：确定性诊断流程状态机，把 D5-D8 的能力串成
 * 一条完整链路（理解→取证→判定→提议→批准→执行→验证→收敛）。
 *
 * <p><b>这是全项目"Agent 安全边界"最直接的体现（`ADR-009`）</b>：本类完全不 import
 * `com.trade.mall.agent.llm.*` 下任何类型（`SelfCheck §90` 有穷人版 ArchUnit 断言），
 * 即使它调用的两个能力（{@link TicketUnderstandingService}/{@link ReasoningService}）
 * 内部真的用了 LLM——本类只消费它们的结果分类（`Understood`/`AnchorMissing`/`Escalated`，
 * `Concluded`/`NoConclusion`），从不触碰 `versionSnapshot()` 这类携带 LLM 类型的字段，
 * "流程往哪走"完全由 {@link DiagnosisTransitionPolicy} 这张纯数据表决定。</p>
 *
 * <p><b>两个方法而不是一个</b>：{@link #runToApproval} 跑到第一个需要外部输入的点为止
 * （要么提前终止在某个终态，要么暂停在 `AWAITING_APPROVAL` 等人决定）；
 * {@link #resumeAfterApproval} 接着跑完剩下的部分。这不是任意的 API 设计选择——
 * 一个真实系统不可能在一次方法调用里同步等待人几天后才做出的批准决定，进程早就
 * 结束了。把"人来之前"和"人来之后"拆成两次独立调用，是把这个必然的异步边界如实
 * 反映在方法签名上，而不是假装它不存在。</p>
 */
public final class DiagnosisOrchestrator {

    /** 当前最小闭环最多允许两轮“验证失败后重新取证/推理”，避免两类动作来回摆动形成无限自治循环。 */
    private static final int MAX_REASONING_ROUNDS = 2;

    private final TicketUnderstandingService understandingService;
    private final EvidenceCollectionService evidenceService;
    private final ReasoningService reasoningService;
    private final RemediationProposerService proposerService;
    private final ApprovalGate approvalGate;
    private final ActionExecutionRepository executionRepo;
    private final ActionDispatcher actionDispatcher;
    private final NonFundActionExecutor nonFundActionExecutor;
    private final RecoveryVerifier recoveryVerifier;
    private final EventLedger ledger;
    private final LongSupplier clock;
    private final DiagnosisRunStore runStore;
    private final Consumer<String> terminalResourceReleaser;

    public DiagnosisOrchestrator(TicketUnderstandingService understandingService,
                                  EvidenceCollectionService evidenceService,
                                  ReasoningService reasoningService,
                                  RemediationProposerService proposerService,
                                  ApprovalGate approvalGate,
                                  ActionExecutionRepository executionRepo,
                                  ActionDispatcher actionDispatcher,
                                  NonFundActionExecutor nonFundActionExecutor,
                                  RecoveryVerifier recoveryVerifier,
                                  EventLedger ledger,
                                  LongSupplier clock) {
        this(understandingService, evidenceService, reasoningService, proposerService, approvalGate, executionRepo,
            actionDispatcher, nonFundActionExecutor, recoveryVerifier, ledger, clock, DiagnosisRunStore.noop(), id -> {});
    }

    /**
     * 带 DiagnosisRunStore（诊断运行存储）的构造方式；生产/可恢复运行应使用这一入口。
     * 旧构造器继续保留，避免为了持久化改动 D8 所有既有装配代码。
     */
    public DiagnosisOrchestrator(TicketUnderstandingService understandingService,
                                  EvidenceCollectionService evidenceService,
                                  ReasoningService reasoningService,
                                  RemediationProposerService proposerService,
                                  ApprovalGate approvalGate,
                                  ActionExecutionRepository executionRepo,
                                  ActionDispatcher actionDispatcher,
                                  NonFundActionExecutor nonFundActionExecutor,
                                  RecoveryVerifier recoveryVerifier,
                                  EventLedger ledger,
                                  LongSupplier clock,
                                  DiagnosisRunStore runStore) {
        this(understandingService, evidenceService, reasoningService, proposerService, approvalGate, executionRepo,
            actionDispatcher, nonFundActionExecutor, recoveryVerifier, ledger, clock, runStore, id -> {});
    }

    /**
     * 生产运行时可额外传入 terminalResourceReleaser（终态资源释放回调）；它是普通 JDK Consumer（消费者），
     * 因此编排层仍然不知道 LLM（大语言模型）类型。只有终态 checkpoint（检查点）成功保存后才调用。
     */
    public DiagnosisOrchestrator(TicketUnderstandingService understandingService,
                                  EvidenceCollectionService evidenceService,
                                  ReasoningService reasoningService,
                                  RemediationProposerService proposerService,
                                  ApprovalGate approvalGate,
                                  ActionExecutionRepository executionRepo,
                                  ActionDispatcher actionDispatcher,
                                  NonFundActionExecutor nonFundActionExecutor,
                                  RecoveryVerifier recoveryVerifier,
                                  EventLedger ledger,
                                  LongSupplier clock,
                                  DiagnosisRunStore runStore,
                                  Consumer<String> terminalResourceReleaser) {
        this.understandingService = understandingService;
        this.evidenceService = evidenceService;
        this.reasoningService = reasoningService;
        this.proposerService = proposerService;
        this.approvalGate = approvalGate;
        this.executionRepo = executionRepo;
        this.actionDispatcher = actionDispatcher;
        this.nonFundActionExecutor = nonFundActionExecutor;
        this.recoveryVerifier = recoveryVerifier;
        this.ledger = ledger;
        this.clock = clock;
        this.runStore = java.util.Objects.requireNonNull(runStore, "runStore");
        this.terminalResourceReleaser = java.util.Objects.requireNonNull(terminalResourceReleaser, "terminalResourceReleaser");
    }

    /** 从工单原始文本开始，跑到第一个终态，或者暂停在 {@code AWAITING_APPROVAL}。 */
    public DiagnosisRun runToApproval(String ticketSn, String diagnosisId, String freeText) {
        DiagnosisState state = DiagnosisState.RECEIVED;
        int seq = 0;

        seq = ++seq; state = move(diagnosisId, seq, state, DiagnosisTrigger.START_UNDERSTANDING);

        UnderstandingResult understanding = understandingService.understand(ticketSn, diagnosisId, freeText);
        if (understanding instanceof UnderstandingResult.AnchorMissing || understanding instanceof UnderstandingResult.Escalated) {
            // 两种结局对编排层而言是同一件事——理解阶段没能拿到可用锚点，见类头对 D6
            // UnderstandingResult 三态与本状态机两条边不完全对齐这件事的说明。
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.ANCHOR_MISSING_DETECTED);
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.ESCALATE_TO_HUMAN);
            return checkpoint(new DiagnosisRun(ticketSn, diagnosisId, state, seq, null, null, null, null, null, null));
        }

        UnderstandingResult.Understood understood = (UnderstandingResult.Understood) understanding;
        Anchor anchor = understood.anchor();
        state = move(diagnosisId, ++seq, state, DiagnosisTrigger.ANCHOR_EXTRACTED);

        EvidenceBundle bundle = evidenceService.collect(diagnosisId, anchor.value());
        if (evidenceInsufficient(bundle)) {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.EVIDENCE_INSUFFICIENT_DETECTED);
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.ESCALATE_TO_HUMAN);
            return checkpoint(new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, null, null, null, null));
        }
        state = move(diagnosisId, ++seq, state, DiagnosisTrigger.EVIDENCE_COMPLETE);

        return reasonPlanAndContinue(ticketSn, diagnosisId, anchor, bundle, state, seq, 1, java.util.Set.of());
    }

    /**
     * NOT_RECOVERED（未恢复）之后真正开始下一轮：重新取证、重新推理、重新提议。
     * 上一轮 FindingType（诊断类型）被禁止重复，保证验证失败不会自动重放同一动作。
     */
    public DiagnosisRun resumeReasoning(String diagnosisId) {
        DiagnosisRun paused = runStore.find(diagnosisId)
            .orElseThrow(() -> new IllegalStateException("diagnosis checkpoint not found: " + diagnosisId));
        if (paused.state() != DiagnosisState.REASONING
                || !(paused.verifyResult() instanceof VerifyResult.NotRecovered)) {
            throw new IllegalStateException("diagnosis is not waiting for re-reasoning: state=" + paused.state());
        }
        int completedRound = paused.evidenceBundle() == null ? 1 : paused.evidenceBundle().round();
        if (completedRound >= MAX_REASONING_ROUNDS) {
            // 第二轮动作仍被独立验证为未恢复时，不再让模型在故障类型之间无限来回；
            // 复用现有 REASONING -> NO_CONCLUSION -> ESCALATED_HUMAN 状态边界，直接交给人工。
            DiagnosisState state = move(diagnosisId, paused.seq() + 1, paused.state(), DiagnosisTrigger.NO_CONCLUSION_REACHED);
            state = move(diagnosisId, paused.seq() + 2, state, DiagnosisTrigger.ESCALATE_TO_HUMAN);
            return checkpoint(new DiagnosisRun(paused.ticketSn(), diagnosisId, state, paused.seq() + 2,
                paused.anchor(), paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.approvalId(), paused.verifyResult()));
        }
        int round = completedRound + 1;
        EvidenceBundle fresh = evidenceService.collect(diagnosisId, round, paused.anchor().value());
        if (evidenceInsufficient(fresh)) {
            DiagnosisState state = move(diagnosisId, paused.seq() + 1, paused.state(), DiagnosisTrigger.NO_CONCLUSION_REACHED);
            state = move(diagnosisId, paused.seq() + 2, state, DiagnosisTrigger.ESCALATE_TO_HUMAN);
            return checkpoint(new DiagnosisRun(paused.ticketSn(), diagnosisId, state, paused.seq() + 2,
                paused.anchor(), fresh, null, null, null, paused.verifyResult()));
        }
        java.util.Set<com.trade.mall.agent.reasoning.FindingType> forbidden = java.util.Set.of();
        if (paused.finding() instanceof FindingResult.Concluded previous) {
            forbidden = java.util.Set.of(previous.findingType());
        }
        return reasonPlanAndContinue(paused.ticketSn(), diagnosisId, paused.anchor(), fresh,
            paused.state(), paused.seq(), round, forbidden);
    }

    private DiagnosisRun reasonPlanAndContinue(String ticketSn, String diagnosisId, Anchor anchor,
                                                EvidenceBundle bundle, DiagnosisState state, int seq, int round,
                                                java.util.Set<com.trade.mall.agent.reasoning.FindingType> forbiddenTypes) {
        FindingResult finding = reasoningService.reason(diagnosisId, bundle, round, forbiddenTypes, historicalExperiences(diagnosisId));
        if (finding instanceof FindingResult.NoConclusion) {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.NO_CONCLUSION_REACHED);
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.ESCALATE_TO_HUMAN);
            return checkpoint(new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, finding, null, null, null));
        }
        FindingResult.Concluded concluded = (FindingResult.Concluded) finding;
        state = move(diagnosisId, ++seq, state, DiagnosisTrigger.FINDING_CONCLUDED);

        if (!proposerService.requiresAction(concluded.findingType())) {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.NO_ACTION_NEEDED);
            return checkpoint(new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, finding, null, null, null));
        }

        Proposal proposal = proposerService.propose(diagnosisId, anchor, concluded, bundle, round);
        state = move(diagnosisId, ++seq, state, DiagnosisTrigger.PROPOSAL_CREATED);

        if (proposal.actionType().requiresApproval()) {
            String approvalId = proposal.proposalId() + ":APPROVAL";
            approvalGate.request(approvalId, proposal.operationId(), proposal.actionType().name(), proposal.actionType().actionVersion(), proposal.paramsHash());
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.REQUIRES_APPROVAL);
            return checkpoint(new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, finding, proposal, approvalId, null));
        }

        state = move(diagnosisId, ++seq, state, DiagnosisTrigger.NO_APPROVAL_NEEDED);
        DiagnosisRun executing = checkpoint(new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle,
            finding, proposal, null, null));
        return checkpoint(executeAndVerify(executing.ticketSn(), diagnosisId, executing.anchor(), executing.evidenceBundle(),
            executing.finding(), executing.proposal(), executing.state(), executing.seq()));
    }

    /** 根据持久化的 diagnosisId（诊断编号）恢复并处理审批决定。 */
    public DiagnosisRun resumeAfterApproval(String diagnosisId, ApprovalDecision decision, String approverId) {
        DiagnosisRun paused = runStore.find(diagnosisId)
            .orElseThrow(() -> new IllegalStateException("diagnosis checkpoint not found: " + diagnosisId));
        return resumeAfterApproval(paused, decision, approverId);
    }

    /** 人（或系统判定超时）对一次暂停在 {@code AWAITING_APPROVAL} 的诊断做出的决定。 */
    public DiagnosisRun resumeAfterApproval(DiagnosisRun paused, ApprovalDecision decision, String approverId) {
        if (!paused.isPausedAtApproval()) {
            throw new IllegalStateException("diagnosis is not paused at AWAITING_APPROVAL: state=" + paused.state());
        }
        String diagnosisId = paused.diagnosisId();
        int seq = paused.seq();
        DiagnosisState state = paused.state();

        switch (decision) {
            case GRANT -> {
                approvalGate.grant(paused.approvalId(), approverId);
                state = move(diagnosisId, ++seq, state, DiagnosisTrigger.APPROVAL_GRANTED);
                // 先把 Diagnosis（诊断）推进到 EXECUTING（执行中）并持久化，再碰资金执行边界；
                // crash 发生在后面的任何位置，重启后都不会退回“再次批准”的旧检查点。
                DiagnosisRun executing = checkpoint(new DiagnosisRun(paused.ticketSn(), diagnosisId, state, seq,
                    paused.anchor(), paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.approvalId(), null));
                return checkpoint(executeAndVerify(executing.ticketSn(), diagnosisId, executing.anchor(), executing.evidenceBundle(),
                    executing.finding(), executing.proposal(), executing.state(), executing.seq()));
            }
            case REJECT -> {
                approvalGate.reject(paused.approvalId(), approverId);
                state = move(diagnosisId, ++seq, state, DiagnosisTrigger.APPROVAL_REJECTED);
                return checkpoint(new DiagnosisRun(paused.ticketSn(), diagnosisId, state, seq, paused.anchor(),
                    paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.approvalId(), null));
            }
            case LET_EXPIRE -> {
                approvalGate.expire(paused.approvalId());
                state = move(diagnosisId, ++seq, state, DiagnosisTrigger.APPROVAL_EXPIRED);
                return checkpoint(new DiagnosisRun(paused.ticketSn(), diagnosisId, state, seq, paused.anchor(),
                    paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.approvalId(), null));
            }
            default -> throw new IllegalStateException("unreachable: unknown ApprovalDecision " + decision);
        }
    }

    private DiagnosisRun executeAndVerify(String ticketSn, String diagnosisId, Anchor anchor, EvidenceBundle bundle,
                                           FindingResult finding, Proposal proposal, DiagnosisState state, int seq) {
        String operationId = proposal.operationId();
        ExecutionState terminalState;

        if (proposal.actionType().requiresApproval()) {
            // 资金动作恢复规则很简单：只有 PENDING（待执行）才可能继续 dispatch（发送）；
            // DISPATCHED/UNKNOWN/BLOCKED/终态都绝不重发，只读取已有执行状态。
            ActionExecution existing = executionRepo.load(OperationId.of(operationId)).orElse(null);
            if (existing == null) {
                executionRepo.create(ActionExecution.create(OperationId.of(operationId)));
                existing = executionRepo.load(OperationId.of(operationId)).orElseThrow();
            }

            if (existing.state() == ExecutionState.PENDING) {
                ConsumedApproval consumed;
                try {
                    consumed = approvalGate.consume(operationId, proposal.actionType().actionVersion(), proposal.paramsHash());
                } catch (com.trade.mall.agent.approval.ApprovalAlreadyConsumedException crashAfterConsume) {
                    // 能走到这里的前提是 execution 仍然 PENDING：执行域已经证明没有 DISPATCH 记录，
                    // 因此恢复此前已经消费的批准能力票据是安全的；如果已 DISPATCHED，本分支不会进入。
                    consumed = approvalGate.recoverConsumed(operationId, proposal.actionType().actionVersion(), proposal.paramsHash());
                }
                ActionCommand command = new ActionCommand(operationId, proposal.actionType().name(),
                    ParamsHashing.canonicalJson(proposal.params()), proposal.paramsHash());
                actionDispatcher.dispatch(consumed, command);
            }
            terminalState = executionRepo.load(OperationId.of(operationId)).orElseThrow().state();
        } else {
            // 非资金动作：轻量执行器，不经过资金安全专用的状态机（见 NonFundActionExecutor 类头）。
            try {
                nonFundActionExecutor.execute(operationId, proposal.actionType(), proposal.params());
                terminalState = ExecutionState.SUCCEEDED;
            } catch (NonFundActionBusinessFailureException failed) {
                terminalState = ExecutionState.FAILED;
            } catch (RuntimeException inconclusive) {
                // 非资金动作虽然可幂等重放，但 timeout/连接断开也不能被伪装成 FAILED；
                // 保持 Diagnosis 在 EXECUTING，下一维护周期由 DurableNonFundActionExecutor 根据 PENDING 重放。
                terminalState = ExecutionState.UNKNOWN;
            }
        }

        return continueAfterExecutionState(ticketSn, diagnosisId, anchor, bundle, finding, proposal, state, seq, terminalState);
    }

    /**
     * Reconcile（对账）或依赖恢复把 ActionExecution（动作执行）推进之后，从这里继续诊断；
     * 本方法只读取既有 execution 状态，绝不会再次 dispatch（发送）同一个资金动作。
     */
    public DiagnosisRun resumeAfterExecution(String diagnosisId) {
        DiagnosisRun paused = runStore.find(diagnosisId)
            .orElseThrow(() -> new IllegalStateException("diagnosis checkpoint not found: " + diagnosisId));
        return resumeAfterExecution(paused);
    }

    public DiagnosisRun resumeAfterExecution(DiagnosisRun paused) {
        if (paused.state() != DiagnosisState.EXECUTING) {
            throw new IllegalStateException("diagnosis is not waiting for execution convergence: state=" + paused.state());
        }

        // 如果 crash 发生在 EXECUTING checkpoint 已保存、ActionExecution 还没创建之间，
        // “没有 execution 记录”本身证明资金动作尚未发出，可以安全进入现有执行路径；
        // executeAndVerify 内部对 PENDING/CONSUMED 也做了可重入恢复。
        if (paused.proposal().actionType().requiresApproval()) {
            String operationId = paused.proposal().operationId();
            ActionExecution execution = executionRepo.load(OperationId.of(operationId)).orElse(null);
            if (execution == null || execution.state() == ExecutionState.PENDING) {
                return checkpoint(executeAndVerify(paused.ticketSn(), paused.diagnosisId(), paused.anchor(),
                    paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.state(), paused.seq()));
            }
            return checkpoint(continueAfterExecutionState(paused.ticketSn(), paused.diagnosisId(), paused.anchor(),
                paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.state(), paused.seq(), execution.state()));
        }

        // 非资金动作由 DurableNonFundActionExecutor（耐久非资金执行器）以 operationId 保护；
        // PENDING crash 可依靠动作自身幂等语义安全重放，SUCCEEDED/FAILED 则不会重复外部调用。
        return checkpoint(executeAndVerify(paused.ticketSn(), paused.diagnosisId(), paused.anchor(),
            paused.evidenceBundle(), paused.finding(), paused.proposal(), paused.state(), paused.seq()));
    }

    private DiagnosisRun continueAfterExecutionState(String ticketSn, String diagnosisId, Anchor anchor, EvidenceBundle bundle,
                                                      FindingResult finding, Proposal proposal, DiagnosisState state, int seq,
                                                      ExecutionState executionState) {
        if (executionState == ExecutionState.ESCALATED) {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.EXECUTION_ESCALATED);
            return new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, finding, proposal, null, null);
        }
        if (!executionState.isTerminal()) {
            // UNKNOWN/BLOCKED（结果未知/依赖阻塞）不是程序异常：保留在 EXECUTING，等待对账/恢复后再次调用 resumeAfterExecution。
            return new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, finding, proposal, null, null);
        }

        state = move(diagnosisId, ++seq, state, DiagnosisTrigger.EXECUTION_TERMINAL);
        String operationId = proposal.operationId();
        VerifyResult verifyResult = recoveryVerifier.verify(operationId, anchor.value(),
            proposal.actionType(), proposal.verificationPlan());

        if (verifyResult instanceof VerifyResult.Recovered) {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.VERIFY_RECOVERED);
        } else if (verifyResult instanceof VerifyResult.NotRecovered) {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.VERIFY_NOT_RECOVERED);
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.REOPEN_REASONING);
        } else {
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.VERIFY_SOURCE_UNAVAILABLE);
            state = move(diagnosisId, ++seq, state, DiagnosisTrigger.ESCALATE_TO_HUMAN);
        }

        return new DiagnosisRun(ticketSn, diagnosisId, state, seq, anchor, bundle, finding, proposal, null, verifyResult);
    }

    /**
     * G-005 最小经验检索：复用最近完成的 Diagnosis checkpoint（诊断检查点），不引入向量库。
     * 摘要只进入 LLM 的“历史经验”段落，Evidence 白名单仍只来自本轮 bundle。
     */
    private java.util.List<String> historicalExperiences(String currentDiagnosisId) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (DiagnosisRun old : runStore.recentTerminal(5)) {
            if (currentDiagnosisId.equals(old.diagnosisId())) continue;
            if (!(old.finding() instanceof FindingResult.Concluded concluded)) continue;
            String action = old.proposal() == null ? "NO_ACTION（无需动作）" : old.proposal().actionType().name();
            String verify = old.verifyResult() == null ? "NONE" : old.verifyResult().getClass().getSimpleName();
            out.add("sourceDiagnosisId=" + old.diagnosisId() + ", findingType=" + concluded.findingType()
                + ", action=" + action + ", finalState=" + old.state() + ", verify=" + verify);
        }
        return java.util.List.copyOf(out);
    }

    /** 关键证据缺失的保守近似：至少需要一条真实存在的证据，EMPTY 不能支撑模型下结论。 */
    private boolean evidenceInsufficient(EvidenceBundle bundle) {
        if (bundle.items().isEmpty()) return true;
        for (Evidence e : bundle.items()) {
            if (e.acquireState() == AcquireState.PRESENT) return false;
        }
        return true;
    }

    private DiagnosisRun checkpoint(DiagnosisRun run) {
        runStore.save(run);
        if (run.isTerminal()) {
            // 先 durable checkpoint（耐久保存），再释放在途资源；保存失败时不能提前丢掉恢复所需的版本 pin。
            terminalResourceReleaser.accept(run.diagnosisId());
        }
        return run;
    }

    private DiagnosisState move(String diagnosisId, int seq, DiagnosisState from, DiagnosisTrigger trigger) {
        DiagnosisState to = DiagnosisTransitionPolicy.apply(from, trigger);
        ledger.append(DiagnosisEvents.stateChanged(diagnosisId, seq, trigger.name(), to.name(), clock.getAsLong()));
        return to;
    }
}
