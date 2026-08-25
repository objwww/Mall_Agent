package com.trade.mall.agent.runtime.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trade.mall.agent.llm.LlmJsonUtil;
import com.trade.mall.agent.llm.PromptSnapshot;
import com.trade.mall.agent.llm.PromptVersionStore;
import com.trade.mall.agent.llm.SkillSnapshot;
import com.trade.mall.agent.llm.SkillVersionStore;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.llm.infrastructure.InMemorySkillVersionStore;
import com.trade.mall.agent.runtime.AgentOperationReporter;
import com.trade.mall.agent.runtime.ToolManifest;
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
import java.util.HexFormat;
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
    private final PromptVersionStore prompts;
    private final SkillVersionStore skills;
    private final ToolManifest tools;
    private final byte[] expectedBearer;

    public AgentControlHttpServer(InetSocketAddress address, String apiKey,
                                  DiagnosisOrchestrator orchestrator, DiagnosisRunStore store,
                                  AgentOperationReporter reporter, PromptVersionStore prompts) {
        this(address, apiKey, orchestrator, store, reporter, prompts,
            new InMemorySkillVersionStore(VersionSnapshot.LEGACY_SKILL_VERSION, ""));
    }

    public AgentControlHttpServer(InetSocketAddress address, String apiKey,
                                  DiagnosisOrchestrator orchestrator, DiagnosisRunStore store,
                                  AgentOperationReporter reporter, PromptVersionStore prompts,
                                  SkillVersionStore skills) {
        this(address, apiKey, orchestrator, store, reporter, prompts, skills,
            ToolManifest.from("legacy-no-manifest", java.util.List.of(), java.util.List.of()));
    }

    public AgentControlHttpServer(InetSocketAddress address, String apiKey,
                                  DiagnosisOrchestrator orchestrator, DiagnosisRunStore store,
                                  AgentOperationReporter reporter, PromptVersionStore prompts,
                                  SkillVersionStore skills, ToolManifest tools) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("control apiKey must not be blank");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.store = Objects.requireNonNull(store, "store");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.expectedBearer = ("Bearer " + apiKey.trim()).getBytes(StandardCharsets.UTF_8);
        try { this.server = HttpServer.create(Objects.requireNonNull(address, "address"), 0); }
        catch (IOException e) { throw new IllegalStateException("cannot bind Agent control HTTP server", e); }
        this.executor = Executors.newFixedThreadPool(4);
        this.server.setExecutor(executor);
        this.server.createContext("/internal/v1/diagnoses", this::handleDiagnoses);
        this.server.createContext("/internal/v1/health", this::handleHealth);
        this.server.createContext("/internal/v1/prompts", this::handlePrompts);
        this.server.createContext("/internal/v1/skills", this::handleSkills);
        this.server.createContext("/internal/v1/tools", this::handleTools);
        this.server.createContext("/v1/runs", this::handleEvaluationRun);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handleTools(HttpExchange exchange) throws IOException {
        String traceId = prepareTraceId(exchange);
        try {
            logInfo(traceId, "查询工具清单入参", "method=" + exchange.getRequestMethod());
            if (!authenticated(exchange)) { send(exchange, 401, jsonError("unauthorized")); return; }
            if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, jsonError("method_not_allowed")); return; }
            send(exchange, 200, renderTools(tools));
            logInfo(traceId, "查询工具清单出参", "version=" + tools.version() + ",digest=" + tools.digest() + ",count=" + tools.tools().size());
        } finally { exchange.close(); }
    }

    private void handleSkills(HttpExchange exchange) throws IOException {
        String traceId = prepareTraceId(exchange);
        try {
            if (!authenticated(exchange)) { send(exchange, 401, jsonError("unauthorized")); return; }
            String path = exchange.getRequestURI().getPath();
            String base = "/internal/v1/skills";
            String rest = path.length() <= base.length() ? "" : path.substring(base.length());
            logInfo(traceId, "技能版本接口入参", "method=" + exchange.getRequestMethod() + ",path=" + path);
            if ((rest.isEmpty() || rest.equals("/")) && exchange.getRequestMethod().equals("GET")) {
                int limit = queryLimit(exchange.getRequestURI().getRawQuery());
                send(exchange, 200, renderSkillHistory(skills.history(limit)));
                logInfo(traceId, "查询技能历史出参", "limit=" + limit + ",currentVersion=" + skills.currentVersion());
                return;
            }
            if ((rest.isEmpty() || rest.equals("/")) && exchange.getRequestMethod().equals("POST")) {
                Map<String,Object> body = parseBody(exchange);
                String version = promptVersion(required(body, "version"));
                String instructions = required(body, "instructions");
                if (instructions.length() > 60_000) throw new IllegalArgumentException("skill instructions too long");
                skills.publish(version, instructions);
                send(exchange, 200, renderSkill(skills.current()));
                logInfo(traceId, "发布技能版本出参", "version=" + version + ",instructionsLength=" + instructions.length());
                return;
            }
            if (rest.equals("/current") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, renderSkill(skills.current()));
                logInfo(traceId, "查询当前技能出参", "version=" + skills.currentVersion());
                return;
            }
            if (rest.startsWith("/") && rest.endsWith("/activate") && exchange.getRequestMethod().equals("POST")) {
                String version = promptVersion(rest.substring(1, rest.length() - "/activate".length()));
                send(exchange, 200, renderSkill(skills.activate(version)));
                logInfo(traceId, "切换技能版本出参", "version=" + version);
                return;
            }
            send(exchange, 404, jsonError("not_found"));
        } catch (IllegalArgumentException bad) {
            logWarn(traceId, "技能版本参数错误", bad.getMessage()); send(exchange, 400, jsonError(bad.getMessage()));
        } catch (IllegalStateException conflict) {
            logWarn(traceId, "技能版本状态冲突", conflict.getMessage()); send(exchange, 409, jsonError(conflict.getMessage()));
        } catch (RuntimeException failed) {
            logWarn(traceId, "技能版本接口失败", failed.getClass().getSimpleName()); send(exchange, 500, jsonError("runtime_failure"));
        } finally { exchange.close(); }
    }

    private void handlePrompts(HttpExchange exchange) throws IOException {
        String traceId = prepareTraceId(exchange);
        try {
            if (!authenticated(exchange)) { send(exchange, 401, jsonError("unauthorized")); return; }
            String path = exchange.getRequestURI().getPath();
            String base = "/internal/v1/prompts";
            String rest = path.length() <= base.length() ? "" : path.substring(base.length());
            logInfo(traceId, "提示词版本接口入参", "method=" + exchange.getRequestMethod() + ",path=" + path);
            if ((rest.isEmpty() || rest.equals("/")) && exchange.getRequestMethod().equals("GET")) {
                int limit = queryLimit(exchange.getRequestURI().getRawQuery());
                send(exchange, 200, renderPromptHistory(prompts.history(limit)));
                logInfo(traceId, "查询提示词历史出参", "limit=" + limit + ",currentVersion=" + prompts.currentVersion());
                return;
            }
            if ((rest.isEmpty() || rest.equals("/")) && exchange.getRequestMethod().equals("POST")) {
                Map<String,Object> body = parseBody(exchange);
                String version = promptVersion(required(body, "version"));
                String prompt = required(body, "prompt");
                if (prompt.length() > 60_000) throw new IllegalArgumentException("prompt too long");
                prompts.publish(version, prompt);
                send(exchange, 200, renderPrompt(prompts.current()));
                logInfo(traceId, "发布提示词版本出参", "version=" + version + ",promptLength=" + prompt.length());
                return;
            }
            if (rest.equals("/current") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, renderPrompt(prompts.current()));
                logInfo(traceId, "查询当前提示词出参", "version=" + prompts.currentVersion());
                return;
            }
            if (rest.startsWith("/") && rest.endsWith("/activate") && exchange.getRequestMethod().equals("POST")) {
                String version = promptVersion(rest.substring(1, rest.length() - "/activate".length()));
                PromptSnapshot activated = prompts.activate(version);
                send(exchange, 200, renderPrompt(activated));
                logInfo(traceId, "切换提示词版本出参", "version=" + version);
                return;
            }
            send(exchange, 404, jsonError("not_found"));
        } catch (IllegalArgumentException bad) {
            logWarn(traceId, "提示词版本参数错误", bad.getMessage());
            send(exchange, 400, jsonError(bad.getMessage()));
        } catch (IllegalStateException conflict) {
            logWarn(traceId, "提示词版本状态冲突", conflict.getMessage());
            send(exchange, 409, jsonError(conflict.getMessage()));
        } catch (RuntimeException failed) {
            logWarn(traceId, "提示词版本接口失败", failed.getClass().getSimpleName());
            send(exchange, 500, jsonError("runtime_failure"));
        } finally { exchange.close(); }
    }


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

    private void handleEvaluationRun(HttpExchange exchange) throws IOException {
        String traceId = prepareTraceId(exchange);
        try {
            if (!authenticated(exchange)) { send(exchange, 401, jsonError("unauthorized")); return; }
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/v1/runs")) {
                send(exchange, 405, jsonError("method_not_allowed")); return;
            }
            Map<String,Object> body = parseBody(exchange);
            long evaluationRunId = requiredLong(body, "evaluationRunId");
            String caseId = required(body, "caseId");
            String input = required(body, "input");
            String diagnosisId = evaluationDiagnosisId(evaluationRunId, caseId);
            String ticketSn = "eval-" + evaluationRunId + "-" + digest(caseId).substring(0, 12);
            logInfo(traceId, "自动评测入参", "evaluationRunId=" + evaluationRunId + ",caseId=" + caseId
                + ",inputLength=" + input.length() + ",diagnosisId=" + diagnosisId);
            DiagnosisRun run = store.find(diagnosisId)
                .orElseGet(() -> orchestrator.runToApproval(ticketSn, diagnosisId, input));
            reporter.sync(run, traceId, evaluationRunId);
            StringBuilder response = new StringBuilder("{");
            field(response, "runId", AgentOperationReporter.runtimeRunId(diagnosisId)); comma(response);
            field(response, "output", render(run)); comma(response);
            field(response, "traceId", traceId); response.append('}');
            send(exchange, 200, response.toString());
            logInfo(traceId, "自动评测出参", "evaluationRunId=" + evaluationRunId + ",caseId=" + caseId
                + ",state=" + run.state() + ",runId=" + AgentOperationReporter.runtimeRunId(diagnosisId));
        } catch (IllegalArgumentException bad) {
            logWarn(traceId, "自动评测参数错误", bad.getMessage());
            send(exchange, 400, jsonError(bad.getMessage()));
        } catch (IllegalStateException conflict) {
            logWarn(traceId, "自动评测状态冲突", conflict.getMessage());
            send(exchange, 409, jsonError(conflict.getMessage()));
        } catch (RuntimeException failed) {
            logWarn(traceId, "自动评测运行失败", failed.getClass().getSimpleName());
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

    private static long requiredLong(Map<String,Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof Double number) || !Double.isFinite(number)
                || number < 1 || number > Long.MAX_VALUE || number != Math.rint(number)) {
            throw new IllegalArgumentException("missing or invalid " + key);
        }
        return number.longValue();
    }

    private static int queryLimit(String query) {
        if (query == null || query.isBlank()) return 50;
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && pair[0].equals("limit")) {
                try {
                    int value = Integer.parseInt(pair[1]);
                    if (value >= 1 && value <= 200) return value;
                } catch (NumberFormatException ignored) {}
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
        }
        return 50;
    }

    private static String promptVersion(String version) {
        if (!version.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("invalid prompt version");
        return version;
    }

    private static String evaluationDiagnosisId(long evaluationRunId, String caseId) {
        return "eval-" + evaluationRunId + "-" + digest(caseId).substring(0, 24);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private static String renderPrompt(PromptSnapshot prompt) {
        StringBuilder out = new StringBuilder("{");
        field(out, "version", prompt.version()); comma(out); field(out, "prompt", prompt.prompt());
        return out.append('}').toString();
    }

    private static String renderPromptHistory(java.util.List<PromptVersionStore.PromptVersionInfo> versions) {
        StringBuilder out = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (PromptVersionStore.PromptVersionInfo version : versions) {
            if (!first) out.append(','); first = false;
            out.append('{'); field(out, "version", version.version()); comma(out);
            out.append("\"current\":").append(version.current()).append(',');
            out.append("\"createdAt\":").append(version.createdAt()).append('}');
        }
        return out.append("]}").toString();
    }

    private static String renderSkill(SkillSnapshot skill) {
        StringBuilder out = new StringBuilder("{");
        field(out, "version", skill.version()); comma(out); field(out, "instructions", skill.instructions());
        return out.append('}').toString();
    }

    private static String renderSkillHistory(java.util.List<SkillVersionStore.SkillVersionInfo> versions) {
        StringBuilder out = new StringBuilder("{\"items\":["); boolean first = true;
        for (SkillVersionStore.SkillVersionInfo version : versions) {
            if (!first) out.append(','); first = false;
            out.append('{'); field(out, "version", version.version()); comma(out);
            out.append("\"current\":").append(version.current()).append(',');
            out.append("\"createdAt\":").append(version.createdAt()).append('}');
        }
        return out.append("]}").toString();
    }

    private static String renderTools(ToolManifest manifest) {
        StringBuilder out = new StringBuilder("{");
        field(out, "version", manifest.version()); comma(out); field(out, "digest", manifest.digest());
        out.append(",\"items\":["); boolean first = true;
        for (ToolManifest.ToolDefinition tool : manifest.tools()) {
            if (!first) out.append(','); first = false;
            out.append('{'); field(out, "name", tool.name()); comma(out); field(out, "category", tool.category());
            comma(out); field(out, "mode", tool.mode()); comma(out); field(out, "contractVersion", tool.contractVersion());
            comma(out); field(out, "detail", tool.detail()); out.append('}');
        }
        return out.append("]}").toString();
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
