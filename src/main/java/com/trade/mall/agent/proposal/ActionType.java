package com.trade.mall.agent.proposal;

/**
 * 处置提议的动作类型（值对象/枚举）——`M-CAP-05` 的产出物之一，与
 * {@code reasoning.FindingType} 一一对应（{@code RemediationProposerService} 的确定性
 * 映射表）。
 *
 * <p>{@link #sourceType()} 是这个动作**执行时会调用的工具/渠道**的身份标签——不是"这个动作
 * 处理什么业务"，是"这个动作最终要经由哪个系统边界发生副作用"。这个字段存在的唯一理由是
 * `INV-VERIFY-001`：验证方案的 {@code independentSourceType} 必须与这里不同，否则就是
 * "用退款接口的返回值验证退款"这种自我确认——`Proposal` 的构造器会拿这两个值互相比对
 * （见该类），`sourceType()` 命名刻意避免叫 `channel`/`tool` 这类更狭窄的词，因为它必须能
 * 涵盖"调用一个 API"和"读一张表"这两类完全不同的动作。</p>
 *
 * <p><b>D8 新增</b> {@link #requiresApproval()}：`domain_model_and_invariants.md` §4
 * 诊断状态机里 `PROPOSED --> AWAITING_APPROVAL : 资金动作` / `PROPOSED --> EXECUTING :
 * 非资金动作(只读/通知)` 这两条边需要一个"这个动作到底算不算资金动作"的判断依据——
 * `INV-APPR-001` 原文的措辞是"`actionType ∈ 资金动作集合`"，本项目里"资金动作集合"
 * 就是这张枚举本身的一个子集，没有必要另建一张单独的配置表：{@link #REFUND_RETRY}
 * 真实触碰资金（重新发起退款），{@link #ORDER_STATUS_RESYNC} 只是核对状态，不移动
 * 一分钱——这条区分直接决定 `orchestration.DiagnosisOrchestrator` 要不要在
 * `EXECUTING` 之前插入 `AWAITING_APPROVAL` 这一步。</p>
 */
public enum ActionType {

    /** 重新发起一次退款渠道调用——处置 {@code REFUND_STUCK_NEEDS_RETRY}；触碰资金，必须经批准。 */
    REFUND_RETRY("REFUND_CHANNEL_API", true, "v1"),

    /** 触发订单状态与支付网关的强制核对/幂等同步——处置 {@code ORDER_STATUS_NOT_SYNCED}；不触碰资金，但会写订单状态，无需资金审批。 */
    ORDER_STATUS_RESYNC("ORDER_SERVICE_API", false, "v1");

    private final String sourceType;
    private final boolean requiresApproval;
    private final String actionVersion;

    ActionType(String sourceType, boolean requiresApproval, String actionVersion) {
        this.sourceType = sourceType;
        this.requiresApproval = requiresApproval;
        this.actionVersion = actionVersion;
    }

    public String sourceType() { return sourceType; }

    /** 这个动作是否属于 INV-APPR-001 的"资金动作集合"，需要先经 {@code AWAITING_APPROVAL} 才能执行。 */
    public boolean requiresApproval() { return requiresApproval; }

    /** 审批与执行绑定的动作契约版本；升级动作协议时只在动作定义处变化。 */
    public String actionVersion() { return actionVersion; }
}

