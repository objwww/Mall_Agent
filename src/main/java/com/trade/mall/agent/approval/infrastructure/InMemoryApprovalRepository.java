package com.trade.mall.agent.approval.infrastructure;

import com.trade.mall.agent.approval.Approval;
import com.trade.mall.agent.approval.ApprovalDuplicateTransitionException;
import com.trade.mall.agent.approval.ApprovalId;
import com.trade.mall.agent.approval.ApprovalOptimisticLockException;
import com.trade.mall.agent.approval.ApprovalRepository;
import com.trade.mall.agent.approval.ApprovalState;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventLedger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存批准仓储（测试/演示用；生产走 MySQL：approval 表 + `UNIQUE(approvalId)` 主键，
 * 事件与状态同一本地事务提交，与 `InMemoryActionExecutionRepository` 完全同构，D1 起
 * 沿用的同一套模式）。
 *
 * <p>额外维护一个 operationId → approvalId 的二级索引，服务
 * {@link ApprovalRepository#findByOperationId}——生产实现是 approval 表上
 * `operationId` 列的一个索引（一个 operationId 在有效期内只应有一条非终态批准，
 * 这里用"最后一次 create() 覆盖索引"简化模拟，足够 D4 的验收范围）。</p>
 *
 * <p>save() 与 execution 域的 save() 同一临界区顺序：先 CAS 版本号，再查事件幂等，
 * 最后一次性提交状态 + 追加事件——保证"批准的状态转移"与"批准事件写入账本"
 * 对外表现为同一个不可分割的动作，与 `ActionExecution` 共用同一个 `EventLedger`
 * 实例（ADR-008：同库不跨 MQ，一次真实的本地事务即可覆盖两个聚合）。</p>
 */
public final class InMemoryApprovalRepository implements ApprovalRepository {

    private record Row(String operationId, String actionType, String actionVersion, String paramsHash, long expiresAt,
                        ApprovalState state, String approverId, long version) {}

    private final Map<String, Row> store = new ConcurrentHashMap<>();
    private final Map<String, String> byOperationId = new ConcurrentHashMap<>();
    private final EventLedger ledger;
    private final Object txLock = new Object();

    public InMemoryApprovalRepository(EventLedger ledger) { this.ledger = ledger; }

    @Override
    public void create(Approval approval) {
        synchronized (txLock) {
            String key = approval.id().value();
            for (DomainEvent e : approval.pendingEvents()) {
                if (ledger.exists(e.eventId())) throw new IllegalStateException("approval create event already exists: " + e.eventId());
            }
            Row prev = store.putIfAbsent(key,
                new Row(approval.operationId(), approval.actionType(), approval.actionVersion(), approval.paramsHash(), approval.expiresAt(),
                        approval.state(), approval.approverId(), approval.version()));
            if (prev != null) {
                throw new IllegalStateException("approval already exists: " + approval.id());
            }
            byOperationId.put(approval.operationId(), key);
            for (DomainEvent e : approval.pendingEvents()) ledger.append(e);
            approval.clearPendingEvents();
        }
    }

    @Override
    public Optional<Approval> load(ApprovalId id) {
        Row row = store.get(id.value());
        if (row == null) return Optional.empty();
        return Optional.of(Approval.rehydrate(id, row.operationId(), row.actionType(), row.actionVersion(), row.paramsHash(),
                row.expiresAt(), row.state(), row.approverId(), row.version()));
    }

    @Override
    public Optional<Approval> findByOperationId(String operationId) {
        String approvalId = byOperationId.get(operationId);
        if (approvalId == null) return Optional.empty();
        return load(ApprovalId.of(approvalId));
    }

    @Override
    public void save(Approval approval) {
        synchronized (txLock) {
            String key = approval.id().value();
            Row current = store.get(key);
            if (current == null) throw new ApprovalOptimisticLockException("vanished: " + approval.id());
            // 1) 版本 CAS —— 先查，使并发转移落在 OptimisticLock 而非 Duplicate
            if (current.version() != approval.version()) {
                throw new ApprovalOptimisticLockException(
                    "concurrent transition on " + approval.id()
                    + " expected v" + approval.version() + " but was v" + current.version());
            }
            // 2) 事件幂等预检 —— 任一已存在则整体回滚（崩溃重放）
            for (DomainEvent e : approval.pendingEvents()) {
                if (ledger.exists(e.eventId())) {
                    throw new ApprovalDuplicateTransitionException(approval.id().value(), e.eventId());
                }
            }
            // 3) 提交：状态 + 事件（同一临界区 = 同一“事务”）
            store.put(key, new Row(approval.operationId(), approval.actionType(), approval.actionVersion(), approval.paramsHash(), approval.expiresAt(),
                    approval.state(), approval.approverId(), approval.version() + 1));
            for (DomainEvent e : approval.pendingEvents()) ledger.append(e);
            approval.clearPendingEvents();
        }
    }
    @Override
    public java.util.List<Approval> findDueToExpire(long now, int limit) {
        if (limit <= 0) return java.util.List.of();
        return store.entrySet().stream()
            .map(e -> load(ApprovalId.of(e.getKey())).orElse(null))
            .filter(java.util.Objects::nonNull)
            .filter(a -> a.dueToExpire(now))
            .sorted(java.util.Comparator.comparingLong(Approval::expiresAt).thenComparing(a -> a.id().value()))
            .limit(limit).toList();
    }

}

