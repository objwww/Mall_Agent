package com.trade.mall.agent.runtime;

import com.trade.mall.agent.alert.AlertPort;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 最小常驻调度器：固定频率调用 DurableMallAgentRuntime.maintain（耐久维护周期）。
 * 不引入 Quartz/Kafka/工作流引擎；多实例安全依赖数据库短租约与乐观锁。
 */
public final class DurableMallAgentScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mall-agent-maintenance");t.setDaemon(true);return t;});
    private final DurableMallAgentRuntime runtime; private final AlertPort alerts; private final int batch;
    public DurableMallAgentScheduler(DurableMallAgentRuntime runtime, AlertPort alerts, Duration interval, int batch){
        this.runtime=runtime;this.alerts=alerts;this.batch=batch;
        long ms=Math.max(1000L,interval.toMillis());
        executor.scheduleWithFixedDelay(this::runOnce,0,ms,TimeUnit.MILLISECONDS);
    }
    private void runOnce(){ try{runtime.maintain(batch);}catch(RuntimeException e){alerts.critical("mallagent.maintenance.failed","MallAgent 常驻维护周期失败："+e.getMessage());} }
    @Override public void close(){executor.shutdown();}
}

