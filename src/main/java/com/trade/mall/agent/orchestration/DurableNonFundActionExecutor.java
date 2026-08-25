package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.proposal.ParamsHashing;

import java.util.Map;

/**
 * 非资金动作耐久包装器：PENDING 在外部调用前落库；SUCCEEDED/FAILED 在调用后落库。
 * crash 发生在“外部已成功、SUCCEEDED 未落库”时会重放，因此被包装的非资金动作必须本身幂等。
 */
public final class DurableNonFundActionExecutor implements NonFundActionExecutor {
    private final NonFundExecutionStore store;
    private final NonFundActionExecutor delegate;
    public DurableNonFundActionExecutor(NonFundExecutionStore store, NonFundActionExecutor delegate) { this.store=store; this.delegate=delegate; }

    @Override public void execute(ActionType actionType, Map<String,String> params) {
        execute(ParamsHashing.sha256(params), actionType, params);
    }

    @Override public void execute(String operationId, ActionType actionType, Map<String,String> params) {
        String hash=ParamsHashing.sha256(params);
        store.createPending(operationId, actionType, hash);
        NonFundExecutionStore.Entry entry=store.find(operationId).orElseThrow();
        if(entry.state()==NonFundExecutionStore.State.SUCCEEDED) return;
        if(entry.state()==NonFundExecutionStore.State.FAILED) throw new IllegalStateException("non-fund execution already failed: "+operationId);
        try {
            delegate.execute(operationId, actionType, params);
            store.mark(operationId, NonFundExecutionStore.State.SUCCEEDED);
        } catch (NonFundActionBusinessFailureException businessFailure) {
            // 只有远端明确告诉我们“业务拒绝/参数非法”才是 FAILED。
            store.mark(operationId, NonFundExecutionStore.State.FAILED);
            throw businessFailure;
        } catch (RuntimeException inconclusive) {
            // timeout/5xx/连接断开都可能发生在远端副作用之后；保持 PENDING，后续依赖动作幂等性安全重放。
            throw inconclusive;
        }
    }
}

