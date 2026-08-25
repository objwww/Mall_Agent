package com.trade.mall.agent.verification.infrastructure;

import com.trade.mall.agent.evidence.port.OrderReadPort;
import com.trade.mall.agent.evidence.port.OrderRecord;
import com.trade.mall.agent.proposal.VerificationPlan;
import com.trade.mall.agent.verification.IndependentFactSource;

/**
 * PAYMENT_GATEWAY_QUERY（支付网关独立事实源）：重新查询网关，再读取目标订单当前状态，
 * 只有“网关已支付 + mall 订单已离开待付款”同时成立才算恢复。绝不使用动作调用的返回值。
 */
public final class PaymentGatewayFactSource implements IndependentFactSource {
    private final HttpMallPaymentGatewayQuery gateway;
    private final OrderReadPort orders;
    public PaymentGatewayFactSource(HttpMallPaymentGatewayQuery gateway, OrderReadPort orders){this.gateway=gateway;this.orders=orders;}
    @Override public String sourceType(){return "PAYMENT_GATEWAY_QUERY";}
    @Override public boolean recoveryConfirmed(String anchor){return recoveryConfirmed(anchor,new VerificationPlan(sourceType(),"gateway/order cross-check"));}
    @Override public boolean recoveryConfirmed(String anchor, VerificationPlan plan){
        HttpMallPaymentGatewayQuery.Result external=gateway.query(anchor);
        if(!"TRADE_SUCCESS".equals(external.tradeStatus()) && !"TRADE_FINISHED".equals(external.tradeStatus())) return false;
        OrderRecord order=orders.findByOrderSn(anchor).orElseThrow(() -> new IllegalStateException("order missing during payment verification: "+anchor));
        return order.status()==1 || order.status()==2 || order.status()==3; // 待发货/已发货/已完成。
    }
}

