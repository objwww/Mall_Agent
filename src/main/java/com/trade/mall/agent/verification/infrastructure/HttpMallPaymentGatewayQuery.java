package com.trade.mall.agent.verification.infrastructure;

import com.trade.mall.agent.evidence.port.PaymentGatewayReadPort;
import com.trade.mall.agent.evidence.port.PaymentGatewayRecord;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** mall 内部 API 暴露的支付网关纯查询；该 endpoint 本身不修改订单。 */
public final class HttpMallPaymentGatewayQuery implements PaymentGatewayReadPort {
    public record Result(String orderSn, String tradeStatus) {}
    private final HttpClient client; private final URI base; private final String tenantId; private final String apiKey; private final Duration timeout;
    public HttpMallPaymentGatewayQuery(URI mallBaseUri,String tenantId,String apiKey,Duration timeout){
        this.base=mallBaseUri; this.tenantId=tenantId; this.apiKey=apiKey; this.timeout=timeout; this.client=HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Optional<PaymentGatewayRecord> findByOrderSn(String orderSn) {
        Result result = query(orderSn);
        return Optional.of(new PaymentGatewayRecord(result.orderSn(), result.tradeStatus()));
    }

    public Result query(String orderSn){
        String root=base.toString().replaceAll("/+$","");
        URI uri=URI.create(root+"/internal/agent/payments/orders/"+URLEncoder.encode(orderSn, StandardCharsets.UTF_8)+"/gateway");
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(timeout).header("X-Tenant-Id",tenantId).header("X-Agent-Api-Key",apiKey).GET().build();
        try{
            HttpResponse<String> rsp=client.send(req,HttpResponse.BodyHandlers.ofString());
            if(rsp.statusCode()<200||rsp.statusCode()>=300) throw new IllegalStateException("payment gateway query HTTP "+rsp.statusCode());
            String tradeStatus = extractJsonStringField(rsp.body(), "tradeStatus");
            if (tradeStatus == null || tradeStatus.isBlank()) throw new IllegalStateException("gateway query missing tradeStatus");
            return new Result(orderSn, tradeStatus);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("gateway query interrupted",e);}
        catch(IOException e){throw new IllegalStateException("gateway query unavailable",e);}
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
            if (c == '\\' && ++i < json.length()) out.append(json.charAt(i)); else out.append(c);
        }
        return null;
    }
}

