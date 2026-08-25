package com.trade.mall.agent.verification;

/**
 * 验证方案与动作来源相同——`INV-VERIFY-001` 的运行时防线。
 *
 * <p>`proposal.Proposal` 的构造器（D7）已经在类型层面守住了这条不变量——一个
 * 验证方案与动作同源的 `Proposal` 根本构造不出来。但 {@link RecoveryVerifier}
 * 的入口**仍然**独立地做一次同样的检查，不因为"上游应该已经保证过了"就省略——
 * 这不是不信任 D7，是"规则本身要有自测"这条纪律（同 D5 `INV-EVAL-001` 反例夹具、
 * D7 `Proposal` 构造器自测）的延续：`RecoveryVerifier.verify()` 未来完全可能被
 * D7 之外的调用方直接传入一个手写的 `ActionType`/`VerificationPlan` 组合（比如
 * 一次针对既有 `operationId` 的人工补验证），那时候就没有 `Proposal` 构造器这道
 * 保护伞——验证器自己的入口必须能独立地把这类同源验证挡下来，不能依赖"调用方
 * 应该已经用 D7 的方式构造过"这个假设。
 */
public class SameSourceVerificationException extends RuntimeException {
    public SameSourceVerificationException(String message) { super(message); }
}

