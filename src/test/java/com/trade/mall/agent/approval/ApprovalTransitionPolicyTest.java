package com.trade.mall.agent.approval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 转移表穷举：5 态 x 4 触发 = 20 组合，恰好 5 条合法，终态无出边。同 D1/D2 的 ExecutionTransitionPolicyTest 同构。 */
class ApprovalTransitionPolicyTest {

    @Test
    void exhaustive_20_combinations() {
        int legal = 0;
        for (ApprovalState from : ApprovalState.values()) {
            for (ApprovalTrigger tr : ApprovalTrigger.values()) {
                var to = ApprovalTransitionPolicy.next(from, tr);
                if (to.isPresent()) {
                    legal++;
                    assertFalse(from.isTerminal(),
                        "终态 " + from + " 不应有出边 (trigger=" + tr + ")");
                }
            }
        }
        // 硬编码 5：改动转移表必须同步改这个数字——强制重新想一遍是否引入了新的非法转移
        assertEquals(5, legal, "转移表必须恰好 5 条合法转移");
        assertEquals(5, ApprovalTransitionPolicy.size());
    }

    @Test
    void consumed_cannot_be_consumed_again_INV_APPR_003() {
        assertTrue(ApprovalTransitionPolicy.next(
            ApprovalState.CONSUMED, ApprovalTrigger.CONSUME).isEmpty());
    }

    @Test
    void pending_cannot_be_consumed_directly() {
        assertTrue(ApprovalTransitionPolicy.next(
            ApprovalState.PENDING, ApprovalTrigger.CONSUME).isEmpty());
    }

    @Test
    void granted_can_expire_without_being_consumed() {
        assertEquals(ApprovalState.EXPIRED, ApprovalTransitionPolicy.next(
            ApprovalState.GRANTED, ApprovalTrigger.EXPIRE).orElseThrow());
    }

    @Test
    void granted_consume_reaches_consumed() {
        assertEquals(ApprovalState.CONSUMED, ApprovalTransitionPolicy.next(
            ApprovalState.GRANTED, ApprovalTrigger.CONSUME).orElseThrow());
    }
}

