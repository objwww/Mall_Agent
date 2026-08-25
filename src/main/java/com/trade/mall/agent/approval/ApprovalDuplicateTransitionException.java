package com.trade.mall.agent.approval;

/**
 * 同一批准事件（同 eventId）已被记录过——与
 * {@code execution.application.DuplicateTransitionException} 同构（D1/D2）：
 * 说明这是一次崩溃重放（例如批准者的 HTTP 请求超时后客户端重试了 grant/consume）。
 *
 * <p>D4 范围内没有为批准域再造一个 `IdempotentTransitionExecutor`（那是 D1 为
 * `ActionExecution` 引入的、专门吞掉重放异常并幂等返回当前状态的应用层包装）——
 * 批准的 GRANT/REJECT/CONSUME 由人经 API 触发，不是内部重试循环在打，账本层的
 * eventId 幂等主键已经保证"同一事实不会被记两次"，这个异常继续往上抛出即可：
 * 调用方（HTTP 层）看到它，语义上与"这条批准已经处理过了"完全等价，可以按
 * 幂等重试处理，不需要额外的包装层。留作后续如接入真实重试框架时的扩展点。</p>
 */
public class ApprovalDuplicateTransitionException extends RuntimeException {
    private final String approvalId;
    private final String eventId;

    public ApprovalDuplicateTransitionException(String approvalId, String eventId) {
        super("duplicate approval event: approvalId=" + approvalId + " eventId=" + eventId);
        this.approvalId = approvalId;
        this.eventId = eventId;
    }

    public String approvalId() { return approvalId; }
    public String eventId() { return eventId; }
}

