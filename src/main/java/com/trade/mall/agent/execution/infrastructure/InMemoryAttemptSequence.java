package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.application.AttemptSequence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存序号生成器（测试/演示用；生产实现走数据库自增列或计数表的 UPDATE ... SET seq=seq+1）。
 * 每个 operationId 独立计数，从 1 开始。
 */
public final class InMemoryAttemptSequence implements AttemptSequence {
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public int nextSeq(String operationId) {
        return counters.computeIfAbsent(operationId, k -> new AtomicInteger(0)).incrementAndGet();
    }
}

