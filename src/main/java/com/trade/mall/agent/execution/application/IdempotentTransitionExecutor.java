package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.execution.domain.OperationId;

/**
 * 幂等转移包装：崩溃恢复会重放逻辑，同一业务事实可能被提交两次。
 * eventId 业务语义构造，第二次写入必然冲突——我们把冲突解释为“这件事已做过”，
 * 读当前状态返回即可。
 *
 * 注意：这里不能简单 catch 后重试（会再次冲突，无限循环），必须转为读状态。
 */
public class IdempotentTransitionExecutor {

    private final ExecutionApplicationService service;
    private final ActionExecutionRepository repo;

    public IdempotentTransitionExecutor(ExecutionApplicationService service, ActionExecutionRepository repo) {
        this.service = service; this.repo = repo;
    }

    public ExecutionSnapshot applyIdempotent(TransitionCommand cmd) {
        try {
            return service.transition(cmd);
        } catch (DuplicateTransitionException dup) {
            OperationId id = cmd.operationId();
            return repo.load(id).map(ExecutionSnapshot::of)
                .orElseThrow(() -> new IllegalStateException(
                    "duplicate event but execution missing: " + id));
        }
    }
}

