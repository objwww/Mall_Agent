package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.IllegalTransitionException;

/**
 * 执行应用服务：编排 load → apply → save，并画事务边界。
 *
 * 它取代了原 M-EXEC-01 里那个直接写 SQL 的“事务脚本”状态机（ddd_design §6）：
 *  - 领域逻辑（守不变量）在聚合 apply() 里
 *  - I/O（FOR UPDATE / CAS / 写事件）在仓储里
 *  - 本类只负责把它们串起来并包一个事务
 *
 * 生产环境这里会有 @Transactional；此处保持框架无关，事务性由仓储实现保证。
 */
public class ExecutionApplicationService {

    private final ActionExecutionRepository repo;
    private final java.util.function.LongSupplier clock;

    public ExecutionApplicationService(ActionExecutionRepository repo, java.util.function.LongSupplier clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 读取当前执行快照；供恢复/对账层在竞争条件后重新确认真实状态，不做任何状态转移。 */
    public java.util.Optional<ExecutionSnapshot> snapshot(com.trade.mall.agent.execution.domain.OperationId operationId) {
        return repo.load(operationId).map(ExecutionSnapshot::of);
    }

    /** 施加一次转移。 */
    public ExecutionSnapshot transition(TransitionCommand cmd) {
        ActionExecution exec = repo.load(cmd.operationId())
            .orElseThrow(() -> new IllegalTransitionException("execution not found: " + cmd.operationId()));
        exec.apply(cmd.trigger(), cmd.context(), clock.getAsLong());  // 纯领域，守不变量
        repo.save(exec);                                              // CAS + 原子写事件
        return ExecutionSnapshot.of(exec);
    }
}

