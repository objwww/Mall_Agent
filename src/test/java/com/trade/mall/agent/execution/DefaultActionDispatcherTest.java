package com.trade.mall.agent.execution;

import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.ConsumedApproval;
import com.trade.mall.agent.approval.infrastructure.InMemoryApprovalRepository;
import com.trade.mall.agent.approval.infrastructure.InMemoryAuthorizationPort;
import com.trade.mall.agent.config.KillSwitch;
import com.trade.mall.agent.config.infrastructure.InMemoryConfigReader;
import com.trade.mall.agent.execution.application.*;
import com.trade.mall.agent.execution.domain.*;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.InMemoryAttemptSequence;
import com.trade.mall.agent.execution.infrastructure.ScriptedActionPort;
import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.ledger.EventIds;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §10-16 一一对应：D2 的验收标准全部在此复现。
 * 没有 Mockito（Maven Central 被墙），用 ScriptedActionPort 的调用计数
 * 等价替代 {@code verify(actionPort, times(1))}。
 */
class DefaultActionDispatcherTest {

    static final long NOW = 1_700_000_000_000L;
    InMemoryEventLedger ledger;
    InMemoryActionExecutionRepository repo;
    ExecutionApplicationService svc;
    InMemoryConfigReader configReader;
    ScriptedActionPort actionPort;
    DefaultActionDispatcher dispatcher;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        repo = new InMemoryActionExecutionRepository(ledger);
        svc = new ExecutionApplicationService(repo, () -> NOW);
        configReader = new InMemoryConfigReader().set(true);
        actionPort = new ScriptedActionPort();
        dispatcher = new DefaultActionDispatcher(new KillSwitch(configReader), svc, ledger, actionPort,
            new InMemoryAttemptSequence());
    }

    private ExecutionState state(String op) { return repo.load(OperationId.of(op)).orElseThrow().state(); }

    // D4：ConsumedApproval 构造函数收紧为包私有之后，测试也只能像生产代码一样
    // 走 ApprovalGate 的 request→grant→consume——approvalId 借用 op（同一测试方法内
    // 每个 op 只用一次，足够唯一），每次调用都是一条独立、真实生效的批准。
    private ConsumedApproval approval(String op) { return approval(op, "h"); }
    private ConsumedApproval approval(String op, String hash) {
        var approvalLedger = new InMemoryEventLedger();
        var approvalRepo = new InMemoryApprovalRepository(approvalLedger);
        var authPort = new InMemoryAuthorizationPort();
        authPort.authorize("system", op);
        var gate = new ApprovalGate(approvalRepo, authPort, () -> NOW);
        gate.request(op, op, "v1", hash);
        gate.grant(op, "system");
        return gate.consume(op, "v1", hash);
    }

    private ActionCommand command(String op) { return new ActionCommand(op, "REFUND", "{}", "h"); }

    @Test void happy_path_dispatches_once_and_settles_success() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        actionPort.scriptOutcome("op", new PortOutcome.Success("wx-ref-001"));

        var outcome = dispatcher.dispatch(approval("op"), command("op"));

        assertInstanceOf(DispatchOutcome.Succeeded.class, outcome);
        assertEquals(ExecutionState.SUCCEEDED, state("op"));
        assertEquals(1, actionPort.callCount("op"));
        assertTrue(ledger.exists(EventIds.attemptDispatching("op", 1)),
            "先记录后发出：DISPATCHING 事件必须在 execute() 之前落盘");
    }

    @Test void timeout_must_not_retry() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        actionPort.scriptThrow("op", new RuntimeException("HTTP call failed", new SocketTimeoutException("read timed out")));

        var outcome = dispatcher.dispatch(approval("op"), command("op"));

        assertInstanceOf(DispatchOutcome.Unknown.class, outcome, "超时不判失败，也不判成功——落 Unknown");
        assertEquals(ExecutionState.UNKNOWN, state("op"));
        assertEquals(1, actionPort.callCount("op"), "verify(actionPort, times(1))：绝不自动重试");
    }

    @Test void connect_exception_maps_to_blocked_not_unknown() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        actionPort.scriptThrow("op", new RuntimeException("HTTP call failed", new ConnectException("refused")));

        var outcome = dispatcher.dispatch(approval("op"), command("op"));

        assertInstanceOf(DispatchOutcome.Blocked.class, outcome);
        assertEquals(ExecutionState.BLOCKED, state("op"), "T13：确定未发出，不能被当成说不清的 UNKNOWN");
        assertEquals(1, actionPort.callCount("op"));
    }

    @Test void not_configured_message_F001_maps_to_blocked() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        actionPort.scriptOutcome("op", new PortOutcome.Unavailable("该退款渠道未配置"));

        var outcome = dispatcher.dispatch(approval("op"), command("op"));

        assertInstanceOf(DispatchOutcome.Blocked.class, outcome);
        assertEquals(ExecutionState.BLOCKED, state("op"), "F-001 修复：未配置不再被误判为 UNKNOWN");
    }

    @Test void kill_switch_closed_blocks_without_touching_action_port() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        configReader.set(false);

        var outcome = dispatcher.dispatch(approval("op"), command("op"));

        assertInstanceOf(DispatchOutcome.Blocked.class, outcome);
        assertEquals(ExecutionState.BLOCKED, state("op"));
        assertEquals(0, actionPort.callCount("op"), "KillSwitch 关闭时 actionPort 绝不能被调用");
        assertFalse(ledger.exists(EventIds.attemptDispatching("op", 1)), "根本没有产生 Attempt");
    }

    @Test void unreadable_config_blocks_without_touching_action_port() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        configReader.breakReading();

        dispatcher.dispatch(approval("op"), command("op"));

        assertEquals(0, actionPort.callCount("op"));
    }

    @Test void approval_params_mismatch_is_rejected_before_any_write_INV_APPR_001() {
        repo.create(ActionExecution.create(OperationId.of("op")));

        assertThrows(ApprovalBindingException.class, () ->
            dispatcher.dispatch(approval("op", "hash-A"),
                new ActionCommand("op", "REFUND", "{}", "hash-B")));

        assertEquals(ExecutionState.PENDING, state("op"), "拒绝必须发生在任何状态写入之前");
        assertEquals(0, actionPort.callCount("op"));
    }

    @Test void approval_operation_id_mismatch_is_rejected() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        assertThrows(ApprovalBindingException.class, () ->
            dispatcher.dispatch(approval("other-op", "h"), command("op")));
    }
}

