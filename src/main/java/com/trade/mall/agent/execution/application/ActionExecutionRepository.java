package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.OperationId;

import java.util.Optional;

/**
 * 聚合仓储（端口）。I/O 集中在这里，领域层保持纯粹（ddd_design §6.2）。
 *
 * save() 的语义（生产实现用 MySQL）：
 *  - 版本 CAS（乐观锁）——版本不匹配抛 OptimisticLockException
 *  - 与事件写入在“同一本地事务”内：任一 pendingEvent 的 eventId 已存在则整体回滚，
 *    抛 DuplicateTransitionException（崩溃重放的幂等落点）。
 * 这保证“状态与事件永远一致”——要么都写、要么都不写。
 */
public interface ActionExecutionRepository {
    Optional<ActionExecution> load(OperationId id);   // 生产实现：SELECT ... FOR UPDATE
    void create(ActionExecution execution);           // 新建 PENDING（PK=operationId 防重复）
    void save(ActionExecution execution);             // CAS + 原子写事件
}

