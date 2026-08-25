package com.trade.mall.agent.execution.reconcile;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.application.TransitionCommand;
import com.trade.mall.agent.execution.domain.*;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.InMemoryReconcileQueue;
import com.trade.mall.agent.execution.infrastructure.ScriptedActionPort;
import com.trade.mall.agent.execution.port.PortOutcome;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 与 SelfCheck §26-30 一一对应：M-EXEC-04，UNKNOWN 唯一合法的出路。 */
class ReconcileSchedulerTest {

    static final long NOW = 1_700_000_000_000L;
    InMemoryEventLedger ledger;
    InMemoryActionExecutionRepository repo;
    ExecutionApplicationService svc;
    InMemoryReconcileQueue queue;
    ScriptedActionPort actionPort;
    ReconcileScheduler scheduler;
    InMemoryAlertPort alertPort;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        repo = new InMemoryActionExecutionRepository(ledger);
        svc = new ExecutionApplicationService(repo, () -> NOW);
        queue = new InMemoryReconcileQueue();
        actionPort = new ScriptedActionPort();
        alertPort = new InMemoryAlertPort();
        scheduler = new ReconcileScheduler(svc, actionPort, queue, alertPort, () -> NOW);
    }

    private ExecutionState state(String op) { return repo.load(OperationId.of(op)).orElseThrow().state(); }

    private void toUnknown(String op) {
        repo.create(ActionExecution.create(OperationId.of(op)));
        svc.transition(TransitionCommand.of(op, TransitionTrigger.DISPATCH, TransitionContext.of(1, "go")));
        svc.transition(TransitionCommand.of(op, TransitionTrigger.TIMEOUT, TransitionContext.of(1, "t")));
        queue.enqueueFirstIfAbsent(op, NOW);
    }

    @Test void success_resolves_to_succeeded_and_leaves_queue() {
        toUnknown("op");
        actionPort.scriptOutcome("op", new PortOutcome.Success("ref"));
        var run = scheduler.runDue(100);
        assertEquals(1, run.resolved());
        assertEquals(ExecutionState.SUCCEEDED, state("op"));
        assertTrue(queue.entry("op").isEmpty());
        assertEquals(0, actionPort.callCount("op"), "对账只 query，绝不 execute");
    }

    @Test void business_failure_resolves_to_failed() {
        toUnknown("op");
        actionPort.scriptOutcome("op", new PortOutcome.BusinessFailure("REJECTED", "拒绝"));
        var run = scheduler.runDue(100);
        assertEquals(1, run.resolved());
        assertEquals(ExecutionState.FAILED, state("op"));
    }

    @Test void inconclusive_stays_unknown_and_backs_off() {
        toUnknown("op");
        actionPort.scriptOutcome("op", new PortOutcome.Inconclusive("渠道也不确定"));
        var run = scheduler.runDue(100);
        assertEquals(1, run.stillUnknown());
        assertEquals(ExecutionState.UNKNOWN, state("op"));
        var entry = queue.entry("op").orElseThrow();
        assertEquals(1, entry.reconcileCount());
        assertTrue(entry.nextReconcileAt() > NOW);
    }

    @Test void query_unavailable_leaves_state_untouched_and_writes_no_event() {
        toUnknown("op");
        long before = ledger.eventsOf("op").size();
        actionPort.scriptThrow("op", new RuntimeException("HTTP call failed", new ConnectException("refused")));
        var run = scheduler.runDue(100);
        assertEquals(1, run.queryUnavailable());
        assertEquals(ExecutionState.UNKNOWN, state("op"));
        assertEquals(before, ledger.eventsOf("op").size());
        assertTrue(queue.entry("op").isPresent());
    }

    @Test void past_24h_escalates_without_another_query() {
        toUnknown("op");
        queue.reschedule("op", 3, NOW - 1000);
        // 手动把 firstUnknownAt 拨到 25 小时前：重新入队一条同 operationId、更早的 firstUnknownAt。
        long longAgo = NOW - Duration.ofHours(25).toMillis();
        // InMemoryReconcileQueue 没有直接改 firstUnknownAt 的 API（生产也不该有——它是不可变的创建时间戳），
        // 这里通过重建队列来模拟“很久以前就进入了 UNKNOWN”。
        var freshQueue = new InMemoryReconcileQueue();
        freshQueue.enqueueFirstIfAbsent("op", longAgo);
        freshQueue.reschedule("op", 3, NOW - 1000);
        var freshScheduler = new ReconcileScheduler(svc, actionPort, freshQueue, alertPort, () -> NOW);

        var run = freshScheduler.runDue(100);
        assertEquals(1, run.escalated());
        assertEquals(ExecutionState.ESCALATED, state("op"));
        assertTrue(freshQueue.entry("op").isEmpty());
        assertEquals(0, actionPort.queryCallCount("op"));
    }
}

