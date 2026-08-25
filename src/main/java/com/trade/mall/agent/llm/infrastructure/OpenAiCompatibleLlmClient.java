package com.trade.mall.agent.llm.infrastructure;

import com.trade.mall.agent.llm.LlmClient;
import com.trade.mall.agent.llm.LlmQuotaException;
import com.trade.mall.agent.llm.LlmRequest;
import com.trade.mall.agent.llm.LlmResponse;
import com.trade.mall.agent.llm.LlmSchemaException;
import com.trade.mall.agent.llm.LlmTimeoutException;
import com.trade.mall.agent.llm.LlmUnavailableException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实 Chat Completions（对话补全）HTTP 客户端；只实现 mall-agent 当前真正使用的 JSON 输出能力。 */
public final class OpenAiCompatibleLlmClient implements LlmClient {
    private final String modelId;
    private final OpenAiCompatibleLlmClientFactory.Endpoint endpoint;
    private final HttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    OpenAiCompatibleLlmClient(String modelId, OpenAiCompatibleLlmClientFactory.Endpoint endpoint) {
        this.modelId = modelId;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder().connectTimeout(endpoint.timeout()).build();
    }

    @Override public String modelId() { return modelId; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (closed.get()) throw new LlmUnavailableException("LLM client already closed: " + modelId);
        String body = "{\"model\":\"" + esc(endpoint.remoteModel()) + "\","
            + "\"messages\":[{\"role\":\"system\",\"content\":\"" + esc(request.systemPrompt()) + "\"},"
            + "{\"role\":\"user\",\"content\":\"" + esc(request.userPrompt()) + "\"}],"
            + "\"response_format\":{\"type\":\"json_object\"},"
            + "\"max_tokens\":" + request.maxTokens() + "}";
        HttpRequest httpRequest = HttpRequest.newBuilder(chatCompletionUri(endpoint.baseUri()))
            .timeout(endpoint.timeout())
            .header("Authorization", "Bearer " + endpoint.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429) throw new LlmQuotaException("LLM quota/rate limit, HTTP 429");
            if (status == 408 || status == 504) throw new LlmTimeoutException("LLM upstream timeout, HTTP " + status);
            if (status < 200 || status >= 300) {
                throw new LlmUnavailableException("LLM HTTP " + status + ": " + abbreviate(response.body(), 256));
            }
            String content = extractFirstMessageContent(response.body());
            if (content == null || content.isBlank()) throw new LlmSchemaException("LLM response missing choices[0].message.content");
            return new LlmResponse(content, modelId);
        } catch (HttpTimeoutException e) {
            throw new LlmTimeoutException("LLM request timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM request interrupted", e);
        } catch (IOException e) {
            throw new LlmUnavailableException("LLM request unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean healthy() {
        try {
            LlmResponse r = complete(new LlmRequest(
                "Return JSON only. Output exactly {\"ok\":true}.", "health", 16));
            return r.content() != null && !r.content().isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public void shutdown(Duration grace) { closed.set(true); }

    private static URI chatCompletionUri(URI base) {
        String text = base.toString().replaceAll("/+$", "");
        if (text.endsWith("/chat/completions")) return URI.create(text);
        return URI.create(text + "/chat/completions");
    }

    /**
     * 只从 OpenAI-compatible 固定 envelope 中取第一个 message.content；不是通用 JSON parser。
     * 会正确跳过字符串转义，找不到/语法损坏时 fail closed。
     */
    static String extractFirstMessageContent(String json) {
        int choices = indexOfJsonKey(json, "choices", 0);
        if (choices < 0) return null;
        int message = indexOfJsonKey(json, "message", choices);
        if (message < 0) return null;
        int content = indexOfJsonKey(json, "content", message);
        if (content < 0) return null;
        int colon = skipWsToColon(json, content);
        if (colon < 0) return null;
        int p = skipWs(json, colon + 1);
        if (p >= json.length() || json.charAt(p) != '"') return null;
        return parseJsonString(json, p);
    }

    private static int indexOfJsonKey(String json, String key, int from) {
        String target = "\"" + key + "\"";
        int p = Math.max(0, from);
        while ((p = json.indexOf(target, p)) >= 0) {
            if (!isEscaped(json, p)) return p + target.length();
            p += target.length();
        }
        return -1;
    }

    private static int skipWsToColon(String s, int p) {
        p = skipWs(s, p);
        return p < s.length() && s.charAt(p) == ':' ? p : -1;
    }
    private static int skipWs(String s, int p) { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; return p; }
    private static boolean isEscaped(String s, int quote) { int bs=0; for(int i=quote-1;i>=0 && s.charAt(i)=='\\';i--) bs++; return (bs&1)==1; }

    private static String parseJsonString(String s, int quote) {
        StringBuilder out = new StringBuilder();
        for (int i = quote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (++i >= s.length()) return null;
            char e = s.charAt(i);
            switch (e) {
                case '"' -> out.append('"'); case '\\' -> out.append('\\'); case '/' -> out.append('/');
                case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n');
                case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= s.length()) return null;
                    try { out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); }
                    catch (NumberFormatException bad) { return null; }
                    i += 4;
                }
                default -> { return null; }
            }
        }
        return null;
    }

    private static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length() + 32);
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }
    private static String abbreviate(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0,n); }
}

