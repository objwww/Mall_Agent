package com.trade.mall.agent.execution.recovery;

/**
 * CrashRecoveryScanner —— M-EXEC-05。进程启动时（以及周期性地）把悬挂的执行
 * 一律置为 UNKNOWN，交给对账收敛。**不猜、不重发**——见类型所在包名本身就是一句约束：
 * 这个包里不允许出现任何指向 {@code ActionPort} 的依赖（INV-UNK-002 的结构性保证）。
 */
public interface CrashRecoveryScanner {
    /**
     * 扫描并恢复悬挂的执行。幂等、多实例安全、单条失败不中断整批。
     * @return 本次恢复的统计
     */
    RecoveryReport scan();
}

