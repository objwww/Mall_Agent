package com.trade.mall.agent.runtime;

import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.EventLedger;
import com.trade.mall.agent.llm.VersionSnapshot;
import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisState;
import com.trade.mall.agent.reasoning.FindingResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把真实领域事件幂等上报为 mall_R 的 Agent Run/Tool Call 在线监控协议。 */
public final class AgentOperationReporter {
    private final URI baseUri;
    private final String ingestKey;
    private final String defaultModelId;
    private final String defaultPromptVersion;
    private final String skillVersion;
    private final String toolSchemaVersion;
    private final EventLedger ledger;
    private final HttpClient client;
    private final Duration timeout;

    public AgentOperationReporter(URI baseUri, String ingestKey, String defaultModelId,
                                  String defaultPromptVersion, String skillVersion,
                                  String toolSchemaVersion, EventLedger ledger, Duration timeout) {
        this.baseUri = URI.create(required(baseUri, "operations baseUri").toString().replaceAll("/+$", ""));
        this.ingestKey = required(ingestKey, "operations ingestKey");
        this.defaultModelId = sized(defaultModelId, "modelId", 100);
        this.defaultPromptVersion = sized(defaultPromptVersion, "promptVersion", 64);
        this.skillVersion = sized(skillVersion, "skillVersion", 64);
        this.toolSchemaVersion = sized(toolSchemaVersion, "toolSchemaVersion", 64);
        this.ledger = required(ledger, "eventLedger");
        this.timeout = required(timeout, "timeout");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 上报失败不改变诊断事实；下次创建、查询或审批会用相同幂等键补报。 */
    public boolean sync(DiagnosisRun run, String traceId) {
        try {
            List<DomainEvent> events = eventsOf(run);
            String runId = runId(run.diagnosisId());
            VersionSnapshot version = versionOf(run);
            long startedAt = events.stream().mapToLong(DomainEvent::occurredAt).min().orElse(System.currentTimeMillis());
            post("/agentRuntime/v1/runs/start", startBody(run, runId, traceId, version, startedAt));

            List<DomainEvent> tools = events.stream().filter(e -> toolName(e) != null)
                .sorted(Comparator.comparingLong(DomainEvent::occurredAt).thenComparing(DomainEvent::eventId)).toList();
            for (int i = 0; i < tools.size(); i++) {
                DomainEvent event = tools.get(i);
                post("/agentRuntime/v1/runs/" + runId + "/tool-calls", toolBody(event, i + 1));
            }
            if (run.isTerminal()) {
                post("/agentRuntime/v1/runs/" + runId + "/finish", finishBody(run));
            }
            System.out.println("级别=信息,TraceId=" + traceId + ",动作=同步Agent在线监控,diagnosisId="
                + run.diagnosisId() + ",runId=" + runId + ",toolCallCount=" + tools.size() + ",state=" + run.state());
            return true;
        } catch (Exception exception) {
            System.err.println("级别=警告,TraceId=" + traceId + ",动作=同步Agent在线监控失败,diagnosisId="
                + run.diagnosisId() + ",原因=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private List<DomainEvent> eventsOf(DiagnosisRun run) {
        Map<String,DomainEvent> unique = new LinkedHashMap<>();
        add(unique, ledger.eventsOf(run.diagnosisId()));
        if (run.approvalId() != null) add(unique, ledger.eventsOf(run.approvalId()));
        if (run.proposal() != null) add(unique, ledger.eventsOf(run.proposal().operationId()));
        return new ArrayList<>(unique.values());
    }

    private static void add(Map<String,DomainEvent> target, List<DomainEvent> events) {
        for (DomainEvent event : events) target.putIfAbsent(event.eventId(), event);
    }

    private Map<String,Object> startBody(DiagnosisRun run, String runId, String traceId,
                                         VersionSnapshot version, long startedAt) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId("start", run.diagnosisId()));
        body.put("runId", runId);
        body.put("agentName", "MallAgent工单处理");
        body.put("agentRole", "MALL_HANDLER");
        body.put("traceId", identity(traceId));
        body.put("diagnosisId", sized(run.diagnosisId(), "diagnosisId", 64));
        body.put("modelId", version == null ? defaultModelId : sized(version.modelId(), "modelId", 100));
        body.put("promptVersion", version == null ? defaultPromptVersion : sized(version.promptVersion(), "promptVersion", 64));
        body.put("skillVersion", skillVersion);
        body.put("toolSchemaVersion", version == null ? toolSchemaVersion : sized(version.toolSchemaVersion(), "toolSchemaVersion", 64));
        body.put("inputSummary", limit("工单=" + run.ticketSn() + ",诊断=" + run.diagnosisId(), 1000));
        body.put("startedAt", startedAt);
        return body;
    }

    private Map<String,Object> toolBody(DomainEvent event, int sequence) {
        boolean failed = failed(event.eventType());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId("tool", event.eventId()));
        body.put("toolCallId", eventId("call", event.eventId()));
        body.put("sequenceNo", sequence);
        body.put("toolName", toolName(event));
        body.put("status", failed ? "FAILED" : "SUCCESS");
        body.put("inputJson", json(Map.of("aggregateId", event.aggregateId(), "eventType", event.eventType())));
        body.put("outputJson", json(Map.of("payload", limit(event.payload(), 8000))));
        if (failed) body.put("errorMessage", limit(event.payload(), 2000));
        body.put("startedAt", event.occurredAt());
        body.put("finishedAt", event.occurredAt());
        return body;
    }

    private Map<String,Object> finishBody(DiagnosisRun run) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId("finish", run.diagnosisId()));
        body.put("status", switch (run.state()) {
            case RESOLVED, CLOSED_NO_ACTION -> "SUCCESS";
            case REJECTED, EXPIRED -> "CANCELLED";
            default -> "FAILED";
        });
        body.put("outputSummary", "诊断终态=" + run.state() + ",状态序号=" + run.seq());
        if (run.state() == DiagnosisState.ESCALATED_HUMAN) body.put("errorMessage", "诊断已转人工处理");
        body.put("finishedAt", System.currentTimeMillis());
        return body;
    }

    private void post(String path, Map<String,Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
            .header("X-Agent-Ingest-Key", ingestKey).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Agent监控采集失败 HTTP=" + response.statusCode());
        }
    }

    private VersionSnapshot versionOf(DiagnosisRun run) {
        if (run.finding() instanceof FindingResult.Concluded finding) return finding.versionSnapshot();
        if (run.finding() instanceof FindingResult.NoConclusion finding) return finding.versionSnapshot();
        return null;
    }

    private static String toolName(DomainEvent event) {
        String type = event.eventType();
        if (type.startsWith("Ticket.")) return "llm.ticket_understanding";
        if (type.startsWith("Evidence.")) return "evidence." + firstWord(event.payload()).toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("Finding.")) return "llm.reasoning";
        if (type.startsWith("Proposal.")) return "policy.remediation";
        if (type.startsWith("Approval.")) return "approval.gate";
        if (type.startsWith("Attempt.") || type.startsWith("Execution.") || type.startsWith("Reconcile.")) return "action.execution";
        if (type.startsWith("Verification.")) return "verification.recovery";
        return null;
    }

    private static boolean failed(String type) {
        return type.contains("Failed") || type.contains("Unavailable") || type.contains("Unknown")
            || type.contains("Escalated") || type.contains("Rejected") || type.contains("Expired");
    }

    private static String firstWord(String value) {
        if (value == null || value.isBlank()) return "unknown";
        int end = value.indexOf(' ');
        return end < 0 ? value : value.substring(0, end);
    }

    private static String runId(String diagnosisId) { return "run-" + digest(diagnosisId).substring(0, 40); }
    private static String eventId(String kind, String seed) { return kind + "-" + digest(seed).substring(0, 40); }
    private static String identity(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 64 ? value : "trace-" + digest(value).substring(0, 40);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String json(Map<String,?> values) {
        StringBuilder out = new StringBuilder("{"); boolean first = true;
        for (Map.Entry<String,?> entry : values.entrySet()) {
            if (entry.getValue() == null) continue;
            if (!first) out.append(','); first = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) out.append(value);
            else out.append('"').append(escape(value.toString())).append('"');
        }
        return out.append('}').toString();
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static String sized(String value, String name, int max) {
        String checked = required(value, name);
        if (checked.length() > max) throw new IllegalArgumentException(name + " length must be <= " + max);
        return checked;
    }
    private static <T> T required(T value, String name) {
        if (value == null || value instanceof String text && text.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
}
