package com.trade.mall.agent.execution.domain;

/** 一次尝试的结局（VO）。DISPATCHING=已发出待应答。 */
public enum AttemptOutcome { DISPATCHING, SUCCESS, FAILED, UNKNOWN, NOT_SENT }

