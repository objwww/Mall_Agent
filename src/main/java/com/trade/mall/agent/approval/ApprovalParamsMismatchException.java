package com.trade.mall.agent.approval;

/**
 * 消费时重算的 (actionVersion, paramsHash) 与批准时绑定的不一致（INV-APPR-001）。
 *
 * <p>检测时机很关键：在 {@link ApprovalGate#consume} 里，这个比对发生在
 * <b>任何状态转移之前</b>——不一致就直接拒绝，不消耗这条 Approval 的任何转移机会，
 * 调用方拿到明确的"参数对不上"信号后，应该走"重新申请批准"而不是重试同一次 consume()。</p>
 */
public class ApprovalParamsMismatchException extends RuntimeException {
    public ApprovalParamsMismatchException(String message) { super(message); }
}

