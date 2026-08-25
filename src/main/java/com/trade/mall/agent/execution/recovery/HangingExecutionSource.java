package com.trade.mall.agent.execution.recovery;

import java.util.List;

/**
 * HangingExecutionSource —— 悬挂候选的只读来源（端口）。
 *
 * <p>生产实现直接查执行表并做短租约 claim（认领）：它不修改 ActionExecution（动作执行）的
 * 领域 state（状态），只写 recovery_claim_until（恢复认领到期时间）这类调度元数据；
 * 多实例通过 `SELECT ... FOR UPDATE SKIP LOCKED` + 短租约避免长期重复扫描。它不经过
 * `ActionExecutionRepository` 的领域 CAS 写路径，因为这里只是“发现/认领”，不是“推进状态”。</p>
 *
 * <p><b>D3 相对模块文档冻结版本的一处简化，已在 D3-REPORT.md 中说明并建议回填文档：</b>
 * 原 SQL 用 `agent_event` 三表 JOIN + NOT EXISTS 去推导"这次尝试有没有终态事件"，
 * 那是因为原设计里 `agent_action_attempt.outcome` 列的更新时机不确定，只能退回去
 * 翻事件日志核实。D2 把 Attempt 结局的持久化坐实之后（`ActionExecution.apply()` 里
 * 的 `settleAttemptIfApplicable`），"这次尝试有没有终态"这件事已经是聚合自己权威、
 * 直接可读的字段（`state`），不再需要绕道事件表反推——`state == DISPATCHED` 本身
 * 就是"转移表里从 DISPATCHED 出去的四条边都还没走"的充分必要条件。查询因此从
 * 三表 JOIN 简化为对 `agent_action_execution.state` 的单表条件，这不是抄近路，
 * 是聚合把这条不变量吃下去之后，下游查询自然变简单的直接结果。</p>
 *
 * <p>{@code claimHanging} 而不是 {@code findHanging}：命名直接体现 SKIP LOCKED 的意图——
 * 调用一次即"认领"，同一批候选不会被并发的第二次调用重复返回，模拟多实例安全（ADR-012）。</p>
 */
public interface HangingExecutionSource {
    List<HangingExecution> claimHanging(int limit);
}

