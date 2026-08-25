package com.trade.mall.agent.execution;

import com.trade.mall.agent.execution.application.*;
import com.trade.mall.agent.execution.domain.*;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.ledger.EventIds;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionExecutionTest {
    static final long NOW = 1_700_000_000_000L;
    InMemoryEventLedger ledger;
    InMemoryActionExecutionRepository repo;
    ExecutionApplicationService svc;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        repo = new InMemoryActionExecutionRepository(ledger);
        svc = new ExecutionApplicationService(repo, () -> NOW);
    }

    private void create(String op) { repo.create(ActionExecution.create(OperationId.of(op))); }
    private ExecutionState state(String op) { return repo.load(OperationId.of(op)).orElseThrow().state(); }

    @Test void happy_path_dispatch_timeout_reconcile_success() {
        create("op");
        svc.transition(TransitionCommand.of("op", TransitionTrigger.DISPATCH, TransitionContext.of(1, "go")));
        assertEquals(ExecutionState.DISPATCHED, state("op"));
        assertTrue(ledger.exists(EventIds.attemptDispatching("op", 1)));

        svc.transition(TransitionCommand.of("op", TransitionTrigger.TIMEOUT, TransitionContext.of(1, "t")));
        assertEquals(ExecutionState.UNKNOWN, state("op"));

        svc.transition(TransitionCommand.of("op", TransitionTrigger.RECONCILE_SUCCESS, TransitionContext.reconcile(1, "ok")));
        assertEquals(ExecutionState.SUCCEEDED, state("op"));
        assertEquals(1, ledger.countOfType("op", "Execution.Settled"));
    }

    @Test void illegal_transition_writes_nothing() {
        create("op");
        svc.transition(TransitionCommand.of("op", TransitionTrigger.DISPATCH, TransitionContext.of(1, "go")));
        svc.transition(TransitionCommand.of("op", TransitionTrigger.TIMEOUT, TransitionContext.of(1, "t")));
        int before = ledger.eventsOf("op").size();
        assertThrows(IllegalTransitionException.class, () ->
            svc.transition(TransitionCommand.of("op", TransitionTrigger.DISPATCH, TransitionContext.of(2, "redispatch"))));
        assertEquals(before, ledger.eventsOf("op").size(), "非法转移必须零写入");
        assertEquals(ExecutionState.UNKNOWN, state("op"));
    }

    @Test void terminal_is_immutable() {
        create("op");
        svc.transition(TransitionCommand.of("op", TransitionTrigger.DISPATCH, TransitionContext.of(1, "go")));
        svc.transition(TransitionCommand.of("op", TransitionTrigger.ACK_SUCCESS, TransitionContext.of(1, "ok")));
        assertEquals(ExecutionState.SUCCEEDED, state("op"));
        assertThrows(IllegalTransitionException.class, () ->
            svc.transition(TransitionCommand.of("op", TransitionTrigger.TIMEOUT, TransitionContext.of(1, "x"))));
    }
}

