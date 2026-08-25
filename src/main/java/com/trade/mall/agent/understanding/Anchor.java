package com.trade.mall.agent.understanding;

/** 从工单自由文本里提出的锚点（值对象）——理解层的产出，供 D5 的证据采集器直接消费。 */
public record Anchor(AnchorType type, String value) implements java.io.Serializable {
    public Anchor {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value must not be blank");
    }
}

