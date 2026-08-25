package com.trade.mall.agent.execution.domain;

import java.util.Objects;

/**
 * 操作身份（Value Object）。唯一标识一次“业务意图”，跨重试保持不变。
 * 见 glossary.md「操作身份 Operation Identity」与 DDD §4。
 * 值对象：无生命周期、由值定义、不可变。
 */
public final class OperationId {
    private final String value;

    private OperationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        this.value = value;
    }

    public static OperationId of(String value) { return new OperationId(value); }

    public String value() { return value; }

    @Override public boolean equals(Object o) {
        return (o instanceof OperationId x) && value.equals(x.value);
    }
    @Override public int hashCode() { return Objects.hash(value); }
    @Override public String toString() { return value; }
}

