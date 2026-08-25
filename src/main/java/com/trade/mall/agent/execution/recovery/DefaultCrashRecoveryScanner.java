package com.trade.mall.agent.execution.recovery;

import com.trade.mall.agent.alert.AlertPort;
import com.trade.mall.agent.execution.application.DuplicateTransitionException;
import com.trade.mall.agent.execution.application.ExecutionApplicationService;
import com.trade.mall.agent.execution.application.OptimisticLockException;
import com.trade.mall.agent.execution.application.TransitionCommand;
import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.OperationId;
import com.trade.mall.agent.execution.domain.TransitionContext;
import com.trade.mall.agent.execution.domain.TransitionTrigger;
import com.trade.mall.agent.execution.reconcile.ReconcileQueue;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * DefaultCrashRecoveryScanner —— M-EXEC-05 的落地实现。
 *
 * <p><b>本类不做任何推断。</b> 不看时间戳、不看部分写入、不看回调——一律置为
 * UNKNOWN 并交给对账。任何"聪明"的推断都可能错，而错的方向不对称：推断为
 * "没成"而实际成了 → 补偿退款 → 重复退款。见 M-EXEC-05-recovery.md §1.3。</p>
 *
 * <p><b>结构性保证 INV-UNK-002</b>：本类、以及它所在的整个 {@code execution.recovery}
 * 包，没有、也不允许有任何指向 {@code ActionPort} 的 import——恢复流程里连"重发"
 * 这个选项在代码里都不存在，不是靠 if 判断挡住的。</p>
 */
public final class DefaultCrashRecoveryScanner implements CrashRecoveryScanner {

    private static final int BATCH = 200;

    private final HangingExecutionSource source;
    private final ExecutionApplicationService service;
    private final ReconcileQueue reconcileQueue;
    private final AlertPort alertPort;
    private final LongSupplier clock;

    public DefaultCrashRecoveryScanner(HangingExecutionSource source, ExecutionApplicationService service,
                                        ReconcileQueue reconcileQueue, AlertPort alertPort, LongSupplier clock) {
        this.source = source;
        this.service = service;
        this.reconcileQueue = reconcileQueue;
        this.alertPort = alertPort;
        this.clock = clock;
    }

    @Override
    public RecoveryReport scan() {
        List<HangingExecution> batch = source.claimHanging(BATCH);
        int recovered = 0, failed = 0;
        for (HangingExecution h : batch) {
            try {
                if (recoverOne(h)) recovered++;
            } catch (Exception e) {
                failed++;
                // 单条失败不中断整批——一条坏数据不应阻止其余条目恢复，更不应阻止应用启动。
            }
        }
        if (failed > 0) {
            alertPort.critical("recovery.failed",
                "崩溃恢复失败 " + failed + " 条。悬挂执行可能已产生外部副作用，需人工确认。");
        }
        return new RecoveryReport(batch.size(), recovered, 0, failed);
    }

    /**
     * 恢复一条悬挂执行。见 M-EXEC-05-recovery.md §4 的状态×动作表。
     */
    private boolean recoverOne(HangingExecution h) {
        if (h.state() == ExecutionState.UNKNOWN) {
            // h 只是认领瞬间的快照；认领提交后另一实例可能已经完成对账并进入终态。
            // 重新确认真实状态，只在当前仍 UNKNOWN 时补排，避免旧快照制造伪队列项。
            enqueueOnlyIfCurrentlyUnknown(h.operationId());
            return false;
        }
        if (h.state() != ExecutionState.DISPATCHED) {
            // 理论上不会走到这里（HangingExecutionSource 只产出 DISPATCHED/UNKNOWN），
            // 但保留这一分支：PENDING/BLOCKED 从未发出（INV-UNK-004 保证），不处理。
            return false;
        }

        try {
            service.transition(TransitionCommand.of(h.operationId(), TransitionTrigger.CRASH_RECOVERED,
                TransitionContext.of(h.seqNo(), "CRASH_RECOVERY")));
        } catch (DuplicateTransitionException | com.trade.mall.agent.execution.domain.IllegalTransitionException
                 | OptimisticLockException raced) {
            // h 是扫描时拿到的旧快照。另一实例可能已经把真实执行推进到 UNKNOWN、BLOCKED 或终态；
            // 不能因为“我手里的旧快照曾经是 DISPATCHED”就无条件重新入对账队列。重新读取一次真实状态，
            // 只有它现在仍然是 UNKNOWN 才补排；终态/BLOCKED/PENDING/DISPATCHED 都不制造新的调度事实。
            enqueueOnlyIfCurrentlyUnknown(h.operationId());
            return false;
        }

        reconcileQueue.enqueueFirstIfAbsent(h.operationId(), clock.getAsLong());
        return true;
    }

    private void enqueueOnlyIfCurrentlyUnknown(String operationId) {
        service.snapshot(OperationId.of(operationId))
            .filter(snapshot -> snapshot.state() == ExecutionState.UNKNOWN)
            .ifPresent(snapshot -> reconcileQueue.enqueueFirstIfAbsent(operationId, clock.getAsLong()));
    }
}

