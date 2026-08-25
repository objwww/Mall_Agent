package com.trade.mall.agent.approval;

import java.util.List;
import java.util.Optional;

/** Approval（审批）仓储；状态与领域事件必须同一本地事务提交。 */
public interface ApprovalRepository {
    Optional<Approval> load(ApprovalId id);
    Optional<Approval> findByOperationId(String operationId);
    void create(Approval approval);
    void save(Approval approval);
    default List<Approval> findDueToExpire(long now, int limit) { return List.of(); }
}

