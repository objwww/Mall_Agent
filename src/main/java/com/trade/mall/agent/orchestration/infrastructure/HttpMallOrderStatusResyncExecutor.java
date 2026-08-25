package com.trade.mall.agent.orchestration.infrastructure;

import com.trade.mall.agent.orchestration.NonFundActionExecutor;
import com.trade.mall.agent.orchestration.NonFundActionBusinessFailureException;
import com.trade.mall.agent.orchestration.NonFundActionUnavailableException;
import com.trade.mall.agent.proposal.ActionType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** ORDER_STATUS_RESYNC（订单状态核对）的真实 mall HTTP 适配器。 */
public final class HttpMallOrderStatusResyncExecutor implements NonFundActionExecutor {
    private final HttpClient client;
    private final URI endpoint;
    private final String tenantId;
    private final String apiKey;
    private final Duration timeout;

    public HttpMallOrderStatusResyncExecutor(URI mallBaseUri, String tenantId, String apiKey, Duration timeout) {
        this.endpoint=URI.create(mallBaseUri.toString().replaceAll("/+$", "") + "/internal/agent/payments/orders/resync");
        this.tenantId=require(tenantId,"tenantId"); this.apiKey=require(apiKey,"apiKey"); this.timeout=timeout;
        this.client=HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override public void execute(ActionType type, Map<String,String> params) { execute(params.getOrDefault("orderSn","UNKNOWN"), type, params); }

    @Override public void execute(String operationId, ActionType type, Map<String,String> params) {
        if(type!=ActionType.ORDER_STATUS_RESYNC) throw new IllegalArgumentException("unsupported non-fund action: "+type);
        String orderSn=require(params.get("orderSn"),"orderSn");
        String body="{\"operationId\":\""+esc(operationId)+"\",\"orderSn\":\""+esc(orderSn)
            +"\",\"actor\":\"mall-agent\",\"note\":\"ORDER_STATUS_NOT_SYNCED remediation\"}";
        HttpRequest req=HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type","application/json").header("X-Tenant-Id",tenantId).header("X-Agent-Api-Key",apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> rsp=client.send(req,HttpResponse.BodyHandlers.ofString());
            if (rsp.statusCode() >= 400 && rsp.statusCode() < 500)
                throw new NonFundActionBusinessFailureException("mall order resync HTTP " + rsp.statusCode());
            if (rsp.statusCode() < 200 || rsp.statusCode() >= 300)
                throw new NonFundActionUnavailableException("mall order resync HTTP " + rsp.statusCode());
            String gatewayStatus = extractJsonStringField(rsp.body(), "gatewayStatus");
            if (gatewayStatus == null || gatewayStatus.isBlank())
                throw new NonFundActionUnavailableException("mall order resync missing gatewayStatus");
        } catch(InterruptedException e){ Thread.currentThread().interrupt(); throw new NonFundActionUnavailableException("mall order resync interrupted",e); }
        catch(IOException e){ throw new NonFundActionUnavailableException("mall order resync unavailable",e); }
    }

    private static String extractJsonStringField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int p = json.indexOf(needle);
        if (p < 0) return null;
        p = json.indexOf(':', p + needle.length());
        if (p < 0) return null;
        p++; while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return null;
        StringBuilder out = new StringBuilder();
        for (int i = p + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return out.toString();
            if (c == '\\' && ++i < json.length()) { out.append(json.charAt(i)); } else out.append(c);
        }
        return null;
    }

    private static String require(String v,String n){ if(v==null||v.isBlank()) throw new IllegalArgumentException(n+" required"); return v; }
    private static String esc(String s){ return s.replace("\\","\\\\").replace("\"","\\\""); }
}

