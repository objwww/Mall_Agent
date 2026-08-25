package com.trade.mall.agent.reasoning;

/**
 * 判定结论的类型（值对象/枚举）——`M-CAP-04` 的产出物之一。
 *
 * <p>D7 范围内只定义两个具体类型，各自对应一条从 D5 证据源到 D7 处置提议的完整链路，
 * 不是想把"所有可能的故障模式"都在这一天穷举完：</p>
 * <ul>
 *   <li>{@link #REFUND_STUCK_NEEDS_RETRY} —— 退款单已发起，但退款执行日志
 *       （{@code oms_order_refund_log}，D5 的 {@code RefundLogEvidenceCollector}）显示
 *       渠道侧失败/未知，且退款单本身尚未到达终态——典型的"卡在半路"，处置建议是重试。</li>
 *   <li>{@link #ORDER_STATUS_NOT_SYNCED} —— 用户反映已支付但订单状态未更新，呼应 D6
 *       `TicketUnderstandingService` §60/§64 例子里的锚点症状"支付成功但订单状态未更新"
 *       ——处置建议是触发一次订单状态与支付网关的强制核对。</li>
 * </ul>
 *
 * <p>每个类型在 {@code proposal.RemediationProposerService} 里对应唯一一个
 * {@link com.trade.mall.agent.proposal.ActionType}（确定性映射表，见该类），
 * 这条 1:1 关系是刻意设计——`FindingType` 只在"要不要处置、处置成什么类型的动作"这个
 * 边界上生效，不需要处置层再猜一次"这个判定到底该配哪个动作"。</p>
 */
public enum FindingType {
    REFUND_STUCK_NEEDS_RETRY,
    ORDER_STATUS_NOT_SYNCED,

    /** 独立退款事实已证明恢复完成，因此无需产生新的处置动作。 */
    REFUND_ALREADY_RECOVERED
}

