package com.trade.mall.agent.ledger;

/** 业务语义 eventId 构造器。集中在一处，保证跨模块一致、可复现。 */
public final class EventIds {
    private EventIds() {}
    public static String attemptDispatching(String opId, int seq) { return opId + ":DISPATCHING:" + seq; }
    public static String attemptOk(String opId, int seq)          { return opId + ":ATTEMPT_OK:" + seq; }
    public static String attemptFail(String opId, int seq)        { return opId + ":ATTEMPT_FAIL:" + seq; }
    public static String attemptUnknown(String opId, int seq)     { return opId + ":ATTEMPT_UNK:" + seq; }
    public static String settled(String opId)                     { return opId + ":SETTLED:1"; }
    public static String unknownTimeout(String opId, int seq)     { return opId + ":UNKNOWN:" + seq; }
    public static String unknownCrash(String opId, int seq)       { return opId + ":UNKNOWN:CRASH:" + seq; }
    public static String blocked(String opId, int seq)            { return opId + ":BLOCKED:" + seq; }
    public static String unblocked(String opId, int seq)          { return opId + ":UNBLOCKED:" + seq; }
    public static String reconcileResolved(String opId, int n)    { return opId + ":RECONCILE_OK:" + n; }
    public static String reconcileStillUnknown(String opId, int n){ return opId + ":RECONCILE_UNK:" + n; }
    public static String escalated(String opId)                   { return opId + ":ESCALATED:1"; }
}

