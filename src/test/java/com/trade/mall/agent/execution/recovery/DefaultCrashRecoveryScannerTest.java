package com.trade.mall.agent.execution.recovery;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.application.TransitionCommand;
import com.trade.mall.agent.execution.domain.*;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.InMemoryHangingExecutionSource;
import com.trade.mall.agent.execution.infrastructure.InMemoryReconcileQueue;
import com.trade.mall.agent.execution.reconcile.ReconcileQueue;
import com.trade.mall.agent.execution.reconcile.ReconcileQueueEntry;
import com.trade.mall.agent.ledger.EventIds;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 与 SelfCheck §19-25 一一对应：M-EXEC-05 的核心验收点。 */
class DefaultCrashRecoveryScannerTest {

    static final long NOW = 1_700_000_000_000L;
    InMemoryEventLedger ledger;
    InMemoryActionExecutionRepository repo;
    ExecutionApplicationService svc;
    InMemoryAlertPort alertPort;
    InMemoryReconcileQueue queue;
    InMemoryHangingExecutionSource source;
    DefaultCrashRecoveryScanner scanner;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        repo = new InMemoryActionExecutionRepository(ledger);
        svc = new ExecutionApplicationService(repo, () -> NOW);
        alertPort = new InMemoryAlertPort();
        queue = new InMemoryReconcileQueue();
        source = new InMemoryHangingExecutionSource(repo, queue);
        scanner = new DefaultCrashRecoveryScanner(source, svc, queue, alertPort, () -> NOW);
    }

    private ExecutionState state(String op) { return repo.load(OperationId.of(op)).orElseThrow().state(); }
    private void dispatch(String op) {
        repo.create(ActionExecution.create(OperationId.of(op)));
        svc.transition(TransitionCommand.of(op, TransitionTrigger.DISPATCH, TransitionContext.of(1, "go")));
    }

    @Test void hanging_dispatched_recovers_to_unknown_and_enqueues() {
        dispatch("op");
        var report = scanner.scan();
        assertEquals(1, report.recovered());
        assertEquals(ExecutionState.UNKNOWN, state("op"));
        assertTrue(ledger.exists(EventIds.unknownCrash("op", 1)));
        assertTrue(queue.entry("op").isPresent());
    }

    @Test void pending_is_never_hanging_INV_UNK_004() {
        repo.create(ActionExecution.create(OperationId.of("op")));
        var report = scanner.scan();
        assertEquals(0, report.scanned());
        assertEquals(ExecutionState.PENDING, state("op"));
    }

    @Test void terminal_is_skipped() {
        dispatch("op");
        svc.transition(TransitionCommand.of("op", TransitionTrigger.ACK_SUCCESS, TransitionContext.of(1, "ok")));
        var report = scanner.scan();
        assertEquals(0, report.scanned());
    }

    @Test void unknown_not_yet_enqueued_only_gets_requeued() {
        dispatch("op");
        svc.transition(TransitionCommand.of("op", TransitionTrigger.TIMEOUT, TransitionContext.of(1, "t")));
        assertTrue(queue.entry("op").isEmpty());

        var report = scanner.scan();
        assertEquals(0, report.recovered(), "只是补排，不是新恢复");
        assertTrue(queue.entry("op").isPresent());
        assertFalse(ledger.exists(EventIds.unknownCrash("op", 1)));
    }

    @Test void idempotent_double_scan() {
        dispatch("op");
        scanner.scan();
        var second = scanner.scan();
        assertEquals(0, second.scanned());
        assertEquals(1, ledger.countOfType("op", "Execution.Unknown"));
    }

    @Test void single_bad_entry_does_not_block_the_batch() {
        ReconcileQueue poisoned = new ReconcileQueue() {
            @Override public void enqueueFirstIfAbsent(String operationId, long now) {
                if (operationId.equals("op-bad")) throw new RuntimeException("boom");
                queue.enqueueFirstIfAbsent(operationId, now);
            }
            @Override public void reschedule(String operationId, int c, long n) { queue.reschedule(operationId, c, n); }
            @Override public void remove(String operationId) { queue.remove(operationId); }
            @Override public Optional<ReconcileQueueEntry> entry(String operationId) { return queue.entry(operationId); }
            @Override public List<ReconcileQueueEntry> due(long now, int limit) { return queue.due(now, limit); }
        };
        var poisonedSource = new InMemoryHangingExecutionSource(repo, poisoned);
        var poisonedScanner = new DefaultCrashRecoveryScanner(poisonedSource, svc, poisoned, alertPort, () -> NOW);

        dispatch("op-bad");
        dispatch("op-good");

        var report = poisonedScanner.scan();
        assertEquals(1, report.failed());
        assertEquals(1, report.recovered());
        assertEquals(ExecutionState.UNKNOWN, state("op-good"));
        assertTrue(alertPort.count() >= 1);
    }

    @Test void concurrent_scan_never_double_processes_a_row() throws InterruptedException {
        int n = 8;
        for (int i = 0; i < n; i++) dispatch("op-" + i);

        var totalRecovered = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(4);
        var latch = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) pool.submit(() -> {
            try { totalRecovered.addAndGet(scanner.scan().recovered()); }
            finally { latch.countDown(); }
        });
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(n, totalRecovered.get());
        for (int i = 0; i < n; i++) {
            assertEquals(1, ledger.countOfType("op-" + i, "Execution.Unknown"));
        }
    }
}

