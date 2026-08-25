package com.trade.mall.agent.runtime.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trade.mall.agent.llm.LlmJsonUtil;
import com.trade.mall.agent.runtime.AgentOperationReporter;
import com.trade.mall.agent.orchestration.ApprovalDecision;
import com.trade.mall.agent.orchestration.DiagnosisOrchestrator;
import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisRunStore;
import com.trade.mall.agent.proposal.Proposal;
import com.trade.mall.agent.reasoning.FindingResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent Runtime（智能代理运行时）的最小控制面 HTTP 接口。
 * 只暴露创建/查询 Diagnosis（诊断）与提交审批决定；服务间身份使用 Bearer Key（持有者密钥）。
 */
public final class AgentControlHttpServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private final HttpServer server;
    private final ExecutorService executor;
    private final DiagnosisOrchestrator orchestrator;
    private final DiagnosisRunStore store;
    private final AgentOperationReporter reporter;
    private final byte[] expectedBearer;

    public AgentControlHttpServer(InetSocketAddress address, String apiKey,
                                  DiagnosisOrchestrator orchestrator, DiagnosisRunStore store,
                                  AgentOperationReporter reporter) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("control apiKey must not be blank");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.store = Objects.requireNonNull(store, "store");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.expectedBearer = ("Bearer " + apiKey.trim()).getBytes(StandardCharsets.UTF_8);
        try { this.server = HttpServer.create(Objects.requireNonNull(address, "address"), 0); }
        catch (IOException e) { throw new IllegalStateException("cannot bind Agent control HTTP server", e); }
        this.executor = Executors.newFixedThreadPool(4);
        this.server.setExecutor(executor);
        this.server.createContext("/internal/v1/diagnoses", this::handleDiagnoses);
        this.server.createContext("/internal/v1/health", this::handleHealth);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }


    private void handleHealth(HttpExchange exchange) throws IOException {
        String traceId = prepareTraceId(exchange);
        try {
            logInfo(traceId, "健康检查入参", "method=" + exchange.getRequestMethod());
            if (!authenticated(exchange)) { send(exchange, 401, jsonError("unauthorized")); return; }
            if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, jsonError("method_not_allowed")); return; }
            send(exchange, 200, "{\"status\":\"UP\"}");
            logInfo(traceId, "健康检查出参", "status=200,result=UP");
        } finally { exchange.close(); }
    }

    private void handleDiagnoses(HttpExchange exchange) throws IOException {
        String traceId = prepareTraceId(exchange);
        try {
            logInfo(traceId, "诊断接口入参", "method=" + exchange.getRequestMethod() + ",path=" + exchange.getRequestURI().getPath());
            if (!authenticated(exchange)) { send(exchange, 401, jsonError("unauthorized")); return; }
            String path = exchange.getRequestURI().getPath();
            String base = "/internal/v1/diagnoses";
            String rest = path.length() <= base.length() ? "" : path.substring(base.length());
            if ((rest.isEmpty() || rest.equals("/")) && exchange.getRequestMethod().equals("POST")) {
                create(exchange); return;
            }
            if (rest.startsWith("/")) rest = rest.substring(1);
            String[] parts = rest.split("/");
            if (parts.length == 1 && !parts[0].isBlank() && exchange.getRequestMethod().equals("GET")) {
                get(exchange, parts[0]); return;
            }
            if (parts.length == 2 && !parts[0].isBlank() && parts[1].equals("approval")
                    && exchange.getRequestMethod().equals("POST")) {
                approve(exchange, parts[0]); return;
            }
            send(exchange, 404, jsonError("not_found"));
        } catch (IllegalArgumentException bad) {
            logWarn(traceId, "诊断接口参数错误", bad.getMessage());
            send(exchange, 400, jsonError(bad.getMessage()));
        } catch (IllegalStateException conflict) {
            logWarn(traceId, "诊断接口状态冲突", conflict.getMessage());
            send(exchange, 409, jsonError(conflict.getMessage()));
        } catch (RuntimeException failed) {
            logWarn(traceId, "诊断接口运行失败", failed.getClass().getSimpleName());
            send(exchange, 500, jsonError("runtime_failure"));
        } finally {
            exchange.close();
        }
    }

    private void create(HttpExchange exchange) throws IOException {
        Map<String,Object> body = parseBody(exchange);
        String ticketSn = required(body, "ticketSn");
        String diagnosisId = required(body, "diagnosisId");
        String freeText = required(body, "freeText");
        DiagnosisRun run = store.find(diagnosisId).orElseGet(() -> orchestrator.runToApproval(ticketSn, diagnosisId, freeText));
        if (!run.ticketSn().equals(ticketSn)) {
            throw new IllegalStateException("diagnosisId already bound to another ticket");
        }
        reporter.sync(run, responseTraceId(exchange));
        send(exchange, 200, render(run));
        logInfo(responseTraceId(exchange), "创建诊断出参",
            "ticketSn=" + ticketSn + ",diagnosisId=" + diagnosisId + ",state=" + run.state() + ",seq=" + run.seq());
    }

    private void get(HttpExchange exchange, String diagnosisId) throws IOException {
        DiagnosisRun run = store.find(diagnosisId).orElse(null);
        if (run == null) { send(exchange, 404, jsonError("diagnosis_not_found")); return; }
        reporter.sync(run, responseTraceId(exchange));
        send(exchange, 200, render(run));
        logInfo(responseTraceId(exchange), "查询诊断出参",
            "diagnosisId=" + diagnosisId + ",state=" + run.state() + ",seq=" + run.seq());
    }

    private void approve(HttpExchange exchange, String diagnosisId) throws IOException {
        Map<String,Object> body = parseBody(exchange);
        String decisionRaw = required(body, "decision");
        String approverId = required(body, "approverId"); // 只能由已认证 mall-admin 服务端从会话转发。
        ApprovalDecision decision;
        try { decision = ApprovalDecision.valueOf(decisionRaw); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid decision"); }
        if (decision == ApprovalDecision.LET_EXPIRE) throw new IllegalArgumentException("LET_EXPIRE is scheduler-only");
        DiagnosisRun run = orchestrator.resumeAfterApproval(diagnosisId, decision, approverId);
        reporter.sync(run, responseTraceId(exchange));
        send(exchange, 200, render(run));
        logInfo(responseTraceId(exchange), "审批诊断出参",
            "diagnosisId=" + diagnosisId + ",decision=" + decision + ",approverId=" + approverId + ",state=" + run.state());
    }

    private Map<String,Object> parseBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("request body too large");
        String text = new String(bytes, StandardCharsets.UTF_8);
        try { return LlmJsonUtil.parseFlatObject(text); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid json"); }
    }

    private static String required(Map<String,Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("missing " + key);
        return s;
    }

    private boolean authenticated(HttpExchange exchange) {
        String actual = exchange.getRequestHeaders().getFirst("Authorization");
        if (actual == null) return false;
        return MessageDigest.isEqual(expectedBearer, actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String prepareTraceId(HttpExchange exchange) {
        String traceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isBlank() || traceId.length() > 128) traceId = UUID.randomUUID().toString();
        else traceId = traceId.trim();
        exchange.getResponseHeaders().set("X-Trace-Id", traceId);
        return traceId;
    }

    private static String responseTraceId(HttpExchange exchange) {
        return exchange.getResponseHeaders().getFirst("X-Trace-Id");
    }

    private static void logInfo(String traceId, String action, String detail) {
        System.out.println("级别=信息,TraceId=" + traceId + ",动作=" + action + "," + detail);
    }

    private static void logWarn(String traceId, String action, String detail) {
        System.err.println("级别=警告,TraceId=" + traceId + ",动作=" + action + ",原因=" + detail);
    }

    private static String render(DiagnosisRun run) {
        StringBuilder out = new StringBuilder("{");
        field(out, "diagnosisId", run.diagnosisId()); comma(out);
        field(out, "ticketSn", run.ticketSn()); comma(out);
        field(out, "state", run.state().name()); comma(out);
        out.append("\"seq\":").append(run.seq());
        if (run.approvalId() != null) { comma(out); field(out, "approvalId", run.approvalId()); }
        if (run.finding() instanceof FindingResult.Concluded f) {
            comma(out); field(out, "findingType", f.findingType().name());
            comma(out); out.append("\"confidence\":").append(f.confidence());
        }
        Proposal proposal = run.proposal();
        if (proposal != null) {
            comma(out); field(out, "proposalId", proposal.proposalId());
            comma(out); field(out, "actionType", proposal.actionType().name());
            comma(out); field(out, "operationId", proposal.operationId());
            comma(out); field(out, "paramsHash", proposal.paramsHash());
            comma(out); out.append("\"params\":{");
            boolean first = true;
            for (Map.Entry<String,String> e : proposal.params().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (!first) out.append(','); first = false;
                field(out, e.getKey(), e.getValue());
            }
            out.append('}');
        }
        out.append('}');
        return out.toString();
    }

    private static String jsonError(String message) {
        StringBuilder out = new StringBuilder("{"); field(out, "error", message == null ? "error" : message); return out.append('}').toString();
    }
    private static void comma(StringBuilder out) { out.append(','); }
    private static void field(StringBuilder out, String key, String value) {
        out.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
    }
    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }
    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() { server.stop(0); executor.shutdown(); }
}
