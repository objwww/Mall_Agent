package com.trade.mall.agent.execution.port;

/**
 * 一次外部调用（execute/query）的结局。sealed，四种且只有四种——
 * 强迫调用方（DefaultActionDispatcher）在 switch 里穷举处理，编译期就能发现漏判。
 *
 * <p>四态与 {@code DispatchOutcome}/执行状态机的对应关系（见 M-EXEC-03-dispatcher.md）：</p>
 * <pre>
 *   Success          → ACK_SUCCESS      → SUCCEEDED
 *   BusinessFailure  → ACK_FAILURE      → FAILED   （渠道明确拒绝，非网络异常）
 *   Inconclusive     → TIMEOUT          → UNKNOWN  （说不清：超时/连接中断/响应无法解析）
 *   Unavailable      → DEPENDENCY_UNAVAILABLE(T13) → BLOCKED（确定没发出：未配置/DNS 都解不出）
 * </pre>
 */
public sealed interface PortOutcome
    permits PortOutcome.Success, PortOutcome.BusinessFailure, PortOutcome.Inconclusive, PortOutcome.Unavailable {

    /** 渠道明确应答成功。 */
    record Success(String channelRef) implements PortOutcome {}

    /** 渠道明确拒绝（业务错误码，不是网络问题）——这才是唯一允许判 FAILED 的情形。 */
    record BusinessFailure(String errorCode, String message) implements PortOutcome {}

    /** 说不清：超时、连接被中断、响应无法解析。上层必须映射到 UNKNOWN，不得猜测。 */
    record Inconclusive(String reason) implements PortOutcome {}

    /** 确定请求没有离开进程：未配置该渠道、DNS 解析失败、连接被显式拒绝。上层映射到 BLOCKED。 */
    record Unavailable(String reason) implements PortOutcome {}
}

