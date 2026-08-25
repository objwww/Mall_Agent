package com.trade.mall.agent.execution.reconcile;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 与 SelfCheck §18 一一对应：纯策略，零 I/O。 */
class ReconcilePolicyTest {

    @Test void backoff_schedule_5_10_30_60() {
        assertEquals(Duration.ofMinutes(5), ReconcilePolicy.nextDelay(0));
        assertEquals(Duration.ofMinutes(10), ReconcilePolicy.nextDelay(1));
        assertEquals(Duration.ofMinutes(30), ReconcilePolicy.nextDelay(2));
        assertEquals(Duration.ofMinutes(60), ReconcilePolicy.nextDelay(3));
    }

    @Test void backoff_clamps_to_last_tier_beyond_table() {
        assertEquals(Duration.ofMinutes(60), ReconcilePolicy.nextDelay(99));
    }

    @Test void escalates_at_24h_not_before() {
        assertFalse(ReconcilePolicy.shouldEscalate(Duration.ofHours(23).plusMinutes(59)));
        assertTrue(ReconcilePolicy.shouldEscalate(Duration.ofHours(24)));
    }
}

