package com.trade.mall.agent.approval;

/**
 * ConsumedApproval —— D2 阶段以最小占位形态引入，**D4 在这里把它收紧到位**。
 *
 * <p><b>构造函数现在是包私有</b>：只有 {@code com.trade.mall.agent.approval} 包内的代码
 * （实际上只有 {@link ApprovalGate#consume}）能造出这个类型的实例。这把
 * INV-APPR-001/002（资金动作必须绑定一次有效批准、批准不可绕过）从"运行时 if 检查"
 * 提升为"编译期就够不到"——D2 报告里立的 flag 现在兑现：字段一个没改
 * （仍是 operationId + paramsHash），只是收紧了谁能构造它，对 D2 已经写好的
 * {@code DefaultActionDispatcher}/调用方代码零破坏性影响。</p>
 *
 * <p><b>验收标准"绕过批准直接调 execute → 编译不过"就是字面意义上的编译不过</b>：
 * 任何在本包之外写 {@code new ConsumedApproval(...)} 的代码，会得到
 * "ConsumedApproval(String,String) is not public in ConsumedApproval; cannot be
 * accessed from outside package" 这个编译错误——D4-REPORT.md §3.1 附了一次真实编译失败
 * 的记录作为证据，而不是只在注释里空口宣称。</p>
 */
public final class ConsumedApproval {
    private final String operationId;
    private final String paramsHash;

    ConsumedApproval(String operationId, String paramsHash) {
        if (operationId == null || operationId.isBlank())
            throw new IllegalArgumentException("operationId must not be blank");
        if (paramsHash == null || paramsHash.isBlank())
            throw new IllegalArgumentException("paramsHash must not be blank");
        this.operationId = operationId;
        this.paramsHash = paramsHash;
    }

    public String operationId() { return operationId; }
    public String paramsHash() { return paramsHash; }
}

