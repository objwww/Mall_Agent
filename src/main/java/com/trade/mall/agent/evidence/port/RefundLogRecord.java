package com.trade.mall.agent.evidence.port;

/**
 * `oms_order_refund_log`（退款执行历史日志，Append-Only）单行的只读行投影——
 * `action` 取值如 `REFUND_CREATED`/`CHANNEL_SUCCESS`/`CHANNEL_FAILED`/`CHANNEL_UNKNOWN`，
 * 是这条诊断证据链里最接近"告警"的一类信号：`CHANNEL_FAILED`/`CHANNEL_UNKNOWN` 附带的
 * `errorCode`/`errorMsg`/`traceId` 往往就是判定层（D7+）定位故障根因的关键依据。
 *
 * <p>不实现 {@link com.trade.mall.agent.evidence.EvidencePayload}——单条日志本身不是一条
 * 独立的证据，一次查询天然返回"这个订单/退款单的完整日志序列"，装进 {@link RefundLogBundle}
 * 之后那个包装类型才是证据载荷。</p>
 */
public record RefundLogRecord(
        long id,
        String refundSn,
        String orderSn,
        String action,
        String channelCode,
        boolean success,
        String errorCode,
        String errorMsg,
        String traceId,
        long createTimeEpochMillis
) implements java.io.Serializable {
}

