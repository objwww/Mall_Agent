package com.trade.mall.agent.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpReadOnlyClientTest {
    @Test void 只调用配置白名单且服务端声明只读的真实工具() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, calls));
        server.start();
        try {
            McpReadOnlyClient client = new McpReadOnlyClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                "test-token", "query_order_trace", Duration.ofSeconds(2));

            client.verify();
            String result = client.call("traceId", "TRACE-1001");

            assertEquals("订单证据:TRACE-1001", result);
            assertEquals(4, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test void 远程地址必须使用加密连接和访问令牌() {
        assertThrows(IllegalArgumentException.class, () -> new McpReadOnlyClient(
            URI.create("http://example.com/mcp"), "token", "query", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new McpReadOnlyClient(
            URI.create("https://example.com/mcp"), "", "query", Duration.ofSeconds(1)));
    }

    private static void handle(HttpExchange exchange, AtomicInteger calls) throws IOException {
        calls.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
        assertEquals("POST", exchange.getRequestMethod());
        if (body.contains("\"method\":\"initialize\"")) {
            assertEquals("initialize", exchange.getRequestHeaders().getFirst("Mcp-Method"));
            exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
            reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"测试\",\"version\":\"1\"}}}");
        } else if (body.contains("notifications/initialized")) {
            assertSession(exchange);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        } else if (body.contains("\"method\":\"tools/list\"")) {
            assertSession(exchange);
            reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"query_order_trace\",\"description\":\"查询订单\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":true}}]}}");
        } else {
            assertSession(exchange);
            assertEquals("tools/call", exchange.getRequestHeaders().getFirst("Mcp-Method"));
            assertEquals("query_order_trace", exchange.getRequestHeaders().getFirst("Mcp-Name"));
            assertTrue(body.contains("\"traceId\":\"TRACE-1001\""));
            reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"订单证据:TRACE-1001\"}],\"isError\":false}}");
        }
    }

    private static void assertSession(HttpExchange exchange) {
        assertEquals("session-1", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
        assertEquals("2025-06-18", exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
