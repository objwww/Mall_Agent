package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.proposal.ActionType;

import java.util.Map;

/**
 * 非资金动作的执行端口——`domain_model_and_invariants.md` §4 里
 * `PROPOSED --> EXECUTING : 非资金动作(只读/通知)` 这条边跳过 `AWAITING_APPROVAL`
 * 之后，动作到底怎么被执行。
 *
 * <p>**刻意不复用 D1-D4 的 `execution.port.ActionPort`/`ActionDispatcher`**：那一整套
 * 机器（`ActionExecution` 聚合、`ActionAttempt`、`UNKNOWN`/`BLOCKED` 中间态、`INV-EXEC-001`
 * 至多一次成功、崩溃恢复对账）存在的全部理由是资金动作"发出两次等于多花一次钱"这个
 * 后果——`INV-APPR-001` 把这套机器的适用范围明确限定在"资金动作集合"。`ORDER_STATUS_RESYNC`
 * 这类只读/核对动作重复执行没有资金后果（"再查一次订单状态"不是"再退一次款"），
 * 让它走一遍为资金安全设计的重量级状态机，只是徒增复杂度、不增加任何安全性——这是
 * `orchestration.DiagnosisOrchestrator` 对两类动作走两条不同执行路径的根本原因，
 * 不是图省事的简化。</p>
 */
public interface NonFundActionExecutor {
    /** @throws RuntimeException 执行失败——编排层据此把这次执行判定为"到达终态但不成功"，仍然会走去验证，而不是当作异常裸奔。 */
    void execute(ActionType actionType, Map<String, String> params);

    /** operationId（操作编号）感知入口；旧实现保持兼容，耐久包装器用它绑定重启前后的同一动作。 */
    default void execute(String operationId, ActionType actionType, Map<String, String> params) {
        execute(actionType, params);
    }
}

