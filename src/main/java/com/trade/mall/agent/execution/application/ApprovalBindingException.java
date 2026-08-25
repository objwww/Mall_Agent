package com.trade.mall.agent.execution.application;

/**
 * 批准与本次要发出的动作参数不匹配（paramsHash 不一致）——INV-APPR-001。
 *
 * <p>这不是一个"业务上可能发生、需要优雅处理"的情形，而是调用方逻辑错误的信号：
 * 拿着 A 参数的批准，试图分发 B 参数的动作。属于程序缺陷而非运行时不确定性，
 * 所以不落一次状态转移、不消耗一次 Attempt 序号——分发器在做任何持久化写入之前
 * 就必须先失败，不留下任何痕迹（对照 T13：T13 是"发出瞬间才知道"，这里是
 * "发出之前就能确定"，两者在"何时能确定"这件事上有本质区别，所以处理方式不同：
 * 一个走状态转移，一个直接抛异常）。</p>
 */
public class ApprovalBindingException extends RuntimeException {
    public ApprovalBindingException(String message) { super(message); }
}

