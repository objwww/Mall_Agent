package com.trade.mall.agent.execution.recovery;

import com.trade.mall.agent.alert.infrastructure.InMemoryAlertPort;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.application.TransitionCommand;
import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.OperationId;
import com.trade.mall.agent.execution.domain.TransitionContext;
import com.trade.mall.agent.execution.domain.TransitionTrigger;
import com.trade.mall.agent.execution.infrastructure.InMemoryActionExecutionRepository;
import com.trade.mall.agent.execution.infrastructure.InMemoryReconcileQueue;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;

import java.util.List;

/**
 * V9 不依赖 JUnit 的恢复并发反例检查：验证对账 due() 是短租约认领，而不是裸读；
 * 同时验证 CrashRecoveryScanner（崩溃恢复扫描器）拿到旧快照时会重新读取真实执行状态，
 * 终态不能被旧 DISPATCHED/UNKNOWN 快照重新塞回对账队列。
 */
public final class RecoveryConcurrencyRegressionCheck {
    private static final long NOW = 1_700_000_000_000L;
    private static int checks;

    public static void main(String[] args) {
        dueMustClaimWithShortLease();
        concurrentDueMustHaveSingleWinner();
        staleDispatchedMustNotEnqueueTerminalExecution();
        staleUnknownMustNotEnqueueTerminalExecution();
        System.out.println("V9 RECOVERY CONCURRENCY: " + checks + " checks passed");
    }

    private static void dueMustClaimWithShortLease() {
        InMemoryReconcileQueue queue = new InMemoryReconcileQueue();
        queue.enqueueFirstIfAbsent("op-lease", NOW);

        check(queue.due(NOW, 10).size() == 1, "first worker claims the due item");
        check(queue.due(NOW, 10).isEmpty(), "second worker at same time cannot claim the same item");
        check(queue.due(NOW + 29_999, 10).isEmpty(), "item stays leased before lease expiry");
        check(queue.due(NOW + 30_001, 10).size() == 1, "crashed worker's lease expires and item becomes claimable again");
    }


    private static void concurrentDueMustHaveSingleWinner() {
        InMemoryReconcileQueue queue = new InMemoryReconcileQueue();
        queue.enqueueFirstIfAbsent("op-concurrent-lease", NOW);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (!queue.due(NOW, 1).isEmpty()) winners.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        check(winners.get() == 1, "concurrent due() callers have exactly one lease winner");
    }

    private static void staleDispatchedMustNotEnqueueTerminalExecution() {
        TestEnv env = terminalExecution("op-stale-dispatched");
        HangingExecutionSource stale = limit -> List.of(
            new HangingExecution("op-stale-dispatched", ExecutionState.DISPATCHED, 1));
        InMemoryReconcileQueue queue = new InMemoryReconcileQueue();
        DefaultCrashRecoveryScanner scanner = new DefaultCrashRecoveryScanner(
            stale, env.service, queue, new InMemoryAlertPort(), () -> NOW);

        RecoveryReport report = scanner.scan();
        check(report.failed() == 0, "stale DISPATCHED race is benign, not a recovery failure");
        check(queue.entry("op-stale-dispatched").isEmpty(), "terminal execution is not re-enqueued from stale DISPATCHED snapshot");
        check(env.service.snapshot(OperationId.of("op-stale-dispatched")).orElseThrow().state() == ExecutionState.SUCCEEDED,
            "terminal state remains SUCCEEDED");
    }

    private static void staleUnknownMustNotEnqueueTerminalExecution() {
        TestEnv env = terminalExecution("op-stale-unknown");
        HangingExecutionSource stale = limit -> List.of(
            new HangingExecution("op-stale-unknown", ExecutionState.UNKNOWN, 1));
        InMemoryReconcileQueue queue = new InMemoryReconcileQueue();
        DefaultCrashRecoveryScanner scanner = new DefaultCrashRecoveryScanner(
            stale, env.service, queue, new InMemoryAlertPort(), () -> NOW);

        RecoveryReport report = scanner.scan();
        check(report.failed() == 0, "stale UNKNOWN race is benign, not a recovery failure");
        check(queue.entry("op-stale-unknown").isEmpty(), "terminal execution is not re-enqueued from stale UNKNOWN snapshot");
    }

    private static TestEnv terminalExecution(String operationId) {
        InMemoryEventLedger ledger = new InMemoryEventLedger();
        InMemoryActionExecutionRepository repo = new InMemoryActionExecutionRepository(ledger);
        ExecutionApplicationService service = new ExecutionApplicationService(repo, () -> NOW);
        OperationId op = OperationId.of(operationId);
        repo.create(ActionExecution.create(op));
        service.transition(TransitionCommand.of(operationId, TransitionTrigger.DISPATCH,
            TransitionContext.of(1, "test")));
        service.transition(TransitionCommand.of(operationId, TransitionTrigger.ACK_SUCCESS,
            TransitionContext.of(1, "test")));
        return new TestEnv(service);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private record TestEnv(ExecutionApplicationService service) {}
}

