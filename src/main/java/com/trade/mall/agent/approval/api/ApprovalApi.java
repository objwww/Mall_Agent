package com.trade.mall.agent.approval.api;

import com.trade.mall.agent.approval.Approval;
import com.trade.mall.agent.approval.ApprovalGate;
import com.trade.mall.agent.approval.ApprovalId;
import com.trade.mall.agent.approval.ApprovalNotFoundException;
import com.trade.mall.agent.approval.ApprovalRepository;

/**
 * ApprovalApi —— M-API-02。批准/拒绝接口的**协议无关**门面：只做
 * "取上下文 / 批准 / 拒绝"三件事，把 HTTP 语义（状态码、鉴权 Session 取
 * `approverId`、请求体校验）完全挡在这一层之外，实际业务规则全部委托给
 * {@link ApprovalGate}——本类不重复任何一条不变量判断。
 *
 * <p><b>诚实的"未做"声明</b>（与 D1-D3 的报告口径一致）：本类没有
 * `@RestController`/`@PostMapping` 等 Spring MVC 注解，没有接 Sa-Token 从
 * 请求上下文取当前登录用户当 `approverId`，没有 JSON 序列化/反序列化，
 * 没有 `M-API-02-approval-api.md` 这份模块文档（`module_catalog.md` 里
 * 已经登记了这个 ID，但文档正文尚未写）。这些都是 D4 验收标准之外的
 * 基础设施粘合工作，留给接入真实 Web 框架时按 `architecture_rules.md`
 * 的分层规则补齐——本类的方法签名就是那时候 Controller 应该调用的
 * 应用层入口，提前把契约定下来。</p>
 */
public final class ApprovalApi {

    private final ApprovalGate gate;
    private final ApprovalRepository repo;

    public ApprovalApi(ApprovalGate gate, ApprovalRepository repo) {
        this.gate = gate;
        this.repo = repo;
    }

    /** 查询一条批准的当前上下文——供批准者界面展示"要批的是什么"。 */
    public ApprovalContext getContext(String approvalId) {
        Approval approval = repo.load(ApprovalId.of(approvalId))
            .orElseThrow(() -> new ApprovalNotFoundException("approval not found: " + approvalId));
        return ApprovalContext.from(approval);
    }

    /**
     * 批准。`approverId` 在真实 HTTP 层应来自已鉴权的会话（Sa-Token），
     * 不是请求体里的自报字段——本方法的签名把这个假设留给调用方保证，
     * 自己只负责把值原样转交 {@link ApprovalGate#grant}（授权检查在那里做）。
     */
    public ApprovalContext grant(String approvalId, String approverId) {
        return ApprovalContext.from(gate.grant(approvalId, approverId));
    }

    /** 拒绝。语义与 {@link #grant} 对称。 */
    public ApprovalContext reject(String approvalId, String approverId) {
        return ApprovalContext.from(gate.reject(approvalId, approverId));
    }
}

