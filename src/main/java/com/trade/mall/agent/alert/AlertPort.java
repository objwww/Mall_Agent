package com.trade.mall.agent.alert;

/**
 * AlertPort —— 极小的告警端口，供恢复/对账/配置热更新在"人必须知道"的时刻发声。
 *
 * <p>典型场景：崩溃恢复批次里有条目恢复失败（`failed>0`）——悬挂执行可能已经产生了
 * 真实的外部副作用，恢复失败意味着它连"进入 UNKNOWN 排队对账"这一步都没做到，
 * 必须有人工介入去看，不能只是打一行 ERROR 日志就算了。</p>
 *
 * <p><b>D6 新增 {@link #warning}</b>：D3 建立本接口时只有一种严重度（`critical`，
 * 对应"可能已经产生真实副作用、必须立刻有人看"），但 `M-LLM-01` 的模型切换失败
 * 是另一类性质不同的信号——系统仍然可用（旧模型还在正常服务），只是"运维意图没被满足"
 * （改了配置以为换成了，其实没换成）。硬把它塞进 `critical()` 会让真正紧急的资金类
 * 告警和"配置没生效"这种运维提醒混在同一个严重度里，被同样对待、同样的响应 SLA 反而
 * 不合理。新增 `warning()` 是最小的、向后兼容的扩展：D3 已有的
 * `DefaultCrashRecoveryScanner`/`ReconcileScheduler` 调用点一行没改，
 * `InMemoryAlertPort` 只是多记一类。</p>
 */
public interface AlertPort {
    void critical(String code, String message);

    void warning(String code, String message);
}

