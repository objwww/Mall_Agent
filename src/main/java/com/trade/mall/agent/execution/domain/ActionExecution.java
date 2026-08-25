package com.trade.mall.agent.execution.domain;

import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.TransitionEventFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ActionExecution —— 核心域的聚合根（DDD §5.1）。
 *
 * <p><b>纯领域，零 I/O。</b> apply(trigger) 只改自身状态、产出领域事件，
 * 绝不碰数据库。这样它才能被穷举测试，也和 Temporal 的“确定性协调者”同构
 * （prior-art §1、ddd_design §6）。</p>
 *
 * 聚合边界：
 *  - ActionAttempt 在聚合内（INV-EXEC-001“至多一次成功副作用”跨 Execution 与 Attempt）。
 *  - Approval 在聚合外（人异步批准，独立生命周期），只用 operationId 相互引用。
 *
 * 守护的不变量：INV-UNK-001/002、INV-EXEC-001/002/003 —— 全部落在 apply() 这几行。
 */
public final class ActionExecution {

    private final OperationId id;
    private ExecutionState state;
    private long version;                         // 载入时的乐观锁版本
    private final List<ActionAttempt> attempts;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private ActionExecution(OperationId id, ExecutionState state, long version, List<ActionAttempt> attempts) {
        this.id = id; this.state = state; this.version = version;
        this.attempts = new ArrayList<>(attempts);
    }

    /** 工厂：新建一个 PENDING 执行。 */
    public static ActionExecution create(OperationId id) {
        return new ActionExecution(id, ExecutionState.PENDING, 0L, List.of());
    }

    /**
     * 仓储重建：从持久化状态还原聚合，含已有的 Attempt 记录。
     *
     * <p><b>D2 修复：</b> D1 版本的 rehydrate() 只接受 (id, state, version)，Attempt 列表
     * 永远从空开始——这在 D1 verified 通过是因为 D1 的用例从未跨 load/save 边界检查
     * Attempt 结局。D2 引入"结算 Attempt 结局"（settleAttemptIfApplicable）后，
     * DefaultActionDispatcher 的两段式 transition（先 DISPATCH 提交一次、
     * 再 ACK/TIMEOUT/DEPENDENCY_UNAVAILABLE 提交第二次）之间必然经过一次
     * repo.load()——如果 Attempt 不随 state/version 一起持久化，第二次转移
     * 就找不到第一次创建的 Attempt，结算逻辑永远是静默空操作。
     * 这个信号本身也是一次真实的收获：它说明 Attempt 必须和 Execution 状态
     * 在同一次持久化里原子落盘，不能是"只影响内存、reload 就丢"的临时数据——
     * 与 ddd_design §5 "Attempt 在聚合内" 的建模决策完全一致，只是 D1 的仓储实现
     * 还没把这个决策落实到底。</p>
     */
    public static ActionExecution rehydrate(OperationId id, ExecutionState state, long version,
                                             List<ActionAttempt> attempts) {
        return new ActionExecution(id, state, version, attempts);
    }

    /** 兼容旧签名（D1）：不还原 Attempt。仅供尚未升级到持久化 Attempt 的调用方过渡使用。 */
    @Deprecated
    public static ActionExecution rehydrate(OperationId id, ExecutionState state, long version) {
        return new ActionExecution(id, state, version, List.of());
    }

    /**
     * 施加一次转移。纯领域方法。
     *
     * @throws IllegalTransitionException 终态不可变，或转移不在转移表中；不改任何状态。
     */
    public void apply(TransitionTrigger trigger, TransitionContext ctx, long now) {
        // INV-EXEC-003：终态不可变，先于转移表检查，给更清晰的错误
        if (state.isTerminal()) {
            throw new IllegalTransitionException(
                "terminal state is immutable: " + id + " state=" + state + " trigger=" + trigger);
        }
        ExecutionState to = ExecutionTransitionPolicy.next(state, trigger)
            .orElseThrow(() -> new IllegalTransitionException(
                "no transition: " + state + " --" + trigger + "--> ? (operationId=" + id + ")"));

        // DISPATCH 记录一次尝试（INV-EXEC-002：Attempt 在聚合内）；
        // 其余触发若对应一次已存在的 Attempt，结算它的最终结局（D2 补：此前 AttemptOutcome
        // 只有 DISPATCHING 会被写入，SUCCESS/FAILED/UNKNOWN/NOT_SENT 定义了却从未使用——
        // 分发器落地后需要用它们区分“尝试 3 次、其中 2 次 UNKNOWN、第 3 次成功”，必须现在补上）。
        if (trigger == TransitionTrigger.DISPATCH) {
            attempts.add(new ActionAttempt(ctx.seq(), AttemptOutcome.DISPATCHING));
        } else {
            settleAttemptIfApplicable(trigger, ctx);
        }

        // 关键：这一行改状态，下一行产出事件。
        // 因为转移表里没有 UNKNOWN--DISPATCH--> 与 DISPATCHED--TIMEOUT-->FAILED，
        // INV-UNK-002 与 INV-UNK-001 在这里自动成立——它们是“表里没有”，不是“代码里判断”。
        this.state = to;
        pendingEvents.addAll(TransitionEventFactory.build(id.value(), trigger, to, ctx, now));
    }

    /**
     * 把本次转移对应的 Attempt 结算为最终结局（DISPATCHING 之外的四种终局之一）。
     * 只处理带真实 seq 的触发（ACK_SUCCESS / ACK_FAILURE / TIMEOUT / CRASH_RECOVERED /
     * T13 的 DEPENDENCY_UNAVAILABLE）；RECONCILE 系列、ESCALATE、DEPENDENCY_RESTORED
     * 是执行级事件，不结算某一次 Attempt，ctx.seq()=0
     * 也匹配不到任何 Attempt，天然被忽略。
     *
     * T13 的 DEPENDENCY_UNAVAILABLE（DISPATCHED 态触发）单独判断：必须是“离开 DISPATCHED 态”
     * 才结算为 NOT_SENT；T1 的 DEPENDENCY_UNAVAILABLE（PENDING 态触发）此时还没有 Attempt，
     * 用 state==DISPATCHED（转移前的旧状态）区分两者，而不是新增一个触发枚举。
     */
    private void settleAttemptIfApplicable(TransitionTrigger trigger, TransitionContext ctx) {
        AttemptOutcome outcome = switch (trigger) {
            case ACK_SUCCESS -> AttemptOutcome.SUCCESS;
            case ACK_FAILURE -> AttemptOutcome.FAILED;
            case TIMEOUT, CRASH_RECOVERED -> AttemptOutcome.UNKNOWN;
            case DEPENDENCY_UNAVAILABLE -> state == ExecutionState.DISPATCHED ? AttemptOutcome.NOT_SENT : null;
            default -> null;
        };
        if (outcome == null) return;
        findAttempt(ctx.seq()).ifPresent(a -> a.settle(outcome));
    }

    private Optional<ActionAttempt> findAttempt(int seq) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (attempts.get(i).seqNo() == seq) return Optional.of(attempts.get(i));
        }
        return Optional.empty();
    }

    public OperationId id() { return id; }
    public ExecutionState state() { return state; }
    public long version() { return version; }
    public List<ActionAttempt> attempts() { return List.copyOf(attempts); }
    public List<DomainEvent> pendingEvents() { return List.copyOf(pendingEvents); }
    public void clearPendingEvents() { pendingEvents.clear(); }
}

