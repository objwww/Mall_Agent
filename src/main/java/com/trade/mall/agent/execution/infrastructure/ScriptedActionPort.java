package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.execution.port.ActionPort;
import com.trade.mall.agent.execution.port.PortOutcome;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 脚本化 ActionPort（测试用）：按预先编排的脚本依次返回结果/抛出异常，
 * 并记录每个 operationId 的调用次数——D2 验收标准里反复出现的
 * "{@code verify(actionPort, times(1))}"（超时后确认只调用了一次），
 * 在没有 Mockito（Maven Central 被墙）的情况下用这个类的
 * {@link #callCount(String)} 等价替代。
 *
 * <p>生产实现见（计划中的）{@code HttpMallRefundActionPort}：真正的 HTTP 客户端，
 * 连接 3s / 请求整体 10s 超时，调用 mall-admin-server 的退款接口。</p>
 */
public final class ScriptedActionPort implements ActionPort {

    private final Map<String, Deque<Supplier<PortOutcome>>> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> executeCalls = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> queryCalls = new ConcurrentHashMap<>();

    /** 为某个 operationId 编排一串依次返回的结果（用完最后一个后重复返回它）。 */
    public ScriptedActionPort scriptOutcome(String operationId, PortOutcome... outcomes) {
        Deque<Supplier<PortOutcome>> q = new ArrayDeque<>();
        for (PortOutcome o : outcomes) q.add(() -> o);
        scripts.put(operationId, q);
        return this;
    }

    /** 为某个 operationId 编排一次调用直接抛出异常（模拟 ConnectException/超时等）。 */
    public ScriptedActionPort scriptThrow(String operationId, RuntimeException ex) {
        Deque<Supplier<PortOutcome>> q = new ArrayDeque<>();
        q.add(() -> { throw ex; });
        scripts.put(operationId, q);
        return this;
    }

    @Override
    public PortOutcome execute(ActionCommand command) {
        executeCalls.computeIfAbsent(command.operationId(), k -> new AtomicInteger()).incrementAndGet();
        return next(command.operationId());
    }

    @Override
    public PortOutcome query(String operationId) {
        queryCalls.computeIfAbsent(operationId, k -> new AtomicInteger()).incrementAndGet();
        return next(operationId);
    }

    private PortOutcome next(String operationId) {
        Deque<Supplier<PortOutcome>> q = scripts.get(operationId);
        if (q == null || q.isEmpty()) {
            throw new IllegalStateException("no script for operationId=" + operationId);
        }
        Supplier<PortOutcome> s = q.size() > 1 ? q.poll() : q.peek(); // 最后一个可重复取
        return s.get();
    }

    public int callCount(String operationId) {
        AtomicInteger c = executeCalls.get(operationId);
        return c == null ? 0 : c.get();
    }

    public int queryCallCount(String operationId) {
        AtomicInteger c = queryCalls.get(operationId);
        return c == null ? 0 : c.get();
    }
}

