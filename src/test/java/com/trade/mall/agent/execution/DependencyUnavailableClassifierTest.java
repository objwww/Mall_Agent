package com.trade.mall.agent.execution;

import com.trade.mall.agent.execution.application.DependencyUnavailableClassifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/** 与 SelfCheck §9 一一对应。红线：超时绝不能被识别为"确定未发出"。 */
class DependencyUnavailableClassifierTest {

    @Test void connect_exception_is_never_sent() {
        assertTrue(DependencyUnavailableClassifier.isDependencyUnavailable(new ConnectException("refused")));
    }

    @Test void unknown_host_is_never_sent() {
        assertTrue(DependencyUnavailableClassifier.isDependencyUnavailable(new UnknownHostException("x")));
    }

    @Test void timeout_is_not_never_sent() {
        assertFalse(DependencyUnavailableClassifier.isDependencyUnavailable(new SocketTimeoutException("timeout")),
            "超时是说不清，绝不能被当成确定未发出——否则违反 INV-UNK-001 的精神");
    }

    @Test void plain_io_exception_is_not_never_sent() {
        assertFalse(DependencyUnavailableClassifier.isDependencyUnavailable(new IOException("connection reset")));
    }

    @Test void wrapped_cause_chain_is_still_detected() {
        // 真实 HttpMallRefundActionPort 的做法：ConnectException 被包一层 RuntimeException 再抛出。
        var wrapped = new RuntimeException("HTTP call failed", new ConnectException("refused"));
        assertTrue(DependencyUnavailableClassifier.isDependencyUnavailable(wrapped));
    }

    @Test void not_configured_message_variants_F001() {
        assertTrue(DependencyUnavailableClassifier.isNotConfiguredMessage("该退款渠道未配置"));
        assertTrue(DependencyUnavailableClassifier.isNotConfiguredMessage("channel is not configured"));
        assertFalse(DependencyUnavailableClassifier.isNotConfiguredMessage("余额不足，退款失败"));
    }
}

