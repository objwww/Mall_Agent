package com.trade.mall.agent.ledger;

import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventLedgerTest {
    @Test void append_is_idempotent_by_eventId() {
        var l = new InMemoryEventLedger();
        var e = new DomainEvent("op:DISPATCHING:1", "op", "Attempt.Dispatching", 1, "x", 1L);
        assertTrue(l.append(e), "首次写入返回 true");
        assertFalse(l.append(e), "同 eventId 再写返回 false（幂等）");
        assertEquals(1, l.eventsOf("op").size());
    }
    @Test void blank_eventId_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new DomainEvent("", "op", "T", 0, "", 1L));
    }
}

