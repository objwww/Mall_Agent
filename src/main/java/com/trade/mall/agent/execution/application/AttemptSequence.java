package com.trade.mall.agent.execution.application;

/**
 * 尝试序号生成器（端口）。每个 operationId 独立计数，从 1 开始，严格递增。
 *
 * <p>为什么不直接用聚合内 attempts.size()+1：分发前聚合可能还没被载入
 * （典型场景：先分配 seq、把它塞进 TransitionContext，再统一交给
 * ExecutionApplicationService.transition() 去 load+apply+save），
 * 序号生成是一个独立的、有自己并发/持久化需求的关注点。
 * 生产实现：数据库自增列，或按 operationId 一行的计数表（UPDATE ... SET seq=seq+1）。</p>
 */
public interface AttemptSequence {
    int nextSeq(String operationId);
}

