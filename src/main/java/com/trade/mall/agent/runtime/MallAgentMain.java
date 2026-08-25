package com.trade.mall.agent.runtime;

import com.trade.mall.agent.alert.infrastructure.StderrAlertPort;
import com.trade.mall.agent.approval.infrastructure.JdbcMallAdminAuthorizationPort;
import com.trade.mall.agent.execution.infrastructure.HttpMallRefundActionPort;
import com.trade.mall.agent.llm.infrastructure.JdbcPromptVersionStore;
import com.trade.mall.agent.llm.infrastructure.JdbcSkillVersionStore;
import com.trade.mall.agent.llm.infrastructure.OpenAiCompatibleLlmClientFactory;
import com.trade.mall.agent.runtime.http.AgentControlHttpServer;
import com.trade.mall.agent.runtime.infrastructure.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * mall-agent standalone（独立进程）的最小生产启动入口。
 * 配置全部来自环境变量；没有 Spring/DI（依赖注入）框架，避免为了组装现有对象重构 core。
 */
public final class MallAgentMain {
    private MallAgentMain() {}

    public static void main(String[] args) throws Exception {
        Env env = new Env(System.getenv());
        DataSource runtimeDb = new DriverManagerDataSource(
            env.required("AGENT_RUNTIME_JDBC_URL"), env.required("AGENT_RUNTIME_DB_USER"), env.required("AGENT_RUNTIME_DB_PASSWORD"));
        DataSource evidenceDb = new DriverManagerDataSource(
            env.get("AGENT_EVIDENCE_JDBC_URL", env.required("AGENT_RUNTIME_JDBC_URL")),
            env.get("AGENT_EVIDENCE_DB_USER", env.required("AGENT_RUNTIME_DB_USER")),
            env.get("AGENT_EVIDENCE_DB_PASSWORD", env.required("AGENT_RUNTIME_DB_PASSWORD")));

        JdbcPromptVersionStore prompts = new JdbcPromptVersionStore(runtimeDb, System::currentTimeMillis);
        ensurePromptConfigured(prompts, env);
        JdbcSkillVersionStore skills = new JdbcSkillVersionStore(runtimeDb, System::currentTimeMillis);
        ensureSkillConfigured(skills, env);

        String initialModelId = env.required("AGENT_LLM_INITIAL_MODEL_ID");
        ensureModelRoleIsolation(env, initialModelId);
        OpenAiCompatibleLlmClientFactory llmFactory = new OpenAiCompatibleLlmClientFactory(loadLlmProfiles(env));
        String toolSchemaVersion = env.get("AGENT_TOOL_SCHEMA_VERSION", "v1");
        URI mallBaseUri = URI.create(env.required("AGENT_MALL_BASE_URL"));
        String tenantId = env.required("AGENT_MALL_TENANT_ID");
        String mallApiKey = env.required("AGENT_MALL_API_KEY");
        StderrAlertPort alerts = new StderrAlertPort();

        DurableMallAgentRuntime runtime = new DurableMallAgentRuntime(
            runtimeDb, evidenceDb, llmFactory, prompts, skills, initialModelId, toolSchemaVersion,
            new JdbcMallAdminAuthorizationPort(evidenceDb, Duration.ofSeconds(1)),
            () -> parseOptionalBoolean(System.getenv("AGENT_MONEY_ACTION_ALLOWED")),
            new HttpMallRefundActionPort(mallBaseUri.toString(), tenantId, mallApiKey),
            mallBaseUri, tenantId, mallApiKey, alerts, System::currentTimeMillis);

        AgentControlHttpServer control = new AgentControlHttpServer(
            new InetSocketAddress(env.get("AGENT_CONTROL_HOST", "127.0.0.1"), env.intValue("AGENT_CONTROL_PORT", 18080, 1, 65535)),
            env.required("AGENT_CONTROL_API_KEY"), runtime.orchestrator(), runtime.diagnosisRunStore(),
            new AgentOperationReporter(
                URI.create(env.required("AGENT_OPERATIONS_BASE_URL")), env.required("AGENT_OPERATIONS_INGEST_KEY"),
                initialModelId, prompts::currentVersion, skills::currentVersion,
                toolSchemaVersion, runtime.eventLedger(), Duration.ofSeconds(
                    env.longValue("AGENT_OPERATIONS_TIMEOUT_SECONDS", 5L, 1L, 120L))), prompts, skills);
        DurableMallAgentScheduler scheduler = new DurableMallAgentScheduler(
            runtime, alerts, Duration.ofMillis(env.longValue("AGENT_MAINTENANCE_INTERVAL_MS", 5000L, 1000L, 3_600_000L)),
            env.intValue("AGENT_MAINTENANCE_BATCH", 50, 1, 1000));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(scheduler, control, runtime), "mall-agent-shutdown"));
        control.start();
        System.out.println("MallAgent started on " + env.get("AGENT_CONTROL_HOST", "127.0.0.1") + ":" + control.port());
        new CountDownLatch(1).await();
    }

    private static void ensurePromptConfigured(JdbcPromptVersionStore store, Env env) {
        try { store.current(); return; }
        catch (IllegalStateException noCurrent) {
            String version = env.optional("AGENT_PROMPT_VERSION");
            String text = env.optional("AGENT_PROMPT_TEXT");
            if (version == null || text == null) {
                throw new IllegalStateException("agent_prompt_version 没有 current 版本；首次启动必须提供 AGENT_PROMPT_VERSION + AGENT_PROMPT_TEXT", noCurrent);
            }
            store.publish(version, text);
        }
    }

    private static void ensureSkillConfigured(JdbcSkillVersionStore store, Env env) {
        try { store.current(); return; }
        catch (IllegalStateException noCurrent) {
            String version = env.optional("AGENT_SKILL_VERSION");
            String instructions = env.optional("AGENT_SKILL_INSTRUCTIONS");
            if (version == null || instructions == null) {
                throw new IllegalStateException("agent_skill_version 没有 current 版本；首次启动必须提供 AGENT_SKILL_VERSION + AGENT_SKILL_INSTRUCTIONS", noCurrent);
            }
            store.publish(version, instructions);
        }
    }

    private static Map<String, OpenAiCompatibleLlmClientFactory.Endpoint> loadLlmProfiles(Env env) {
        String raw = env.required("AGENT_LLM_PROFILES");
        Map<String, OpenAiCompatibleLlmClientFactory.Endpoint> out = new LinkedHashMap<>();
        for (String token : raw.split(",")) {
            String id = token.trim();
            if (id.isEmpty()) continue;
            String key = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            String prefix = "AGENT_LLM_" + key + "_";
            out.put(id, new OpenAiCompatibleLlmClientFactory.Endpoint(
                URI.create(env.required(prefix + "BASE_URL")),
                env.required(prefix + "API_KEY"),
                env.required(prefix + "REMOTE_MODEL"),
                Duration.ofSeconds(env.longValue(prefix + "TIMEOUT_SECONDS", 30L, 1L, 120L))));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("AGENT_LLM_PROFILES must contain at least one model profile");
        String initial = env.required("AGENT_LLM_INITIAL_MODEL_ID");
        if (!out.containsKey(initial)) throw new IllegalArgumentException("AGENT_LLM_INITIAL_MODEL_ID is not listed in AGENT_LLM_PROFILES: " + initial);
        return Map.copyOf(out);
    }

    private static Boolean parseOptionalBoolean(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if ("true".equalsIgnoreCase(raw.trim())) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw.trim())) return Boolean.FALSE;
        throw new IllegalArgumentException("AGENT_MONEY_ACTION_ALLOWED must be true or false");
    }

    static void ensureModelRoleIsolation(Env env, String handlerModelId) {
        String caseAuthorModelId = env.required("CASE_AUTHOR_MODEL_ID");
        if (caseAuthorModelId.equalsIgnoreCase(handlerModelId)) {
            throw new IllegalArgumentException("造题 Agent 与工单处理 Agent 不得使用同一模型");
        }
    }

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    static final class Env {
        private final Map<String,String> values;
        Env(Map<String,String> values) { this.values = values; }
        String optional(String key) { String v = values.get(key); return v == null || v.isBlank() ? null : v.trim(); }
        String required(String key) { String v = optional(key); if (v == null) throw new IllegalArgumentException("missing environment variable " + key); return v; }
        String get(String key, String fallback) { String v = optional(key); return v == null ? fallback : v; }
        int intValue(String key, int fallback, int min, int max) { long v = longValue(key, fallback, min, max); return (int)v; }
        long longValue(String key, long fallback, long min, long max) {
            String raw = optional(key); long v = raw == null ? fallback : Long.parseLong(raw);
            if (v < min || v > max) throw new IllegalArgumentException(key + " out of range [" + min + "," + max + "]");
            return v;
        }
    }
}
