package com.trade.mall.agent.verification;

import com.trade.mall.agent.ledger.EventLedger;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.VerificationPlan;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * RecoveryVerifier —— `M-CAP-06`：用独立于处置动作的事实验证问题是否消失。
 *
 * <p>两条设计决定，直接对应 `implementation_plan.md` D8 步骤 1：</p>
 * <ol>
 *   <li>**入口先做同源校验，再做任何查询**——{@link #verify} 第一步比对
 *       {@code plan.independentSourceType()} 与 {@code actionType.sourceType()}，
 *       相同就直接抛 {@link SameSourceVerificationException}，连独立事实源都不会去问。
 *       "用退款接口的返回值验证退款"这种自我确认，必须在花一次查询成本之前就被拒绝，
 *       不是查完了才回头发现"这次查询其实不独立"。</li>
 *   <li>**没有为某个 `independentSourceType` 注册适配器，和已注册适配器查询失败，是同一种
 *       结局**——都翻译成 {@link VerifyResult.VerifyUnavailable}。当前生产组装已同时覆盖
 *       `REFUND_LOG`（退款日志）和 `PAYMENT_GATEWAY_QUERY`（支付网关纯查询 + Mall 订单状态
 *       交叉验证）；如果未来新增动作却忘记注册它的独立事实源，也会 fail-closed（失败即收紧），
 *       而不是把“没有验证能力”误写成 Recovered/NotRecovered（已恢复/未恢复）。</li>
 * </ol>
 */
public final class RecoveryVerifier {

    private final Map<String, IndependentFactSource> sourcesByType;
    private final EventLedger ledger;
    private final LongSupplier clock;

    public RecoveryVerifier(List<IndependentFactSource> sources, EventLedger ledger, LongSupplier clock) {
        this.sourcesByType = sources.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(IndependentFactSource::sourceType, s -> s));
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @throws SameSourceVerificationException 验证方案与动作同源（INV-VERIFY-001）
     */
    public VerifyResult verify(String operationId, String anchor, ActionType actionType, VerificationPlan plan) {
        if (plan.independentSourceType().equals(actionType.sourceType())) {
            throw new SameSourceVerificationException(
                "验证方案来源（" + plan.independentSourceType() + "）与动作来源（" + actionType.sourceType()
                    + "）相同——这是自我确认，不是独立验证（INV-VERIFY-001）");
        }

        long now = clock.getAsLong();
        ledger.append(VerificationEvents.started(operationId, 1, plan.independentSourceType(), now));

        IndependentFactSource source = sourcesByType.get(plan.independentSourceType());
        if (source == null) {
            String reason = "没有为 " + plan.independentSourceType() + " 注册独立事实源（M-ADP-01 尚未覆盖这个来源）";
            ledger.append(VerificationEvents.unavailable(operationId, 1, reason, clock.getAsLong()));
            return new VerifyResult.VerifyUnavailable(reason);
        }

        boolean recovered;
        try {
            recovered = source.recoveryConfirmed(anchor, plan);
        } catch (RuntimeException ex) {
            String reason = "查询 " + plan.independentSourceType() + " 失败：" + ex.getMessage();
            ledger.append(VerificationEvents.unavailable(operationId, 1, reason, clock.getAsLong()));
            return new VerifyResult.VerifyUnavailable(reason);
        }

        if (recovered) {
            ledger.append(VerificationEvents.recovered(operationId, 1, plan.independentSourceType(), plan.description(), clock.getAsLong()));
            return new VerifyResult.Recovered(plan.independentSourceType(), plan.description());
        } else {
            ledger.append(VerificationEvents.notRecovered(operationId, 1, plan.independentSourceType(), plan.description(), clock.getAsLong()));
            return new VerifyResult.NotRecovered(plan.independentSourceType(), plan.description());
        }
    }
}

