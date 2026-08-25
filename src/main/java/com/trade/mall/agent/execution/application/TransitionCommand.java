package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.execution.domain.OperationId;
import com.trade.mall.agent.execution.domain.TransitionContext;
import com.trade.mall.agent.execution.domain.TransitionTrigger;

/** 一次转移请求（应用层输入 DTO）。 */
public record TransitionCommand(OperationId operationId, TransitionTrigger trigger, TransitionContext context) {
    public static TransitionCommand of(String opId, TransitionTrigger trigger, TransitionContext ctx) {
        return new TransitionCommand(OperationId.of(opId), trigger, ctx);
    }
}

