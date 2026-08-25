package com.trade.mall.agent.approval;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * ApprovalGate —— M-EXEC-02。批准子域的应用服务，**也是全项目唯一能构造
 * {@link ConsumedApproval} 的地方**（它与 `ConsumedApproval` 同包，能碰到那个
 * 包私有构造函数）。
 *
 * <p>四个方法对应批准的完整生命周期：{@link #request} 发起待决批准、
 * {@link #grant}/{@link #reject} 是人的决定、{@link #consume} 是资金动作真正
 * 发出前的最后一道闸门——D2 的 `DefaultActionDispatcher` 现在必须先从这里换到
 * 一个 `ConsumedApproval`，才有东西可以传给 `dispatch()`。</p>
 */
public final class ApprovalGate {

    private final ApprovalRepository repo;
    private final AuthorizationPort authorizationPort;
    public static final Duration DEFAULT_APPROVAL_TTL = Duration.ofHours(1);
    private final LongSupplier clock;
    private final long approvalTtlMillis;

    public ApprovalGate(ApprovalRepository repo, AuthorizationPort authorizationPort, LongSupplier clock) {
        this(repo, authorizationPort, clock, DEFAULT_APPROVAL_TTL);
    }

    public ApprovalGate(ApprovalRepository repo, AuthorizationPort authorizationPort, LongSupplier clock, Duration approvalTtl) {
        this.repo = repo; this.authorizationPort = authorizationPort; this.clock = clock;
        if (approvalTtl == null || approvalTtl.isZero() || approvalTtl.isNegative()) throw new IllegalArgumentException("approvalTtl must be positive");
        this.approvalTtlMillis = approvalTtl.toMillis();
    }

    /** 发起一条待决批准，三个绑定字段（operationId/actionVersion/paramsHash）自此不可变。 */
    public Approval request(String approvalId, String operationId, String actionVersion, String paramsHash) {
        return request(approvalId, operationId, "UNKNOWN", actionVersion, paramsHash);
    }

    public Approval request(String approvalId, String operationId, String actionType, String actionVersion, String paramsHash) {
        ApprovalId id = ApprovalId.of(approvalId);
        Approval existing = repo.load(id).orElse(null);
        if (existing != null) {
            boolean sameBinding = existing.operationId().equals(operationId)
                && existing.actionVersion().equals(actionVersion)
                && existing.paramsHash().equals(paramsHash)
                && ("UNKNOWN".equals(existing.actionType()) || "UNKNOWN".equals(actionType) || existing.actionType().equals(actionType));
            if (sameBinding && existing.state() == ApprovalState.PENDING) return existing;
            throw new IllegalStateException(
                "approval request already exists with different binding/state: " + approvalId
                    + " state=" + existing.state());
        }
        long now = clock.getAsLong();
        long expiresAt = Math.addExact(now, approvalTtlMillis);
        Approval approval = Approval.request(id, operationId, actionType, actionVersion, paramsHash, expiresAt, now);
        repo.create(approval);
        return approval;
    }

    /**
     * 批准。**先过授权，再过批准**（INV-APPR-004，两个独立检查点，顺序不能反）：
     * 未授权直接拒绝，连 PENDING→GRANTED 这条转移都不会去尝试。
     */
    public Approval grant(String approvalId, String approverId) {
        Approval approval = repo.load(ApprovalId.of(approvalId))
            .orElseThrow(() -> new ApprovalNotFoundException("approval not found: " + approvalId));

        if (!authorizationPort.isAuthorizedApprover(approverId, approval.operationId())) {
            throw new NotAuthorizedException(
                "approver " + approverId + " is not authorized to approve operation " + approval.operationId());
        }

        expireIfDueOrThrow(approval);
        // 允许同一个批准者重放同一个 GRANT：真实 HTTP/进程恢复场景里，批准已经落库但
        // Diagnosis checkpoint 还没来得及写入时，恢复请求必须能安全重入；不同批准者仍不接受。
        if (approval.state() == ApprovalState.GRANTED) {
            if (approverId.equals(approval.approverId())) return approval;
            throw new IllegalApprovalTransitionException(
                "approval already granted by another approver: " + approval.id());
        }

        approval.apply(ApprovalTrigger.GRANT, approverId, clock.getAsLong());
        repo.save(approval);
        return approval;
    }

    /** 拒绝。授权检查同样先于状态转移——拒绝也是一种批准决定，同样要求批准者有资格。 */
    public Approval reject(String approvalId, String approverId) {
        Approval approval = repo.load(ApprovalId.of(approvalId))
            .orElseThrow(() -> new ApprovalNotFoundException("approval not found: " + approvalId));

        if (!authorizationPort.isAuthorizedApprover(approverId, approval.operationId())) {
            throw new NotAuthorizedException(
                "approver " + approverId + " is not authorized to reject operation " + approval.operationId());
        }

        expireIfDueOrThrow(approval);
        // 与 GRANT 一样允许“决定已落库、Diagnosis checkpoint 尚未落”的同人重放。
        if (approval.state() == ApprovalState.REJECTED) {
            if (approverId.equals(approval.approverId())) return approval;
            throw new IllegalApprovalTransitionException(
                "approval already rejected by another approver: " + approval.id());
        }

        approval.apply(ApprovalTrigger.REJECT, approverId, clock.getAsLong());
        repo.save(approval);
        return approval;
    }

    /**
     * 超时未决——D8 新增。`ApprovalTransitionPolicy` 从 D4 起就支持 `PENDING--EXPIRE-->EXPIRED`/
     * `GRANTED--EXPIRE-->EXPIRED`（A3/A5，见该类），但 D4 交付时没有配一个能触发它的公开方法——
     * 没有定时扫描器会主动调用它，这条转移在 D4 是"表里存在、代码里够不到"的状态。D8
     * `orchestration.DiagnosisOrchestrator` 要驱动"批准超时未批 → `EXPIRED`" 这条诊断流程边
     * （`domain_model_and_invariants.md` §4），需要一个入口——补的是**触发方式**，不是
     * 转移规则本身，`ApprovalTransitionPolicy`/`ApprovalState`/`Approval.apply()` 一行没动。
     * 生产环境里这个方法应该被一个定时扫描"哪些 PENDING/GRANTED 已过 `expireAt`"的作业调用
     * （`Approval.Requested` 事件的 payload 里已经预留了 `expireAt` 字段，见
     * `domain_events.md` §2.4），D8 没有实现那个扫描器，这里只补最后一步"扫到了怎么让它过期"。
     */
    public Approval expire(String approvalId) {
        Approval approval = repo.load(ApprovalId.of(approvalId))
            .orElseThrow(() -> new ApprovalNotFoundException("approval not found: " + approvalId));

        if (approval.state() == ApprovalState.EXPIRED) return approval;
        approval.apply(ApprovalTrigger.EXPIRE, null, clock.getAsLong());
        repo.save(approval);
        return approval;
    }

    /**
     * 消费——真正发出资金动作之前的最后一步。
     *
     * <p>三步，顺序固定：① 找到这个 operationId 对应的批准；② 重算的
     * (actionVersion, paramsHash) 必须与批准时绑定的完全一致（INV-APPR-001，
     * 检测发生在任何状态转移之前）；③ 通过 CAS 消费（INV-APPR-003，转移表本身
     * 保证不可能消费第二次），只有到这一步才构造并返回 {@link ConsumedApproval}。</p>
     */

    /**
     * 仅用于 crash recovery（崩溃恢复）：如果批准已经 CONSUMED（已消费），但调用方能够独立证明
     * ActionExecution 仍是 PENDING（待执行，尚未记录 DISPATCH），则恢复那张一次性能力票据。
     *
     * <p>本方法不会把任何状态改成 CONSUMED，也不会让 REJECTED/EXPIRED/GRANTED 冒充已消费；
     * 是否“尚未发送”必须由执行域的 PENDING 状态证明，Approval 域自己不做跨聚合推断。</p>
     */
    public ConsumedApproval recoverConsumed(String operationId, String actionVersion, String paramsHash) {
        Approval approval = repo.findByOperationId(operationId)
            .orElseThrow(() -> new ApprovalNotFoundException("no approval for operationId: " + operationId));

        if (!approval.actionVersion().equals(actionVersion) || !approval.paramsHash().equals(paramsHash)) {
            throw new ApprovalParamsMismatchException(
                "params drifted since approval: operationId=" + operationId);
        }
        if (approval.state() != ApprovalState.CONSUMED) {
            throw new ApprovalAlreadyConsumedException(
                "approval is not recoverable as consumed: " + approval.id() + " state=" + approval.state());
        }
        return new ConsumedApproval(operationId, paramsHash);
    }

    public ConsumedApproval consume(String operationId, String actionVersion, String paramsHash) {
        Approval approval = repo.findByOperationId(operationId)
            .orElseThrow(() -> new ApprovalNotFoundException("no approval for operationId: " + operationId));

        expireIfDueOrThrow(approval);

        if (!approval.actionVersion().equals(actionVersion) || !approval.paramsHash().equals(paramsHash)) {
            throw new ApprovalParamsMismatchException(
                "params drifted since approval: operationId=" + operationId
                + " approved(actionVersion=" + approval.actionVersion() + ", paramsHash=" + approval.paramsHash() + ")"
                + " requested(actionVersion=" + actionVersion + ", paramsHash=" + paramsHash + ")");
        }

        try {
            approval.apply(ApprovalTrigger.CONSUME, null, clock.getAsLong());
        } catch (IllegalApprovalTransitionException notGrantedOrAlreadyConsumed) {
            throw new ApprovalAlreadyConsumedException(
                "approval not consumable (already consumed, rejected, expired, or never granted): "
                + approval.id() + " state=" + approval.state());
        }
        repo.save(approval);

        return new ConsumedApproval(operationId, paramsHash);
    }
    /** 调度器可调用：只有真正到期才推进 EXPIRED。 */
    public boolean expireIfDue(String approvalId) {
        Approval approval = repo.load(ApprovalId.of(approvalId)).orElse(null);
        if (approval == null || approval.state().isTerminal() || !approval.dueToExpire(clock.getAsLong())) return false;
        approval.apply(ApprovalTrigger.EXPIRE, null, clock.getAsLong());
        repo.save(approval);
        return true;
    }

    private void expireIfDueOrThrow(Approval approval) {
        if (!approval.dueToExpire(clock.getAsLong())) return;
        approval.apply(ApprovalTrigger.EXPIRE, null, clock.getAsLong());
        repo.save(approval);
        throw new ApprovalAlreadyConsumedException("approval expired before use: " + approval.id());
    }

}

