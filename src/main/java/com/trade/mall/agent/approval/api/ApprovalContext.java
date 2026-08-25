package com.trade.mall.agent.approval.api;

import com.trade.mall.agent.approval.Approval;

/**
 * ApprovalContext —— M-API-02 对外的只读 DTO，把 {@link Approval} 聚合内部结构
 * 拍平成一个前端/批准者可以直接消费的视图（不暴露 `pendingEvents` 等内部细节）。
 *
 * <p>D4 范围内没有一个真实 HTTP 层把 JSON 序列化成这个类型——见
 * {@link ApprovalApi} 类头的"未做"说明——这里先把契约定下来，字段与
 * `module_catalog.md` 里 `M-API-02` 条目描述的"批准/拒绝接口"职责对齐。</p>
 */
public record ApprovalContext(
        String approvalId,
        String operationId,
        String actionVersion,
        String paramsHash,
        String state,
        String approverId
) {
    public static ApprovalContext from(Approval approval) {
        return new ApprovalContext(
                approval.id().value(),
                approval.operationId(),
                approval.actionVersion(),
                approval.paramsHash(),
                approval.state().name(),
                approval.approverId());
    }
}

