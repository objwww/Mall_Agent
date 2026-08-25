package com.trade.mall.agent.verification.infrastructure;

import com.trade.mall.agent.evidence.port.RefundLogReadPort;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.proposal.VerificationPlan;
import com.trade.mall.agent.verification.IndependentFactSource;

/**
 * REFUND_LOG（退款日志）独立事实源。
 *
 * <p>关键安全语义：如果 VerificationPlan（验证方案）提供 refundSn（退款单号）和
 * baselineSequence（执行前日志基线），只允许“同一退款单、且基线之后新增”的 CHANNEL_SUCCESS
 * 证明本次处置恢复成功；同一订单历史上的其他成功退款不能冒充本次成功。</p>
 */
public final class RefundLogFactSource implements IndependentFactSource {

    private final RefundLogReadPort port;

    public RefundLogFactSource(RefundLogReadPort port) { this.port = port; }

    @Override public String sourceType() { return "REFUND_LOG"; }

    @Override
    public boolean recoveryConfirmed(String anchor) {
        // REFUND_LOG（退款日志）如果没有具体 refundSn（退款单号）和执行前 baseline（基线），
        // 就无法区分“本次动作的新成功”与“这个订单历史上的旧成功”。这里宁可让上层
        // 转成 VerifyUnavailable（无法验证），也绝不能退回宽泛的 orderSn 历史查询。
        throw new IllegalStateException("REFUND_LOG verification requires correlationKey + baselineSequence");
    }

    @Override
    public boolean recoveryConfirmed(String anchor, VerificationPlan plan) {
        if (!plan.hasCorrelation() || plan.baselineSequence() < 0L) return recoveryConfirmed(anchor);
        return port.findByOrderSn(anchor).stream()
            .filter(r -> plan.correlationKey().equals(r.refundSn()))
            .filter(r -> r.id() > plan.baselineSequence())
            .anyMatch(RefundLogFactSource::isChannelSuccess);
    }

    private static boolean isChannelSuccess(RefundLogRecord r) {
        return "CHANNEL_SUCCESS".equals(r.action()) && r.success();
    }
}

