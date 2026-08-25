package com.trade.mall.agent.execution.application;

/**
 * 同一业务事实（同 eventId）已被记录过——说明这是一次崩溃重放。
 * 由 IdempotentTransitionExecutor 捕获后转为“读当前状态、幂等返回”，
 * 绝不能简单 catch 后重试（会再次冲突，无限循环）。
 */
public class DuplicateTransitionException extends RuntimeException {
    private final String operationId;
    private final String eventId;
    public DuplicateTransitionException(String operationId, String eventId) {
        super("duplicate event: op=" + operationId + " eventId=" + eventId);
        this.operationId = operationId; this.eventId = eventId;
    }
    public String operationId() { return operationId; }
    public String eventId() { return eventId; }
}

