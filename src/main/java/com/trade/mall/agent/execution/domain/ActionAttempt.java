package com.trade.mall.agent.execution.domain;

/**
 * 动作尝试（聚合内实体，局部身份 seqNo）。
 * 无独立生命周期，随 ActionExecution 聚合存在（INV-EXEC-002）。
 * “尝试 3 次、其中 2 次 UNKNOWN、第 3 次成功”只有把 Attempt 与 Execution 分开才表达得出。
 */
public final class ActionAttempt {
    private final int seqNo;
    private AttemptOutcome outcome;

    public ActionAttempt(int seqNo, AttemptOutcome outcome) {
        this.seqNo = seqNo;
        this.outcome = outcome;
    }
    public int seqNo() { return seqNo; }
    public AttemptOutcome outcome() { return outcome; }
    public void settle(AttemptOutcome o) { this.outcome = o; }
}

