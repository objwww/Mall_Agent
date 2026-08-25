package com.trade.mall.agent.proposal;

import com.trade.mall.agent.evidence.AcquireState;
import com.trade.mall.agent.evidence.Evidence;
import com.trade.mall.agent.evidence.EvidenceBundle;
import com.trade.mall.agent.evidence.port.RefundLogBundle;
import com.trade.mall.agent.evidence.port.RefundLogRecord;
import com.trade.mall.agent.evidence.port.AfterSaleRecord;
import com.trade.mall.agent.evidence.port.RefundRecord;
import com.trade.mall.agent.ledger.EventLedger;
import com.trade.mall.agent.reasoning.FindingResult;
import com.trade.mall.agent.reasoning.FindingType;
import com.trade.mall.agent.understanding.Anchor;

import java.util.Map;
import java.util.function.LongSupplier;

/**
 * RemediationProposerService —— `M-CAP-05`：判定 → 结构化处置提议。
 *
 * <p>与 `reasoning.ReasoningService`（`M-CAP-04`）刻意不同的一条设计决定：**本类完全不
 * 依赖 `agent.llm` 包**（`SelfCheck` 有一条穷人版 ArchUnit 断言专门验证这件事）——
 * `implementation_plan.md` D7 的 12 维度卡里，`M-CAP-04` 明确写了"调 `LlmPort`（钉版本）"，
 * `M-CAP-05` 那一栏只写"产 `Proposal`，交编排决定是否需批准"，**没有提调 LLM**。
 * 这不是疏漏，是有意义的边界：`FindingType → ActionType` 是一张封闭的、诊断团队自己
 * 定义的策略表（{@link #POLICY}），不需要每次都问一遍非确定性的模型"这个判定该配哪个
 * 动作"——判定本身（"发生了什么"）值得让 LLM 处理，因为现实世界的故障模式复杂到没法
 * 穷举规则；但"这类判定该提议哪种处置动作"是一个**产品/风控决定**，一旦定下来就应该是
 * 确定性的、可审计的映射，不应该有"同一个 `Finding` 两次提议出不同的 `Proposal`"这种
 * 不确定性——资金处置提议不是适合引入随机性的地方。</p>
 *
 * <p>这也是"提议阶段零副作用"（D7 验收标准）在结构上最强的落地方式：本类的依赖只有
 * {@link EventLedger}，连一个 LLM 客户端引用都没有，更不用说执行端口——不是靠运行时
 * 检查保证零副作用，是这个类**物理上拿不到**能产生副作用的协作者。</p>
 */
public final class RemediationProposerService {

    private final EventLedger ledger;
    private final LongSupplier clock;

    public RemediationProposerService(EventLedger ledger, LongSupplier clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /** `FindingType → (ActionType, 参数构造器, 验证方案构造器)` 的确定性策略表，见类头。 */
    private interface Policy {
        ActionType actionType();
        Map<String, String> params(Anchor anchor, EvidenceBundle bundle);
        VerificationPlan verificationPlan(Anchor anchor, EvidenceBundle bundle);
    }

    private static final Map<FindingType, Policy> POLICY = Map.of(
        FindingType.REFUND_STUCK_NEEDS_RETRY, new Policy() {
            @Override public ActionType actionType() { return ActionType.REFUND_RETRY; }
            @Override public Map<String, String> params(Anchor anchor, EvidenceBundle bundle) {
                // 生产编排会传入证据：审批必须绑定 mall 真实 AgentRefundCommand 的完整参数，
                // 不能只批准 orderSn，等执行时再偷偷补 amount/returnApplyId。
                if (bundle != null) {
                    RefundRecord refund = requireRefund(bundle, anchor.value());
                    Long returnApplyId = refund.returnApplyId();
                    if (returnApplyId == null) {
                        // 兼容旧证据投影；新适配器应直接从 oms_order_refund 带出 return_apply_id。
                        returnApplyId = requireAfterSale(bundle, anchor.value()).id();
                    }
                    if (refund.refundAmount() == null || refund.refundAmount().signum() <= 0) {
                        throw new IllegalArgumentException("退款证据缺少合法 refundAmount，不能形成资金处置提议");
                    }
                    return Map.of(
                        "returnApplyId", Long.toString(returnApplyId),
                        "amount", refund.refundAmount().toPlainString(),
                        "currency", "CNY",
                        "actor", "mall-agent",
                        "note", "REFUND_STUCK remediation"
                    );
                }

                // 兼容 D7 独立单元测试的旧入口；真实 D8 编排不会走这个分支。
                return Map.of("orderSn", anchor.value());
            }
            @Override public VerificationPlan verificationPlan(Anchor anchor, EvidenceBundle bundle) {
                String refundSn = refundSn(bundle);
                long baseline = maxRefundLogId(bundle, refundSn);
                return new VerificationPlan("REFUND_LOG",
                    "重试发起之后，独立查询 oms_order_refund_log（退款日志），只接受目标退款单在执行基线之后新增的 CHANNEL_SUCCESS。",
                    refundSn, baseline);
            }
        },
        FindingType.ORDER_STATUS_NOT_SYNCED, new Policy() {
            @Override public ActionType actionType() { return ActionType.ORDER_STATUS_RESYNC; }
            @Override public Map<String, String> params(Anchor anchor, EvidenceBundle bundle) {
                return Map.of("orderSn", anchor.value());
            }
            @Override public VerificationPlan verificationPlan(Anchor anchor, EvidenceBundle bundle) {
                return new VerificationPlan("PAYMENT_GATEWAY_QUERY",
                    "核对之后直接查询支付网关自己的交易状态接口，而不是重新读 mall 订单表——"
                        + "订单表正是 ORDER_SERVICE_API 这次调用要改写的地方，读它验证它自己是自我确认。");
            }
        }
    );

    /**
     * @throws IllegalArgumentException finding 的类型没有对应的处置策略（目前只覆盖两种 FindingType，见类头）
     */
    public Proposal propose(String diagnosisId, Anchor anchor, FindingResult.Concluded finding) {
        return propose(diagnosisId, anchor, finding, null, 1);
    }

    /** 这个 Finding 是否需要动作；无动作结论由编排层走 CLOSED_NO_ACTION，不伪造 Proposal。 */
    public boolean requiresAction(FindingType findingType) {
        return POLICY.containsKey(findingType);
    }

    /** 编排链路使用这个重载，把取证时看到的退款单号/日志基线固定进验证方案。 */
    public Proposal propose(String diagnosisId, Anchor anchor, FindingResult.Concluded finding, EvidenceBundle bundle) {
        return propose(diagnosisId, anchor, finding, bundle, bundle == null ? 1 : bundle.round());
    }

    public Proposal propose(String diagnosisId, Anchor anchor, FindingResult.Concluded finding, EvidenceBundle bundle, int round) {
        if (round < 1) throw new IllegalArgumentException("round must be >= 1");
        Policy policy = POLICY.get(finding.findingType());
        if (policy == null) {
            throw new IllegalArgumentException("没有为 " + finding.findingType() + " 定义处置策略");
        }

        Map<String, String> params = policy.params(anchor, bundle);
        String paramsHash = ParamsHashing.sha256(params);
        String proposalId = diagnosisId + ":PROPOSAL:" + round;

        Proposal proposal = new Proposal(proposalId, policy.actionType(), params, paramsHash,
            finding.findingId(), policy.verificationPlan(anchor, bundle));

        ledger.append(ProposalEvents.created(diagnosisId, round, proposal.actionType().name(),
            proposal.paramsHash(), proposal.basedOnFindingId(),
            proposal.verificationPlan().independentSourceType(), clock.getAsLong()));

        return proposal;
    }

    private static String refundSn(EvidenceBundle bundle) {
        if (bundle == null) return null;
        return bundle.items().stream()
            .filter(e -> e.acquireState() == AcquireState.PRESENT && "REFUND".equals(e.sourceType()))
            .map(Evidence::payload)
            .filter(RefundRecord.class::isInstance)
            .map(RefundRecord.class::cast)
            .map(RefundRecord::refundSn)
            .filter(v -> v != null && !v.isBlank())
            .findFirst().orElse(null);
    }

    private static RefundRecord requireRefund(EvidenceBundle bundle, String orderSn) {
        return bundle.items().stream()
            .filter(e -> e.acquireState() == AcquireState.PRESENT && "REFUND".equals(e.sourceType()))
            .map(Evidence::payload)
            .filter(RefundRecord.class::isInstance)
            .map(RefundRecord.class::cast)
            .filter(r -> orderSn.equals(r.orderSn()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("缺少当前订单的 REFUND 证据，不能形成退款处置提议"));
    }

    private static AfterSaleRecord requireAfterSale(EvidenceBundle bundle, String orderSn) {
        return bundle.items().stream()
            .filter(e -> e.acquireState() == AcquireState.PRESENT && "AFTER_SALE".equals(e.sourceType()))
            .map(Evidence::payload)
            .filter(AfterSaleRecord.class::isInstance)
            .map(AfterSaleRecord.class::cast)
            .filter(r -> orderSn.equals(r.orderSn()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("缺少当前订单的 AFTER_SALE 证据，不能形成退款处置提议"));
    }

    private static long maxRefundLogId(EvidenceBundle bundle, String refundSn) {
        if (bundle == null || refundSn == null) return -1L;
        return bundle.items().stream()
            .filter(e -> e.acquireState() == AcquireState.PRESENT && "REFUND_LOG".equals(e.sourceType()))
            .map(Evidence::payload)
            .filter(RefundLogBundle.class::isInstance)
            .map(RefundLogBundle.class::cast)
            .flatMap(b -> b.entries().stream())
            .filter(r -> refundSn.equals(r.refundSn()))
            .mapToLong(RefundLogRecord::id)
            .max().orElse(-1L);
    }
}


