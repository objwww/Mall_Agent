package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.application.DependencyUnavailableClassifier;
import com.trade.mall.agent.execution.port.ActionCommand;
import com.trade.mall.agent.execution.port.ActionPort;
import com.trade.mall.agent.execution.port.DependencyUnavailableException;
import com.trade.mall.agent.execution.port.PortOutcome;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mall（商城系统）退款 HTTP（网络）适配器。
 *
 * <p>严格对齐 AgentAfterSalesController（Agent 售后控制器）的真实契约：执行使用 POST
 * /internal/agent/after-sales/refunds，查询使用 GET
 * /internal/agent/after-sales/refunds/operations/{operationId}，两者都携带租户和 Agent API 密钥。</p>
 *
 * <p>响应不再假设存在 {@code code=0}；只认 AgentRefundView（Agent 退款视图）的 status 字段，
 * 缺失/未知/PENDING/PROCESSING/UNKNOWN 一律返回 Inconclusive（结果不确定），绝不把普通 2xx
 * 或解析失败猜成成功。</p>
 */
public final class HttpMallRefundActionPort implements ActionPort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern STATUS = Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern REFUND_SN = Pattern.compile("\\\"refundSn\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern ERROR = Pattern.compile("\\\"error\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|null)");

    private final HttpClient client;
    private final String refundEndpoint;
    private final String queryEndpointPrefix;
    private final String tenantId;
    private final String apiKey;

    public HttpMallRefundActionPort(String mallBaseUrl, String tenantId, String apiKey) {
        if (mallBaseUrl == null || mallBaseUrl.isBlank()) throw new IllegalArgumentException("mallBaseUrl must not be blank");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey must not be blank");
        String base = mallBaseUrl.endsWith("/") ? mallBaseUrl.substring(0, mallBaseUrl.length() - 1) : mallBaseUrl;
        this.refundEndpoint = base + "/internal/agent/after-sales/refunds";
        this.queryEndpointPrefix = base + "/internal/agent/after-sales/refunds/operations/";
        this.tenantId = tenantId;
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public PortOutcome execute(ActionCommand command) {
        String body = addOperationId(command.paramsJson(), command.operationId());
        HttpRequest request = common(HttpRequest.newBuilder(URI.create(refundEndpoint)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return send(request);
    }

    @Override
    public PortOutcome query(String operationId) {
        String encoded = URLEncoder.encode(operationId, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = common(HttpRequest.newBuilder(URI.create(queryEndpointPrefix + encoded)))
            .GET().build();
        return send(request);
    }

    private HttpRequest.Builder common(HttpRequest.Builder builder) {
        return builder.timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("X-Tenant-Id", tenantId)
            .header("X-Agent-Api-Key", apiKey);
    }

    private PortOutcome send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP call failed: " + request.uri(), e);
        }
        return mapResponse(response.statusCode(), response.body());
    }

    static PortOutcome mapResponse(int statusCode, String rawBody) {
        String body = rawBody == null ? "" : rawBody;
        if (DependencyUnavailableClassifier.isNotConfiguredMessage(body)) {
            throw new DependencyUnavailableException("mall channel not configured: " + body);
        }
        if (statusCode / 100 != 2) {
            return new PortOutcome.Inconclusive("http status " + statusCode);
        }

        String status = extract(STATUS, body);
        if (status == null || status.isBlank()) {
            return new PortOutcome.Inconclusive("2xx response missing AgentRefundView.status");
        }
        return switch (status) {
            case "SUCCEEDED" -> new PortOutcome.Success(orEmpty(extract(REFUND_SN, body)));
            case "FAILED" -> new PortOutcome.BusinessFailure("REFUND_FAILED", orEmpty(extract(ERROR, body)));
            case "PENDING", "PROCESSING", "UNKNOWN" -> new PortOutcome.Inconclusive("refund status " + status);
            default -> new PortOutcome.Inconclusive("unknown refund status " + status);
        };
    }

    static String addOperationId(String paramsJson, String operationId) {
        if (paramsJson == null) throw new IllegalArgumentException("paramsJson must not be null");
        String json = paramsJson.strip();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("paramsJson must be a JSON object");
        }
        String inside = json.substring(1, json.length() - 1).strip();
        String prefix = "{\"operationId\":\"" + escapeJson(operationId) + "\"";
        return inside.isEmpty() ? prefix + "}" : prefix + "," + inside + "}";
    }

    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String orEmpty(String value) { return value == null ? "" : value; }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

