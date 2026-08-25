package com.trade.mall.agent.verification;

import com.trade.mall.agent.proposal.VerificationPlan;

/** 独立事实源（端口）：验证必须读取与动作来源不同的真实事实。 */
public interface IndependentFactSource {

    String sourceType();

    /** 旧的最小接口保留，避免无关事实源被迫重构。 */
    boolean recoveryConfirmed(String anchor);

    /**
     * 需要关联具体动作时覆盖这个方法；默认仍走旧接口。
     * 这样只有 REFUND_LOG（退款日志）这种确实需要关联键的来源增加逻辑。
     */
    default boolean recoveryConfirmed(String anchor, VerificationPlan plan) {
        return recoveryConfirmed(anchor);
    }
}

