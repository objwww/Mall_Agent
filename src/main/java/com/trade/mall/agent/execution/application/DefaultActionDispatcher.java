package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.approval.ConsumedApproval;
import com.trade.mall.agent.config.KillSwitch;
import com.trade.mall.agent.execution.domain.TransitionContext;
import com.trade.mall.agent.execution.domain.TransitionTrigger;
import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.execution.port.ActionPort;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.ledger.EventIds;
import com.trade.mall.agent.ledger.EventLedger;

/**
 * DefaultActionDispatcher —— M-EXEC-03 的落地实现。
 *
 * <p><b>四道闸门，严格按此顺序：</b></p>
 * <ol>
 *   <li>KillSwitch —— 全局总闸，最便宜、最该最先查（不涉及具体批准/参数）；
 *       关闭时 actionPort 绝不会被触碰。</li>
 *   <li>批准绑定 —— operationId、paramsHash 必须与本次要发的 command 完全一致
 *       （INV-APPR-001）；不一致是调用方 bug，直接抛异常，不落任何状态。</li>
 *   <li><b>"先记录后发出"</b> —— 先 {@code transition(DISPATCH)} 落盘（Attempt.Dispatching
 *       事件 + 状态 DISPATCHED 在同一个仓储事务内提交），再运行时断言这条事件确实已经
 *       存在，然后才调用 {@code actionPort.execute()}。ArchUnit 能检查"谁调用了谁"，
 *       但检查不了"谁先谁后"这种时序关系，所以这里补一道运行时断言当"带刺的安全带"。</li>
 *   <li>结局分类 —— execute() 的返回值或抛出的异常，被收敛映射到状态机仅有的四条
 *       DISPATCHED 出边之一（T3/T4/T5(T6)/T13），绝不允许出现第五种解读。</li>
 * </ol>
 */
public final class DefaultActionDispatcher implements ActionDispatcher {

    private final KillSwitch killSwitch;
    private final ExecutionApplicationService service;
    private final EventLedger ledger;
    private final ActionPort actionPort;
    private final AttemptSequence attemptSequence;

    public DefaultActionDispatcher(KillSwitch killSwitch,
                                    ExecutionApplicationService service,
                                    EventLedger ledger,
                                    ActionPort actionPort,
                                    AttemptSequence attemptSequence) {
        this.killSwitch = killSwitch;
        this.service = service;
        this.ledger = ledger;
        this.actionPort = actionPort;
        this.attemptSequence = attemptSequence;
    }

    @Override
    public DispatchOutcome dispatch(ConsumedApproval approval, ActionCommand command) {
        String operationId = command.operationId();

        // 闸门 1：KillSwitch。关闭 → 直接落 BLOCKED（T1），actionPort 从未被调用。
        if (!killSwitch.moneyActionAllowed()) {
            service.transition(new TransitionCommand(
                com.trade.mall.agent.execution.domain.OperationId.of(operationId),
                TransitionTrigger.DEPENDENCY_UNAVAILABLE,
                TransitionContext.of(0, "kill-switch-closed")));
            return new DispatchOutcome.Blocked(operationId, "kill-switch");
        }

        // 闸门 2：批准绑定。不一致是调用方逻辑错误，不落状态、不消耗 Attempt 序号。
        if (!approval.operationId().equals(operationId)) {
            throw new ApprovalBindingException(
                "approval.operationId(" + approval.operationId() + ") != command.operationId(" + operationId + ")");
        }
        if (!approval.paramsHash().equals(command.paramsHash())) {
            throw new ApprovalBindingException(
                "approval.paramsHash != command.paramsHash for operationId=" + operationId
                + "（INV-APPR-001：批准的是一个具体动作，参数变了批准即失效）");
        }

        // 闸门 3：先记录后发出。
        int seq = attemptSequence.nextSeq(operationId);
        service.transition(TransitionCommand.of(operationId, TransitionTrigger.DISPATCH,
            TransitionContext.of(seq, "dispatch")));

        // 运行时断言：DISPATCHING 事件必须已经落盘，才允许真正对外发起调用。
        // ArchUnit 检查"依赖方向"，检查不了"谁先谁后"，这里用断言把这条时序不变量钉死。
        String dispatchingEventId = EventIds.attemptDispatching(operationId, seq);
        if (!ledger.exists(dispatchingEventId)) {
            throw new IllegalStateException(
                "INV-UNK-004 违反：DISPATCHING 事件未落盘就试图调用 actionPort — " + dispatchingEventId);
        }

        // 闸门 4（真正发出）：这一行之后，进程若崩溃，恢复扫描（D3）能且只能从 DISPATCHED
        // 态的这条 Attempt 记录里发现"曾经尝试过"，绝不会因为记录晚写而"当作从未发生"。
        PortOutcome outcome;
        try {
            outcome = actionPort.execute(command);
        } catch (Throwable t) {
            return handleThrowable(operationId, seq, t);
        }
        return handleOutcome(operationId, seq, outcome);
    }

    private DispatchOutcome handleOutcome(String operationId, int seq, PortOutcome outcome) {
        if (outcome instanceof PortOutcome.Success s) {
            service.transition(TransitionCommand.of(operationId, TransitionTrigger.ACK_SUCCESS,
                TransitionContext.of(seq, s.channelRef())));
            return new DispatchOutcome.Succeeded(operationId, s.channelRef());
        }
        if (outcome instanceof PortOutcome.BusinessFailure f) {
            service.transition(TransitionCommand.of(operationId, TransitionTrigger.ACK_FAILURE,
                TransitionContext.of(seq, f.errorCode())));
            return new DispatchOutcome.Failed(operationId, f.errorCode());
        }
        if (outcome instanceof PortOutcome.Unavailable u) {
            // T13：execute() 返回值层面就能确定"没发出"（例如适配器自己 catch 了配置异常
            // 并转成了 Unavailable，而不是让异常冒泡）。
            service.transition(TransitionCommand.of(operationId, TransitionTrigger.DEPENDENCY_UNAVAILABLE,
                TransitionContext.of(seq, u.reason())));
            return new DispatchOutcome.Blocked(operationId, u.reason());
        }
        // Inconclusive：说不清，唯一安全的落点是 TIMEOUT → UNKNOWN。绝不能因为“没有异常”
        // 就当作成功——沉默不是成功（domain_model_and_invariants.md §3.3 明确禁止的转移）。
        PortOutcome.Inconclusive i = (PortOutcome.Inconclusive) outcome;
        service.transition(TransitionCommand.of(operationId, TransitionTrigger.TIMEOUT,
            TransitionContext.of(seq, i.reason())));
        return new DispatchOutcome.Unknown(operationId, i.reason());
    }

    private DispatchOutcome handleThrowable(String operationId, int seq, Throwable t) {
        if (DependencyUnavailableClassifier.isDependencyUnavailable(t)) {
            // T13：确定没发出（ConnectException / UnknownHostException / "未配置"文案）。
            service.transition(TransitionCommand.of(operationId, TransitionTrigger.DEPENDENCY_UNAVAILABLE,
                TransitionContext.of(seq, describe(t))));
            return new DispatchOutcome.Blocked(operationId, describe(t));
        }
        // 其余一切异常（含超时）一律按"说不清"处理 → UNKNOWN。
        // 这里刻意不做第二次尝试、不做任何形式的自动重试——重试是另一层（对账/退避）的职责，
        // 分发器自己重试等于在不知道有没有副作用的情况下再制造一次副作用（ARCH-EXEC-002）。
        service.transition(TransitionCommand.of(operationId, TransitionTrigger.TIMEOUT,
            TransitionContext.of(seq, describe(t))));
        return new DispatchOutcome.Unknown(operationId, describe(t));
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : (": " + msg));
    }
}

