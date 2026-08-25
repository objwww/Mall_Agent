package com.trade.mall.agent.execution.domain;

import java.util.Map;
import java.util.Optional;

import static com.trade.mall.agent.execution.domain.ExecutionState.*;
import static com.trade.mall.agent.execution.domain.TransitionTrigger.*;

/**
 * 转移表（不可变常量，纯领域知识，零 I/O）。
 *
 * <p><b>本类是全项目最重要的一段代码。</b> 它把 domain_model_and_invariants.md §3.3
 * 的 13 条合法转移 + 5 类禁止转移编码为可穷举测试的数据结构。
 * （T13 是 D2 补入的：DISPATCH 已提交后，actionPort.execute() 才发现依赖确定不可用
 * ——ConnectException / "未配置"文案 / F-001——回落到 BLOCKED 而非 UNKNOWN。
 * 见 domain_model_and_invariants.md §3.3 T13 说明与 DependencyUnavailableClassifier。）</p>
 *
 * <p><b>禁止在本类中加入任何业务条件判断。</b> 一旦出现 if(isRefund) 这样的分支，
 * 转移表就不再可穷举，ADR-002 的全部收益消失。</p>
 *
 * 不变量的落点：
 *  - 不存在 UNKNOWN--DISPATCH--> → INV-UNK-002（UNKNOWN 不重发）自动成立
 *  - 不存在 DISPATCHED--TIMEOUT-->FAILED → INV-UNK-001（超时不判失败）自动成立
 */
public final class ExecutionTransitionPolicy {

    private static final Map<String, ExecutionState> TABLE = Map.ofEntries(
        Map.entry(key(PENDING,    DEPENDENCY_UNAVAILABLE), BLOCKED),    // T1
        Map.entry(key(PENDING,    DISPATCH),               DISPATCHED), // T2
        Map.entry(key(DISPATCHED, ACK_SUCCESS),            SUCCEEDED),  // T3
        Map.entry(key(DISPATCHED, ACK_FAILURE),            FAILED),     // T4
        Map.entry(key(DISPATCHED, TIMEOUT),                UNKNOWN),    // T5
        Map.entry(key(DISPATCHED, CRASH_RECOVERED),        UNKNOWN),    // T6
        Map.entry(key(UNKNOWN,    RECONCILE_SUCCESS),      SUCCEEDED),  // T7
        Map.entry(key(UNKNOWN,    RECONCILE_FAILURE),      FAILED),     // T8
        Map.entry(key(UNKNOWN,    RECONCILE_INCONCLUSIVE), UNKNOWN),    // T9
        Map.entry(key(UNKNOWN,    ESCALATE),               ESCALATED),  // T10
        Map.entry(key(BLOCKED,    DEPENDENCY_RESTORED),    PENDING),    // T11
        Map.entry(key(BLOCKED,    ESCALATE),               ESCALATED),  // T12
        Map.entry(key(DISPATCHED, DEPENDENCY_UNAVAILABLE), BLOCKED)     // T13（D2）
    );

    private ExecutionTransitionPolicy() {}

    public static Optional<ExecutionState> next(ExecutionState from, TransitionTrigger trigger) {
        return Optional.ofNullable(TABLE.get(key(from, trigger)));
    }

    /** 供元测试穷举：表中共有多少条转移。改动必须同步改断言里的数字。 */
    public static int size() { return TABLE.size(); }

    private static String key(ExecutionState from, TransitionTrigger trigger) {
        return from.name() + ':' + trigger.name();
    }
}

