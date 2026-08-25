package com.trade.mall.agent.approval;

/**
 * 同一批准被消费第二次（INV-APPR-003）。
 *
 * <p>底层原因其实是 {@link IllegalApprovalTransitionException}——转移表里没有
 * {@code CONSUMED--CONSUME-->} 这条边（见 `ApprovalTransitionPolicy` 类头）。
 * {@link ApprovalGate#consume} 把这个底层异常翻译成这个语义更明确的类型，
 * 让调用方不需要知道"批准也是一台状态机"这个实现细节，只需要知道"这条批准已经被用过了"。</p>
 */
public class ApprovalAlreadyConsumedException extends RuntimeException {
    public ApprovalAlreadyConsumedException(String message) { super(message); }
}

