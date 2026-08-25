package com.trade.mall.agent.approval;

import com.trade.mall.agent.approval.api.ApprovalApi;
import com.trade.mall.agent.approval.infrastructure.InMemoryApprovalRepository;
import com.trade.mall.agent.approval.infrastructure.InMemoryAuthorizationPort;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §33-39 一一对应：D4 的验收标准全部在此复现。
 * 没有 Mockito（Maven Central 被墙），全部用 InMemory* 端口实现驱动真实的
 * ApprovalGate（不打桩 ApprovalGate 自身——它是被测对象，不是协作者）。
 */
class ApprovalGateTest {

    static final long NOW = 1_700_000_000_000L;
    InMemoryEventLedger ledger;
    InMemoryApprovalRepository repo;
    InMemoryAuthorizationPort authPort;
    ApprovalGate gate;
    ApprovalApi api;

    @BeforeEach void setup() {
        ledger = new InMemoryEventLedger();
        repo = new InMemoryApprovalRepository(ledger);
        authPort = new InMemoryAuthorizationPort();
        gate = new ApprovalGate(repo, authPort, () -> NOW);
        api = new ApprovalApi(gate, repo);
    }

    @Test void request_then_grant_then_consume_happy_path() {
        gate.request("appr", "op", "v1", "h");
        assertEquals("PENDING", api.getContext("appr").state());

        authPort.authorize("alice", "op");
        var granted = gate.grant("appr", "alice");
        assertEquals(ApprovalState.GRANTED, granted.state());
        assertEquals("alice", granted.approverId());
        assertTrue(ledger.exists(ApprovalEventIds.granted("appr")));

        var consumed = gate.consume("op", "v1", "h");
        assertEquals("op", consumed.operationId());
        assertEquals("h", consumed.paramsHash());
        assertEquals(ApprovalState.CONSUMED, repo.load(ApprovalId.of("appr")).orElseThrow().state());
        assertTrue(ledger.exists(ApprovalEventIds.consumed("appr")));
    }

    @Test void grant_without_authorization_is_rejected_INV_APPR_004() {
        gate.request("appr", "op", "v1", "h");
        // authPort 未对 bob 开放任何授权
        assertThrows(NotAuthorizedException.class, () -> gate.grant("appr", "bob"));
        assertEquals(ApprovalState.PENDING, repo.load(ApprovalId.of("appr")).orElseThrow().state(),
            "未授权时，PENDING→GRANTED 这条转移根本没有被尝试");
    }

    @Test void reject_requires_authorization_too() {
        gate.request("appr", "op", "v1", "h");
        assertThrows(NotAuthorizedException.class, () -> gate.reject("appr", "carol"));

        authPort.authorize("carol", "op");
        var rejected = gate.reject("appr", "carol");
        assertEquals(ApprovalState.REJECTED, rejected.state());
        assertThrows(ApprovalAlreadyConsumedException.class, () -> gate.consume("op", "v1", "h"),
            "REJECTED 是终态，之后不可能再被消费");
    }

    @Test void consuming_twice_fails_the_second_time_INV_APPR_003() {
        gate.request("appr", "op", "v1", "h");
        authPort.authorize("dave", "op");
        gate.grant("appr", "dave");

        assertNotNull(gate.consume("op", "v1", "h"));
        assertThrows(ApprovalAlreadyConsumedException.class, () -> gate.consume("op", "v1", "h"));
    }

    @Test void params_drift_at_consume_is_rejected_before_any_transition_INV_APPR_001() {
        gate.request("appr", "op", "v1", "hash-orig");
        authPort.authorize("eve", "op");
        gate.grant("appr", "eve");

        assertThrows(ApprovalParamsMismatchException.class, () -> gate.consume("op", "v1", "hash-tampered"));
        assertEquals(ApprovalState.GRANTED, repo.load(ApprovalId.of("appr")).orElseThrow().state(),
            "比对发生在转移之前——失败不消耗这条 Approval 唯一的一次消费机会");
        assertNotNull(gate.consume("op", "v1", "hash-orig"), "用正确参数事后仍能成功");
    }

    @Test void consuming_before_grant_is_rejected_structurally() {
        gate.request("appr", "op", "v1", "h");
        // 故意不 grant：PENDING 状态下直接尝试消费
        assertThrows(ApprovalAlreadyConsumedException.class, () -> gate.consume("op", "v1", "h"),
            "PENDING--CONSUME--> 在转移表里根本不存在，走同一条'表里没有'机制拒绝");
    }

    @Test void concurrent_double_consume_deterministic_cas_collision() {
        gate.request("appr", "op", "v1", "h");
        authPort.authorize("henry", "op");
        gate.grant("appr", "henry");

        // 两个调用方都在同一版本载入（模拟并发读到同一份 GRANTED 快照），同 D1 §7b
        var copyA = repo.load(ApprovalId.of("appr")).orElseThrow();
        var copyB = repo.load(ApprovalId.of("appr")).orElseThrow();
        copyA.apply(ApprovalTrigger.CONSUME, null, NOW);
        copyB.apply(ApprovalTrigger.CONSUME, null, NOW);

        repo.save(copyA); // 先提交者赢
        assertThrows(ApprovalOptimisticLockException.class, () -> repo.save(copyB),
            "后提交者必被版本 CAS 挡下，绝不出现两条 CONSUMED 事件");
        assertEquals(ApprovalState.CONSUMED, repo.load(ApprovalId.of("appr")).orElseThrow().state());
    }

    @Test void request_persists_pending_and_records_requested_audit_event() {
        gate.request("appr", "op", "v1", "h");
        assertEquals(1, ledger.countOfType("appr", "Approval.Requested"),
            "审批申请必须留下审计事件，但不能执行任何业务动作");
    }

    // ================= D8：expire() —— 与 SelfCheck §86 一一对应 =================

    @Test void expire_pendingApproval_movesToExpired_thenConsumeRejected() {
        gate.request("appr", "op", "v1", "h");
        var expired = gate.expire("appr");
        assertEquals(ApprovalState.EXPIRED, expired.state());
        assertThrows(ApprovalAlreadyConsumedException.class, () -> gate.consume("op", "v1", "h"),
            "EXPIRED 是终态，不可再被消费");
    }
}
