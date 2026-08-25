package com.trade.mall.agent.execution.port;

/**
 * ActionPort —— M-ADP-02。对外部系统（mall-admin-server）的动作适配端口。
 *
 * <p><b>为什么拆两个方法，而不是一个 call()：</b> 分发和对账是两件性质不同的事——
 * {@code execute()} 是"发起一次可能有副作用的动作"，{@code query()} 是"只读查询
 * 一个已发生的事实"。如果合并成一个方法，对账路径（D3 的 Reconciler）调用它时
 * 就可能因为参数、条件判断写错而意外重新触发一次真实退款——这是本项目能想到的
 * 最严重的一类 bug，用类型系统直接把这条路堵死，比"写测试覆盖"更可靠。</p>
 *
 * <p><b>借鉴 Restate 的 attach（ADR-016）：</b> execute() 的实现必须先按
 * operationId 查一次是否已有结果（通常是查 mall 侧退款单的幂等键/操作流水），
 * 如果已有，直接返回已有结果，不得真的对外部系统再发起一次调用。这一点写在契约
 * 注释里，而不是接口方法签名里，因为"先查后发"是实现细节；调用方（Dispatcher）
 * 不需要、也不应该关心 execute() 内部是否真的发出了 HTTP 请求。</p>
 */
public interface ActionPort {

    /**
     * 发起动作。同一 operationId 重复调用应先查已有结果（Restate attach 语义），
     * 而不是重新对外部系统发起一次调用。
     */
    PortOutcome execute(ActionCommand command);

    /** 只读对账查询：这个 operationId 对应的外部动作最终发生了什么？绝不产生新副作用。 */
    PortOutcome query(String operationId);
}

