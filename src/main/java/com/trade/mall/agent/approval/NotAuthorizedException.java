package com.trade.mall.agent.approval;

/**
 * 批准者未过授权检查（INV-APPR-004：授权 ≠ 批准）。
 *
 * <p>这是两个独立的检查点，顺序不能反：授权回答"这个人有没有资格批准资金动作"
 * （角色/权限问题，通常由既有鉴权体系如 Sa-Token 回答），批准回答"这个人是否
 * 同意这一次具体的动作"（业务决策）。一个人可能有资格批准却选择拒绝，
 * 也可能想批准却根本没有资格——{@link ApprovalGate#grant} 把授权检查放在
 * 触发批准转移之前，未过授权时连 PENDING→GRANTED 这条转移都不会尝试。</p>
 */
public class NotAuthorizedException extends RuntimeException {
    public NotAuthorizedException(String message) { super(message); }
}

