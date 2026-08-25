package com.trade.mall.agent.orchestration;

/** 试图施加一条转移表里不存在的 (state, trigger) 组合——与 D1 `IllegalTransitionException` 同构。 */
public class IllegalDiagnosisTransitionException extends RuntimeException {
    public IllegalDiagnosisTransitionException(String message) { super(message); }
}

