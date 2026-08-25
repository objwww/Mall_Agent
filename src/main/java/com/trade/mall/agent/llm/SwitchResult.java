package com.trade.mall.agent.llm;

/**
 * 一次 {@link LlmRegistry#switchTo} 调用的结局——sealed 三态，`M-LLM-01` §2 原样照抄。
 * `Aborted` **不是异常**，是正常返回值：切换失败时系统仍然可用（旧实例继续服务），
 * 不应该用异常这种"出错了"的语义去表达它，异常会诱使调用方写 try/catch 却忘记
 * 检查"旧实例到底还好不好用"这件真正要紧的事。
 */
public sealed interface SwitchResult
    permits SwitchResult.Switched, SwitchResult.Aborted, SwitchResult.NoOp {

    record Switched(String from, String to, long healthCheckMs) implements SwitchResult {}

    /** 引用未被触碰——旧实例继续服务；调用方必须告警，见 `AlertPort.warning`。 */
    record Aborted(String from, String attempted, String reason) implements SwitchResult {}

    record NoOp(String current) implements SwitchResult {}
}

