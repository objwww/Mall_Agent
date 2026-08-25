package com.trade.mall.agent.mcp;

import com.trade.mall.agent.llm.LlmJsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** MCP Streamable HTTP 最小客户端；只调用启动时白名单中的 readOnlyHint 工具。 */
public final class McpReadOnlyClient {
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;
    private final URI endpoint;
    private final String bearerToken;
    private final String allowedTool;
    private final HttpClient http;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;
    private volatile boolean initialized;

    public McpReadOnlyClient(URI endpoint, String bearerToken, String allowedTool, Duration timeout) {
        this.endpoint = requireSecure(endpoint);
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        if (!this.bearerToken.chars().allMatch(ch -> ch >= 0x21 && ch <= 0x7e)) {
            throw new IllegalArgumentException("MCP访问令牌只能包含可打印ASCII字符");
        }
        if (!isLoopback(endpoint) && this.bearerToken.isEmpty()) {
            throw new IllegalArgumentException("远程MCP必须配置访问令牌");
        }
        this.allowedTool = required(allowedTool, "MCP工具名");
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** 启动阶段完成协议协商和工具只读属性校验，避免运行到工单处理中才暴露错误配置。 */
    public void verify() { ensureInitialized(); }

    public String call(String argumentName, String argumentValue) {
        ensureInitialized();
        long id = ids.incrementAndGet();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
            + escape(allowedTool) + "\",\"arguments\":{\"" + escape(required(argumentName, "MCP参数名")) + "\":\""
            + escape(required(argumentValue, "MCP参数值")) + "\"}}}";
        Map<String,Object> response = request("tools/call", allowedTool, body, true);
        Map<String,Object> result = object(response.get("result"), "MCP调用结果");
        if (Boolean.TRUE.equals(result.get("isError"))) throw new IllegalStateException("MCP工具返回失败");
        StringBuilder text = new StringBuilder();
        for (Object item : list(result.get("content"), "MCP调用内容")) {
            Map<String,Object> content = object(item, "MCP内容项");
            if ("text".equals(content.get("type")) && content.get("text") instanceof String value) {
                if (text.length() > 0) text.append('\n');
                text.append(value);
            }
        }
        if (text.isEmpty()) throw new IllegalStateException("MCP工具未返回文本证据");
        if (text.length() > MAX_RESPONSE_CHARS) throw new IllegalStateException("MCP工具响应超过安全上限");
        System.out.println("级别=信息,动作=调用只读MCP工具,McpRequestId=" + id + ",tool=" + allowedTool + ",resultLength=" + text.length());
        return text.toString();
    }

    private synchronized void ensureInitialized() {
        if (initialized) return;
        long initId = ids.incrementAndGet();
        String init = "{\"jsonrpc\":\"2.0\",\"id\":" + initId + ",\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"" + PROTOCOL_VERSION + "\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"MallAgent\",\"version\":\"1.0\"}}}";
        Map<String,Object> initializedResponse = request("initialize", null, init, false);
        Map<String,Object> result = object(initializedResponse.get("result"), "MCP初始化结果");
        if (!PROTOCOL_VERSION.equals(result.get("protocolVersion"))) throw new IllegalStateException("MCP协议版本不匹配");
        request("notifications/initialized", null,
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", true);

        long listId = ids.incrementAndGet();
        Map<String,Object> listed = request("tools/list", null,
            "{\"jsonrpc\":\"2.0\",\"id\":" + listId + ",\"method\":\"tools/list\",\"params\":{}}", true);
        boolean allowedReadOnly = list(object(listed.get("result"), "MCP工具列表").get("tools"), "MCP工具列表").stream()
            .map(item -> object(item, "MCP工具"))
            .anyMatch(tool -> allowedTool.equals(tool.get("name"))
                && Boolean.TRUE.equals(object(tool.get("annotations"), "MCP工具注解").get("readOnlyHint")));
        if (!allowedReadOnly) throw new IllegalStateException("MCP白名单工具不存在或未声明只读：" + allowedTool);
        initialized = true;
        System.out.println("级别=信息,动作=初始化只读MCP连接,tool=" + allowedTool + ",protocol=" + PROTOCOL_VERSION);
    }

    private Map<String,Object> request(String method, String name, String body, boolean protocolHeader) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Method", method)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (name != null) builder.header("Mcp-Name", name);
            if (protocolHeader) builder.header("MCP-Protocol-Version", PROTOCOL_VERSION);
            if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
            if (!bearerToken.isEmpty()) builder.header("Authorization", "Bearer " + bearerToken);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            if (response.statusCode() == 202 && response.body().isBlank()) return Map.of();
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("MCP HTTP=" + response.statusCode());
            String json = response.body().stripLeading().startsWith("data:") ? sseData(response.body()) : response.body();
            Map<String,Object> parsed = LlmJsonUtil.parseFlatObject(json);
            if (parsed.containsKey("error")) throw new IllegalStateException("MCP JSON-RPC错误");
            return parsed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("MCP调用被中断", interrupted);
        } catch (RuntimeException failed) { throw failed; }
        catch (Exception failed) { throw new IllegalStateException("MCP调用失败", failed); }
    }

    private static String sseData(String body) {
        return body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).trim())
            .findFirst().orElseThrow(() -> new IllegalStateException("MCP SSE未返回data事件"));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value, String name) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalStateException(name + "格式非法");
        return (Map<String,Object>) map;
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value, String name) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException(name + "格式非法");
        return (List<Object>) list;
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空"); return value;
    }
    private static URI requireSecure(URI uri) {
        if (uri == null || uri.getHost() == null) throw new IllegalArgumentException("MCP地址非法");
        boolean loopback = isLoopback(uri);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("远程MCP必须使用HTTPS");
        }
        return uri;
    }
    private static boolean isLoopback(URI uri) {
        return "127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()) || "::1".equals(uri.getHost());
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
