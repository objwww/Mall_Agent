package com.trade.mall.agent.orchestration;

import java.util.Map;
import java.util.Optional;

import static com.trade.mall.agent.orchestration.DiagnosisState.*;
import static com.trade.mall.agent.orchestration.DiagnosisTrigger.*;

/**
 * 诊断流程转移表（不可变常量，纯领域知识，零 I/O）——`domain_model_and_invariants.md` §4
 * 的 stateDiagram 逐条编码为可穷举测试的数据结构，与 `execution.domain.ExecutionTransitionPolicy`
 * （D1，全项目最重要的一段代码）同一套设计纪律的第二次应用，理由完全相同：
 * **`ADR-009`（编排层不依赖 LLM）真正要落地，不能只是"这个包不 import llm"这一条
 * 结构性禁令，还需要"流程往哪走"这件事本身在类型层面就是一张与任何非确定性组件
 * 无关的静态表**——`DiagnosisOrchestrator` 调用 LLM 驱动的能力（Understanding/
 * Reasoning）只是为了拿到一个结果分类（Understood/AnchorMissing、Concluded/
 * NoConclusion），再把这个分类翻译成本表能识别的 {@link DiagnosisTrigger}；"分类
 * 之后往哪走"完全由这张表决定，模型不参与、也无法参与这个决定。
 *
 * <p><b>禁止在本类中加入任何业务条件判断</b>——与 `ExecutionTransitionPolicy` 类头
 * 同一条纪律：一旦出现 `if (actionType == REFUND_RETRY)` 这样的分支，转移表就不再
 * 可穷举，这一天想要证明的"流程走向由代码定"就退化成了"藏在某个 if 里的代码定"。</p>
 *
 * <p>19 个状态、24 条合法转移、5 个终态零出边——{@link #size()} 与
 * `SelfCheck §78`/`DiagnosisTransitionPolicyTest` 对这三个数字都有穷举断言。</p>
 */
public final class DiagnosisTransitionPolicy {

    private static final Map<String, DiagnosisState> TABLE = Map.ofEntries(
        Map.entry(key(RECEIVED,              START_UNDERSTANDING),          UNDERSTANDING),          // O1
        Map.entry(key(UNDERSTANDING,         ANCHOR_MISSING_DETECTED),      ANCHOR_MISSING),         // O2
        Map.entry(key(UNDERSTANDING,         ANCHOR_EXTRACTED),             COLLECTING),             // O3
        Map.entry(key(ANCHOR_MISSING,        ESCALATE_TO_HUMAN),            ESCALATED_HUMAN),        // O4
        Map.entry(key(COLLECTING,            EVIDENCE_INSUFFICIENT_DETECTED), EVIDENCE_INSUFFICIENT),// O5
        Map.entry(key(COLLECTING,            EVIDENCE_COMPLETE),            REASONING),              // O6
        Map.entry(key(EVIDENCE_INSUFFICIENT, ESCALATE_TO_HUMAN),            ESCALATED_HUMAN),        // O7
        Map.entry(key(REASONING,             NO_CONCLUSION_REACHED),        NO_CONCLUSION),          // O8
        Map.entry(key(REASONING,             FINDING_CONCLUDED),            CONCLUDED),              // O9
        Map.entry(key(NO_CONCLUSION,         ESCALATE_TO_HUMAN),            ESCALATED_HUMAN),        // O10
        Map.entry(key(CONCLUDED,             PROPOSAL_CREATED),      PROPOSED),               // O11
        Map.entry(key(CONCLUDED,             NO_ACTION_NEEDED),      CLOSED_NO_ACTION),        // O12
        Map.entry(key(PROPOSED,              REQUIRES_APPROVAL),     AWAITING_APPROVAL),       // O13
        Map.entry(key(PROPOSED,              NO_APPROVAL_NEEDED),    EXECUTING),               // O14
        Map.entry(key(AWAITING_APPROVAL,     APPROVAL_GRANTED),      EXECUTING),               // O15
        Map.entry(key(AWAITING_APPROVAL,     APPROVAL_REJECTED),     REJECTED),                // O16
        Map.entry(key(AWAITING_APPROVAL,     APPROVAL_EXPIRED),      EXPIRED),                 // O17
        Map.entry(key(EXECUTING,             EXECUTION_TERMINAL),    VERIFYING),               // O18
        Map.entry(key(EXECUTING,             EXECUTION_ESCALATED),   ESCALATED_HUMAN),         // O19
        Map.entry(key(VERIFYING,             VERIFY_RECOVERED),      RESOLVED),                // O20
        Map.entry(key(VERIFYING,             VERIFY_NOT_RECOVERED),  NOT_RECOVERED),           // O21
        Map.entry(key(VERIFYING,             VERIFY_SOURCE_UNAVAILABLE), VERIFY_UNAVAILABLE),  // O22
        Map.entry(key(NOT_RECOVERED,         REOPEN_REASONING),      REASONING),               // O23（不重复上一动作，回到 REASONING 而不是 EXECUTING）
        Map.entry(key(VERIFY_UNAVAILABLE,    ESCALATE_TO_HUMAN),     ESCALATED_HUMAN)          // O24
    );

    private DiagnosisTransitionPolicy() {}

    public static Optional<DiagnosisState> next(DiagnosisState from, DiagnosisTrigger trigger) {
        return Optional.ofNullable(TABLE.get(key(from, trigger)));
    }

    /** 施加一次转移；非法组合直接抛异常——与 D1 `ActionExecution.apply()` 内部调用 `next()` 后的处理方式一致。 */
    public static DiagnosisState apply(DiagnosisState from, DiagnosisTrigger trigger) {
        return next(from, trigger).orElseThrow(() -> new IllegalDiagnosisTransitionException(
            "no diagnosis transition: " + from + " --" + trigger + "--> ?"));
    }

    /** 供元测试穷举：表中共有多少条转移。改动必须同步改断言里的数字。 */
    public static int size() { return TABLE.size(); }

    private static String key(DiagnosisState from, DiagnosisTrigger trigger) {
        return from.name() + ':' + trigger.name();
    }
}

