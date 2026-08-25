package com.trade.mall.agent.execution.application;

/**
 * 一次 dispatch() 调用给调用方（编排层）看到的结局。sealed，四种。
 * 与 {@link com.trade.mall.agent.execution.domain.ExecutionState} 不是同一个东西——
 * 这是"这次调用你该怎么处理"的应用层结论，而状态机状态是持久化的领域事实；
 * 两者当前恰好一一对应（Succeeded↔SUCCEEDED 等），但故意分开定义两个类型，
 * 避免以后编排层需要展示的信息（比如 channelRef）反向污染纯领域层。
 */
public sealed interface DispatchOutcome
    permits DispatchOutcome.Succeeded, DispatchOutcome.Failed,
            DispatchOutcome.Unknown, DispatchOutcome.Blocked {

    record Succeeded(String operationId, String channelRef) implements DispatchOutcome {}
    record Failed(String operationId, String errorCode) implements DispatchOutcome {}
    record Unknown(String operationId, String reason) implements DispatchOutcome {}
    record Blocked(String operationId, String dependencyId) implements DispatchOutcome {}
}

