package com.trade.mall.agent.evidence;

/**
 * 证据来源定位（值对象）——`INV-EVID-003`"每条证据可定位到表+主键或文件+行号"的落地。
 *
 * <p>{@code primaryKey} 允许为 {@code null}：{@link AcquireState#UNAVAILABLE} 的证据
 * 没有查到任何一行，自然没有主键可言，但**表名必须始终存在**——"我们本来想查哪张表"
 * 是即使查询失败也答得出来的事实，不能因为查询失败就连"查了什么"都说不清楚。
 * {@link AcquireState#PRESENT}/{@link AcquireState#EMPTY} 的证据必须同时具备表名与主键
 * （EMPTY 的"主键"是查询条件本身，如 {@code orderSn=XXX}，不是某一行的自增 id——
 * 见 {@code of(table, conditionOrKey)} 的用法约定）。</p>
 */
public record SourceLocator(String table, String primaryKey) implements java.io.Serializable {

    public SourceLocator {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
    }

    /** UNAVAILABLE 场景：只知道打算查哪张表，查询本身没能问出去，没有主键。 */
    public static SourceLocator tableOnly(String table) {
        return new SourceLocator(table, null);
    }

    /** PRESENT/EMPTY 场景：表名 + 主键（或用于 EMPTY 的查询条件描述）。 */
    public static SourceLocator of(String table, String primaryKeyOrCondition) {
        if (primaryKeyOrCondition == null || primaryKeyOrCondition.isBlank()) {
            throw new IllegalArgumentException("primaryKeyOrCondition must not be blank when not UNAVAILABLE");
        }
        return new SourceLocator(table, primaryKeyOrCondition);
    }
}

