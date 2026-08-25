package com.trade.mall.agent.orchestration;

import com.trade.mall.agent.proposal.ActionType;

import java.util.Optional;

/** 非资金幂等动作的最小耐久记录；不复用资金 ActionExecution（动作执行）状态机。 */
public interface NonFundExecutionStore {
    enum State { PENDING, SUCCEEDED, FAILED }
    record Entry(String operationId, ActionType actionType, String paramsHash, State state) {}

    Optional<Entry> find(String operationId);
    void createPending(String operationId, ActionType actionType, String paramsHash);
    void mark(String operationId, State state);
}

