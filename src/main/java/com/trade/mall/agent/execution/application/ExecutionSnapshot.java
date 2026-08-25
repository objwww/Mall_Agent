package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.ExecutionState;

/** 转移后的只读快照（返回给调用方，不暴露聚合内部可变状态）。 */
public record ExecutionSnapshot(String operationId, ExecutionState state, long version) {
    public static ExecutionSnapshot of(ActionExecution e) {
        return new ExecutionSnapshot(e.id().value(), e.state(), e.version());
    }
}

