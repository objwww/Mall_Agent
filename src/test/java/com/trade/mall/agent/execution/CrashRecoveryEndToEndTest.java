package com.trade.mall.agent.execution;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.application.TransitionCommand;
import com.trade.mall.agent.execution.domain.*;
import com.trade.mall.agent.execution.infrastructure.*;
import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.execution.recovery.DefaultCrashRecoveryScanner;
import com.trade.mall.agent.execution.reconcile.ReconcileScheduler;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §32、M-EXEC-05-recovery.md §8.1 一一对应。
 *
 * <p><b>这一个测试就是整个项目的论点</b>：注入"渠道退款成功但本地未落库"，模拟进程崩溃，
 * 重启进入 UNKNOWN，对账查询收敛为 SUCCEEDED——全程渠道只被要求退款一次。
 * 它在 D3（B3）结束时就能跑通，不需要 LLM、不需要证据链、不需要编排。</p>
 */
class CrashRecoveryEndToEndTest {

    static final long NOW = 1_700_000_000_000L;

    @Test void crash_after_dispatch_converges_without_duplicate_refund() {
        var ledger = new InMemoryEventLedger();
        var repo = new InMemoryActionExecutionRepository(ledger);
        var svc = new ExecutionApplicationService(repo, () -> NOW);
        var alertPort = new InMemoryAlertPort();
        var reconcileQueue = new InMemoryReconcileQueue();
        var hangingSource = new InMemoryHangingExecutionSource(repo, reconcileQueue);
        var scanner = new DefaultCrashRecoveryScanner(hangingSource, svc, reconcileQueue, alertPort, () -> NOW);
        var actionPort = new ScriptedActionPort();
        var reconcileScheduler = new ReconcileScheduler(svc, actionPort, reconcileQueue, alertPort, () -> NOW);

        String op = "op-e2e";
        repo.create(ActionExecution.create(OperationId.of(op)));

        // ① 先记录：DISPATCH 提交，Attempt.Dispatching 落盘（先于任何真实调用）。
        svc.transition(TransitionCommand.of(op, TransitionTrigger.DISPATCH, TransitionContext.of(1, "go")));

        // ② 真正发出：渠道确实收到并执行了退款——这是一次真实副作用。
        actionPort.scriptOutcome(op, new PortOutcome.Success("wx-ref-e2e"));
        actionPort.execute(new ActionCommand(op, "REFUND", "{}", "h"));

        // ③ 进程在这里崩溃：dispatcher 本该紧接着提交的 transition(ACK_SUCCESS) 永远没有机会执行。
        assertEquals(ExecutionState.DISPATCHED, repo.load(OperationId.of(op)).orElseThrow().state(),
            "本地状态仍卡在 DISPATCHED——这就是崩溃窗口");

        // —— 模拟重启：崩溃恢复扫描 ——
        var recovery = scanner.scan();
        assertEquals(1, recovery.recovered());
        assertEquals(ExecutionState.UNKNOWN, repo.load(OperationId.of(op)).orElseThrow().state());

        // —— 对账收敛：真正问一次渠道 ——
        var reconcile = reconcileScheduler.runDue(10);
        assertEquals(1, reconcile.resolved());
        assertEquals(ExecutionState.SUCCEEDED, repo.load(OperationId.of(op)).orElseThrow().state());

        // —— 核心论点 ——
        assertEquals(1, actionPort.callCount(op), "渠道只被要求退款一次，绝无重复退款");
        assertEquals(1, ledger.countOfType(op, "Attempt.Dispatching"), "事件序列中不得出现第二次 Dispatching");
        assertTrue(actionPort.queryCallCount(op) >= 1, "对账确实查询了渠道，不是凭空判成功");
    }
}

