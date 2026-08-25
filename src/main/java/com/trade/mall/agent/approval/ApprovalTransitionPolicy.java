package com.trade.mall.agent.approval;

import java.util.Map;
import java.util.Optional;

import static com.trade.mall.agent.approval.ApprovalState.*;
import static com.trade.mall.agent.approval.ApprovalTrigger.*;

/**
 * 批准转移表（不可变常量，纯领域知识，零 I/O）——与
 * {@code execution.domain.ExecutionTransitionPolicy} 同一套哲学（D1）：
 * 用"表里没有"而不是 if 判断去守不变量，禁止在本类中加入任何业务条件分支。
 *
 * <p><b>INV-APPR-003（批准只能被消费一次）的落点</b>：表里只有
 * {@code GRANTED--CONSUME-->CONSUMED} 一条，没有 {@code CONSUMED--CONSUME-->}——
 * 第二次消费不是靠"检查是否已消费"这行 if 挡住的，是转移表里根本不存在这条边。
 * 这与 D1 处理 INV-UNK-002 的方式完全同构，是同一条设计原则的第二次应用。</p>
 */
public final class ApprovalTransitionPolicy {

    private static final Map<String, ApprovalState> TABLE = Map.ofEntries(
        Map.entry(key(PENDING, GRANT),  GRANTED),   // A1：人批准
        Map.entry(key(PENDING, REJECT), REJECTED),  // A2：人拒绝
        Map.entry(key(PENDING, EXPIRE), EXPIRED),   // A3：待决超时未决
        Map.entry(key(GRANTED, CONSUME), CONSUMED), // A4：唯一消费入口（INV-APPR-003）
        Map.entry(key(GRANTED, EXPIRE), EXPIRED)    // A5：已批准但一直没被消费，超时作废
    );

    private ApprovalTransitionPolicy() {}

    public static Optional<ApprovalState> next(ApprovalState from, ApprovalTrigger trigger) {
        return Optional.ofNullable(TABLE.get(key(from, trigger)));
    }

    public static int size() { return TABLE.size(); }

    private static String key(ApprovalState from, ApprovalTrigger trigger) {
        return from.name() + ':' + trigger.name();
    }
}

