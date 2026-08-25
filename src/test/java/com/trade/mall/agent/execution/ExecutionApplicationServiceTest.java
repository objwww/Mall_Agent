package com.trade.mall.agent.execution;

import com.trade.mall.agent.execution.application.*;
import com.trade.mall.agent.execution.domain.*;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventIds;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionApplicationServiceTest {
    static final long NOW = 1_700_000_000_000L;
    InMemoryEventLedger ledger;
    InMemoryActionExecutionRepository repo;
    ExecutionApplicationService svc;
    IdempotentTransitionExecutor idem;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        repo = new InMemoryActionExecutionRepository(ledger);
        svc = new ExecutionApplicationService(repo, () -> NOW);
        idem = new IdempotentTransitionExecutor(svc, repo);
    }

    @Test void crash_replay_is_idempotent() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        // 预置一条 DISPATCHING:1，模拟上一世已提交
        ledger.append(new DomainEvent(EventIds.attemptDispatching("op", 1),
            "op", "Attempt.Dispatching", 1, "prev", NOW));
        var snap = idem.applyIdempotent(
            TransitionCommand.of("op", TransitionTrigger.DISPATCH, TransitionContext.of(1, "replay")));
        assertEquals(ExecutionState.PENDING, snap.state(), "重放被吞为幂等，状态不动");
        assertEquals(1, ledger.countOfType("op", "Attempt.Dispatching"), "事件仍只有一条");
    }

    @Test void deterministic_cas_collision() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        var a = repo.load(OperationId.of("op")).orElseThrow();
        var b = repo.load(OperationId.of("op")).orElseThrow();   // 两者都在 v0
        a.apply(TransitionTrigger.DISPATCH, TransitionContext.of(1, "A"), NOW);
        b.apply(TransitionTrigger.DISPATCH, TransitionContext.of(1, "B"), NOW);
        repo.save(a);
        assertThrows(OptimisticLockException.class, () -> repo.save(b));
        assertEquals(1, ledger.countOfType("op", "Attempt.Dispatching"));
    }
}

