package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.approval.ConsumedApproval;
import com.trade.mall.agent.execution.port.ActionCommand;

/**
 * ActionDispatcher —— M-EXEC-03。把"一个已批准的动作"变成"一次对外部系统的调用"，
 * 并把调用结局忠实地落回执行状态机。
 *
 * <p>方法签名本身携带一条设计意图：只有 {@link ConsumedApproval} 类型的值才能作为入参，
 * 而这个类型（D4 收紧后）只能由批准消费用例构造——"没有被消费的批准，写不出能调用
 * dispatch() 的代码"，把 INV-APPR-001 从运行时检查提升为编译期约束。</p>
 *
 * <p>前置条件：调用方保证传入的 operationId 对应的执行此刻处于 PENDING
 * （即还没有别的地方并发触发过它的 DISPATCH）。D2 不负责这一层并发防护——
 * 那是 D-later 编排层的职责；本类只负责"一旦轮到它分发，怎么分发才安全"。</p>
 */
public interface ActionDispatcher {
    DispatchOutcome dispatch(ConsumedApproval approval, ActionCommand command);
}

