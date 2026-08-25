package com.trade.mall.agent.approval;

import com.trade.mall.agent.ledger.DomainEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Approval（审批）聚合：绑定具体 operationId（操作编号）+ actionType（动作类型）+
 * actionVersion（动作版本）+ paramsHash（参数哈希），并带明确 expiresAt（过期时间）。
 */
public final class Approval {
    private final ApprovalId id;
    private final String operationId;
    private final String actionType;
    private final String actionVersion;
    private final String paramsHash;
    private final long expiresAt;
    private ApprovalState state;
    private String approverId;
    private long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private Approval(ApprovalId id, String operationId, String actionType, String actionVersion, String paramsHash,
                     long expiresAt, ApprovalState state, String approverId, long version) {
        this.id=id; this.operationId=operationId; this.actionType=actionType; this.actionVersion=actionVersion;
        this.paramsHash=paramsHash; this.expiresAt=expiresAt; this.state=state; this.approverId=approverId; this.version=version;
    }

    /** 旧测试入口兼容；生产请经 ApprovalGate.request 使用有限 TTL。 */
    public static Approval request(ApprovalId id, String operationId, String actionVersion, String paramsHash) {
        return request(id, operationId, "UNKNOWN", actionVersion, paramsHash, Long.MAX_VALUE, 0L);
    }

    public static Approval request(ApprovalId id, String operationId, String actionType, String actionVersion,
                                   String paramsHash, long expiresAt, long now) {
        if (expiresAt <= now) throw new IllegalArgumentException("approval expiresAt must be in the future");
        Approval approval = new Approval(id, operationId, actionType, actionVersion, paramsHash,
            expiresAt, ApprovalState.PENDING, null, 0L);
        String payload = "operationId=" + operationId + ",actionType=" + actionType + ",actionVersion=" + actionVersion
            + ",paramsHash=" + paramsHash + ",expireAt=" + expiresAt;
        approval.pendingEvents.add(new DomainEvent(ApprovalEventIds.requested(id.value()), id.value(),
            "Approval.Requested", 0, payload, now));
        return approval;
    }

    public static Approval rehydrate(ApprovalId id, String operationId, String actionVersion, String paramsHash,
                                     ApprovalState state, String approverId, long version) {
        return new Approval(id, operationId, "UNKNOWN", actionVersion, paramsHash, Long.MAX_VALUE, state, approverId, version);
    }

    public static Approval rehydrate(ApprovalId id, String operationId, String actionType, String actionVersion,
                                     String paramsHash, long expiresAt, ApprovalState state, String approverId, long version) {
        return new Approval(id, operationId, actionType, actionVersion, paramsHash, expiresAt, state, approverId, version);
    }

    public void apply(ApprovalTrigger trigger, String approverIdIfDecision, long now) {
        if (state.isTerminal()) throw new IllegalApprovalTransitionException(
            "terminal approval state is immutable: " + id + " state=" + state + " trigger=" + trigger);
        ApprovalState to = ApprovalTransitionPolicy.next(state, trigger).orElseThrow(() ->
            new IllegalApprovalTransitionException("no approval transition: " + state + " --" + trigger + "--> ? (approvalId=" + id + ")"));
        if (trigger == ApprovalTrigger.GRANT || trigger == ApprovalTrigger.REJECT) {
            if (approverIdIfDecision == null || approverIdIfDecision.isBlank()) throw new IllegalArgumentException("approverId required for " + trigger);
            this.approverId = approverIdIfDecision;
        }
        this.state=to; pendingEvents.add(eventFor(trigger,now));
    }

    private DomainEvent eventFor(ApprovalTrigger trigger,long now){
        return switch(trigger){
            case GRANT -> new DomainEvent(ApprovalEventIds.granted(id.value()),id.value(),"Approval.Granted",0,approverId,now);
            case REJECT -> new DomainEvent(ApprovalEventIds.rejected(id.value()),id.value(),"Approval.Rejected",0,approverId==null?"":approverId,now);
            case CONSUME -> new DomainEvent(ApprovalEventIds.consumed(id.value()),id.value(),"Approval.Consumed",0,operationId,now);
            case EXPIRE -> new DomainEvent(ApprovalEventIds.expired(id.value()),id.value(),"Approval.Expired",0,"expiredAt="+now,now);
        };
    }

    public boolean dueToExpire(long now){ return !state.isTerminal() && now >= expiresAt; }
    public ApprovalId id(){return id;} public String operationId(){return operationId;} public String actionType(){return actionType;}
    public String actionVersion(){return actionVersion;} public String paramsHash(){return paramsHash;} public long expiresAt(){return expiresAt;}
    public ApprovalState state(){return state;} public String approverId(){return approverId;} public long version(){return version;}
    public List<DomainEvent> pendingEvents(){return List.copyOf(pendingEvents);} public void clearPendingEvents(){pendingEvents.clear();}
}

