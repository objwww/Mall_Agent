package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.application.ActionExecutionRepository;
import com.trade.mall.agent.execution.application.DuplicateTransitionException;
import com.trade.mall.agent.execution.application.OptimisticLockException;
import com.trade.mall.agent.execution.domain.ActionAttempt;
import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.OperationId;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventLedger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存仓储（测试/演示用；生产走 MySQL：SELECT..FOR UPDATE + UPDATE..WHERE version=?，
 * Attempt 落一张独立子表，与 Execution 状态同一本地事务提交）。
 *
 * save() 在 store 锁下原子地完成“CAS + 写事件”，精确模拟生产的单事务语义：
 *   - 版本不匹配 → OptimisticLockException，不改任何东西
 *   - 任一事件已存在 → DuplicateTransitionException，不改任何东西（整体回滚）
 *   - 否则：提交新状态 + 新 Attempt 快照 + 追加全部事件
 * 顺序很关键：先查 CAS 再查事件，使“并发转移”表现为 OptimisticLock 而非 Duplicate。
 *
 * <p><b>D2 修复：</b> Row 现在也持有 Attempt 列表快照，而不只是 (state, version)。
 * D1 版本只存 (state, version)，load() 每次都从空 Attempt 列表重建聚合——D1 的验收
 * 标准从未跨越"载入→施加→保存→再载入"这一整个边界检查 Attempt 结局，所以这个洞
 * 一直没被测出来；D2 的 DefaultActionDispatcher 天然会跨这个边界两次（先提交 DISPATCH，
 * 再提交 ACK/TIMEOUT/DEPENDENCY_UNAVAILABLE），第一次没被测出的洞就在这里现了形。</p>
 */
public final class InMemoryActionExecutionRepository implements ActionExecutionRepository {

    private record Row(ExecutionState state, long version, List<ActionAttempt> attempts) {}

    private final Map<String, Row> store = new ConcurrentHashMap<>();
    private final EventLedger ledger;
    private final Object txLock = new Object();

    public InMemoryActionExecutionRepository(EventLedger ledger) { this.ledger = ledger; }

    @Override
    public void create(ActionExecution execution) {
        Row prev = store.putIfAbsent(execution.id().value(),
                new Row(execution.state(), execution.version(), execution.attempts()));
        if (prev != null) {
            throw new IllegalStateException("execution already exists: " + execution.id());
        }
    }

    @Override
    public Optional<ActionExecution> load(OperationId id) {
        Row row = store.get(id.value());
        if (row == null) return Optional.empty();
        return Optional.of(ActionExecution.rehydrate(id, row.state(), row.version(), row.attempts()));
    }

    @Override
    public void save(ActionExecution exec) {
        synchronized (txLock) {
            Row current = store.get(exec.id().value());
            if (current == null) throw new OptimisticLockException("vanished: " + exec.id());
            // 1) 版本 CAS —— 先查，使并发转移落在 OptimisticLock 而非 Duplicate
            if (current.version() != exec.version()) {
                throw new OptimisticLockException(
                    "concurrent transition on " + exec.id()
                    + " expected v" + exec.version() + " but was v" + current.version());
            }
            // 2) 事件幂等预检 —— 任一已存在则整体回滚（崩溃重放）
            for (DomainEvent e : exec.pendingEvents()) {
                if (ledger.exists(e.eventId())) {
                    throw new DuplicateTransitionException(exec.id().value(), e.eventId());
                }
            }
            // 3) 提交：状态 + Attempt 快照 + 事件（同一临界区 = 同一“事务”）
            store.put(exec.id().value(), new Row(exec.state(), exec.version() + 1, exec.attempts()));
            for (DomainEvent e : exec.pendingEvents()) ledger.append(e);
            exec.clearPendingEvents();
        }
    }

    /**
     * 供 {@code HangingExecutionSource} 等只读扫描用（生产环境是一个独立的只读 Mapper，
     * 直接查表，不经过这层聚合仓储的 CAS 写路径——见 M-EXEC-05-recovery.md §5.3）。
     * 返回的是快照的浅拷贝，不影响仓储内部状态。
     */
    public List<ExecutionRowSnapshot> snapshotAll() {
        List<ExecutionRowSnapshot> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Row> en : store.entrySet()) {
            out.add(new ExecutionRowSnapshot(en.getKey(), en.getValue().state(), en.getValue().attempts()));
        }
        return out;
    }

    public record ExecutionRowSnapshot(String operationId, ExecutionState state, List<ActionAttempt> attempts) {}
}

