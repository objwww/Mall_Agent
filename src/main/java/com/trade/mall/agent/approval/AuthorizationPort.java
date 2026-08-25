package com.trade.mall.agent.approval;

/**
 * AuthorizationPort —— 授权端口（INV-APPR-004 的另一半：授权 ≠ 批准）。
 *
 * <p>生产实现复用既有鉴权体系（`FACT-STACK-001`：Sa-Token 1.42.0 已在栈），
 * 回答"这个 approverId 有没有资格批准这个 operationId 对应的资金动作"——
 * 这是一个纯粹的权限问题，与"这个人这一次同不同意"（批准本身）完全独立。</p>
 */
public interface AuthorizationPort {
    boolean isAuthorizedApprover(String approverId, String operationId);
}

