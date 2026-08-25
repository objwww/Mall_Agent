package com.trade.mall.agent.approval;

import java.util.Objects;

/** 批准身份（值对象）。与 OperationId 同构：无生命周期、由值定义、不可变。 */
public final class ApprovalId {
    private final String value;

    private ApprovalId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("approvalId must not be blank");
        this.value = value;
    }

    public static ApprovalId of(String value) { return new ApprovalId(value); }

    public String value() { return value; }

    @Override public boolean equals(Object o) { return (o instanceof ApprovalId x) && value.equals(x.value); }
    @Override public int hashCode() { return Objects.hash(value); }
    @Override public String toString() { return value; }
}

