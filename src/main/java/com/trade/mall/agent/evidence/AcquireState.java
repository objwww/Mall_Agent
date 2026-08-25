package com.trade.mall.agent.evidence;

/**
 * 证据的获取状态（值对象/枚举）——`INV-EVID-001` 的三值本体。
 *
 * <p>三态刻意互斥、缺一不可（`glossary.md`）：</p>
 * <ul>
 *   <li>{@link #PRESENT} —— 查到了，`payload` 非空</li>
 *   <li>{@link #EMPTY} —— **确认没有**：查询成功执行，结果集就是空的（不是"查询失败"）</li>
 *   <li>{@link #UNAVAILABLE} —— **能力不可用**：数据源连不上/查询本身没能问出去，
 *       不代表"没有异常"，正确的下一步是"停下来报告数据源问题"，不是把它当 EMPTY 悄悄跳过</li>
 * </ul>
 *
 * <p>没有第四态 UNKNOWN——那是执行域（`execution.domain.ExecutionState`）对"发出去了但
 * 结局不明"的建模，证据域没有"发出动作"这回事，只有"问到了/问清楚没有/问不出去"，
 * 三态穷尽了取证场景的全部可能，不需要再借用执行域的 UNKNOWN 语义。</p>
 */
public enum AcquireState {
    PRESENT,
    EMPTY,
    UNAVAILABLE
}

