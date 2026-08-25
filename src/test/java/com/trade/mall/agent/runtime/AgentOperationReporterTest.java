package com.trade.mall.agent.runtime;

import com.sun.net.httpserver.HttpServer;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.infrastructure.InMemoryEventLedger;
import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisState;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOperationReporterTest {

    @Test
    void 终态诊断上报开始工具调用和结束三类真实事件() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertEquals("ingest-key", exchange.getRequestHeaders().getFirst("X-Agent-Ingest-Key"));
            requests.add(exchange.getRequestURI().getPath() + "\n"
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            InMemoryEventLedger ledger = new InMemoryEventLedger();
            ledger.append(new DomainEvent("diag-1:ORDER:COLLECTED:1", "diag-1",
                "Evidence.Collected", 0, "ORDER locator=oms_order", 100L));
            AgentOperationReporter reporter = new AgentOperationReporter(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "ingest-key",
                "handler-model", "prompt-v1", "skill-v1", "tool-v1", ledger, Duration.ofSeconds(2));
            DiagnosisRun run = new DiagnosisRun("ticket-1", "diag-1", DiagnosisState.RESOLVED,
                9, null, null, null, null, null, null);

            assertTrue(reporter.sync(run, "trace-1", 7L));
            assertEquals(3, requests.size());
            assertTrue(requests.get(0).contains("/agentRuntime/v1/runs/start"));
            assertTrue(requests.get(0).contains("\"agentRole\":\"MALL_HANDLER\""));
            assertTrue(requests.get(0).contains("\"evaluationRunId\":7"));
            assertTrue(requests.get(1).contains("/tool-calls"));
            assertTrue(requests.get(1).contains("\"toolName\":\"evidence.order\""));
            assertTrue(requests.get(2).contains("/finish"));
            assertTrue(requests.get(2).contains("\"status\":\"SUCCESS\""));
        } finally {
            server.stop(0);
        }
    }
}
