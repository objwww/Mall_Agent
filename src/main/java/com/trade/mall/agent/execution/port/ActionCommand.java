package com.trade.mall.agent.execution.port;

/**
 * 发给外部系统（mall-admin-server）的一条动作指令。
 *
 * <p>{@code paramsHash} 与 {@link com.trade.mall.agent.approval.ConsumedApproval#paramsHash()}
 * 必须一致才允许分发——批准的是"一个具体动作的具体参数"，不是"这一类动作"（INV-APPR-001）。
 * 由调用方（应用层）负责用同一算法算出两处的 paramsHash，本类不做计算，只携带结果，
 * 保持端口纯粹（计算逻辑属于批准子域，见 M-EXEC-02，D4）。</p>
 */
public record ActionCommand(String operationId, String actionType, String paramsJson, String paramsHash) {
    public ActionCommand {
        if (operationId == null || operationId.isBlank())
            throw new IllegalArgumentException("operationId must not be blank");
        if (paramsHash == null || paramsHash.isBlank())
            throw new IllegalArgumentException("paramsHash must not be blank");
    }
}

