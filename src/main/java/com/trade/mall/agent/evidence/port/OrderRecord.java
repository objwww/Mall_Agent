package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

import java.math.BigDecimal;

/**
 * `oms_order` 的只读行投影（`M-ADP-01`）——字段照抄 `document/sql/mall.sql` 里
 * `oms_order` 表定义的诊断相关子集，不是自己发明的 DTO。
 *
 * <p>{@code status} 沿用原表编码（0待付款/1待发货/2已发货/3已完成/4已关闭/5无效订单），
 * 不在这一层做翻译——翻译成人类可读的诊断语言是 D6+ 语义理解层的事，只读适配器只负责
 * 如实转述表里的原始值，多一层翻译就多一层"翻译错了"的风险面。</p>
 */
public record OrderRecord(
        long id,
        String orderSn,
        int status,
        String memberUsername,
        BigDecimal payAmount,
        long createTimeEpochMillis
) implements EvidencePayload {
}

