package com.trade.mall.agent.evidence.port;

/**
 * 只读适配器（`M-ADP-01`）层的数据源不可用信号——生产 JDBC/MyBatis（数据库访问）实现抛出的
 * {@code DataAccessException}/{@code CannotGetJdbcConnectionException} 之类的运行时异常，
 * 这里用一个专门的类型代替，语义更直白：连接池耗尽、目标库宕机、网络分区，都归为这一类。
 *
 * <p>与 {@code execution.port.DependencyUnavailableException}（D2）刻意不共享：那个异常
 * 描述的是"发钱的外部渠道确定没收到请求"，这个异常描述的是"查询只读库这件事本身失败了"——
 * 两者都会被各自域翻译成三值状态机的对应分支（{@code DispatchOutcome.Blocked} vs
 * {@code AcquireState.UNAVAILABLE}），但触发场景和归属的聚合完全不同，仍然遵循
 * §3.4（D4-REPORT.md）"两个域各自独立地体现同一条设计原则"那条理由。</p>
 */
public class DataSourceUnavailableException extends RuntimeException {
    public DataSourceUnavailableException(String message) { super(message); }
    public DataSourceUnavailableException(String message, Throwable cause) { super(message, cause); }
}

