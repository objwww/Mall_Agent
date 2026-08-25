package com.trade.mall.agent.execution;

import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.ExecutionTransitionPolicy;
import com.trade.mall.agent.execution.domain.TransitionTrigger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 转移表穷举：7 态 x 11 触发 = 77 组合，恰好 13 条合法（D2 补入 T13），终态无出边。 */
class ExecutionTransitionPolicyTest {

    @Test
    void exhaustive_77_combinations() {
        int legal = 0;
        for (ExecutionState from : ExecutionState.values()) {
            for (TransitionTrigger tr : TransitionTrigger.values()) {
                var to = ExecutionTransitionPolicy.next(from, tr);
                if (to.isPresent()) {
                    legal++;
                    assertFalse(from.isTerminal(),
                        "终态 " + from + " 不应有出边 (trigger=" + tr + ")");
                }
            }
        }
        // 硬编码 13：改动转移表必须同步改这个数字——这是有意的，强制重新想一遍
        assertEquals(13, legal, "转移表必须恰好 13 条合法转移（T1-T12 + D2 补入的 T13）");
        assertEquals(13, ExecutionTransitionPolicy.size());
    }

    @Test
    void t13_dependency_unavailable_after_dispatch_goes_to_blocked() {
        // D2 补入：DISPATCH 已提交后才发现依赖确定不可用（F-001）→ BLOCKED，而非 UNKNOWN
        assertEquals(ExecutionState.BLOCKED, ExecutionTransitionPolicy.next(
            ExecutionState.DISPATCHED, TransitionTrigger.DEPENDENCY_UNAVAILABLE).orElseThrow());
    }

    @Test
    void unknown_cannot_redispatch_INV_UNK_002() {
        assertTrue(ExecutionTransitionPolicy.next(
            ExecutionState.UNKNOWN, TransitionTrigger.DISPATCH).isEmpty());
    }

    @Test
    void timeout_never_maps_to_failed_INV_UNK_001() {
        assertEquals(ExecutionState.UNKNOWN, ExecutionTransitionPolicy.next(
            ExecutionState.DISPATCHED, TransitionTrigger.TIMEOUT).orElseThrow());
    }
}

