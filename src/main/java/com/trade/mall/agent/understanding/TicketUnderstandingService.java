package com.trade.mall.agent.understanding;

import com.trade.mall.agent.ledger.EventLedger;
import com.trade.mall.agent.llm.LlmClient;
import com.trade.mall.agent.llm.LlmJsonUtil;
import com.trade.mall.agent.llm.LlmQuotaException;
import com.trade.mall.agent.llm.LlmRegistry;
import com.trade.mall.agent.llm.LlmRequest;
import com.trade.mall.agent.llm.LlmResponse;
import com.trade.mall.agent.llm.LlmSchemaException;
import com.trade.mall.agent.llm.LlmTimeoutException;
import com.trade.mall.agent.llm.LlmUnavailableException;
import com.trade.mall.agent.llm.PromptSnapshot;
import com.trade.mall.agent.llm.VersionSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * TicketUnderstandingService —— `M-CAP-01`：自由文本工单 → 结构化（锚点 + 症状）。
 *
 * <p>三条设计决定，全部来自 `implementation_plan.md` D6 步骤 4-5：</p>
 * <ol>
 *   <li>**先钉版本，再调用**——`understand()` 第一步是 {@code llmRegistry.pin(diagnosisId)}
 *       + {@code forPinned(diagnosisId)}，之后这次调用全程用这一个 {@link LlmClient} 实例，
 *       不再碰 {@code llmRegistry.current()}——这是 `ADR-015` 版本钉住落到具体调用方代码
 *       的样子，不只是 `LlmRegistry` 自己有这个能力，调用方必须真的用它。</li>
 *   <li>**LLM 调用走 `LlmPort`，在 L3 内部**（`ARCH-ORCH-001`）——本类不被
 *       `agent.orchestration` 引用，编排层只应该拿到 {@link UnderstandingResult} 这个结果，
 *       不应该知道背后调了几次 LLM、重试了几次。</li>
 *   <li>**解析失败 → 确定失败（`DEP-LLM-001` 失败语义③）→ 重试（带修复提示）→ 超过次数
 *       转人工**——{@link LlmJsonUtil} 解析抛出的 {@link IllegalArgumentException} 和
 *       LLM 自己抛出的 {@link com.trade.mall.agent.llm.LlmSchemaException} 被同等对待，
 *       都触发"追加修复提示、重新问一次"，用满 {@link #MAX_ATTEMPTS} 次仍不行才
 *       {@link UnderstandingResult.Escalated}。</li>
 * </ol>
 */
public final class TicketUnderstandingService {

    private static final int MAX_ATTEMPTS = 3;

    private final LlmRegistry llmRegistry;
    private final EventLedger ledger;
    private final LongSupplier clock;

    public TicketUnderstandingService(LlmRegistry llmRegistry, EventLedger ledger, LongSupplier clock) {
        this.llmRegistry = llmRegistry;
        this.ledger = ledger;
        this.clock = clock;
    }

    public UnderstandingResult understand(String ticketSn, String diagnosisId, String freeText) {
        VersionSnapshot snapshot = llmRegistry.pin(diagnosisId);
        LlmClient client = llmRegistry.forPinned(diagnosisId);
        PromptSnapshot promptSnapshot = llmRegistry.promptForPinned(diagnosisId);
        String systemPrompt = llmRegistry.skillForPinned(diagnosisId).applyTo(promptSnapshot.prompt());

        String repairHint = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmResponse response;
            try {
                response = client.complete(buildRequest(systemPrompt, freeText, repairHint));
            } catch (LlmTimeoutException timeout) {
                // 无副作用，可直接重试（M-LLM-01 §1.3）——不追加修复提示，问题不在 schema。
                repairHint = "";
                continue;
            } catch (LlmSchemaException schemaFailure) {
                repairHint = "\n\n上一次模型响应 envelope/schema 非法（" + schemaFailure.getMessage() + "），请重新输出 JSON。";
                continue;
            } catch (LlmUnavailableException | LlmQuotaException degraded) {
                // 该能力降级；不阻塞诊断流程终止到人工（M-LLM-01 §4.2）。
                return escalate(ticketSn, diagnosisId, "LLM 不可用：" + degraded.getMessage(), attempt, snapshot);
            }

            try {
                Map<String, Object> parsed = LlmJsonUtil.parseFlatObject(response.content());
                UnderstandingResult result = interpret(parsed, snapshot);
                recordAndReturn(ticketSn, diagnosisId, result);
                return result;
            } catch (IllegalArgumentException schemaFailure) {
                repairHint = "\n\n上一次输出没有满足要求的 JSON 结构（" + schemaFailure.getMessage()
                    + "）。请只输出合法 JSON，不要有任何多余文字。";
            }
        }
        return escalate(ticketSn, diagnosisId, "LLM 输出连续 " + MAX_ATTEMPTS + " 次不满足 schema", MAX_ATTEMPTS, snapshot);
    }

    private void recordAndReturn(String ticketSn, String diagnosisId, UnderstandingResult result) {
        long now = clock.getAsLong();
        if (result instanceof UnderstandingResult.Understood u) {
            ledger.append(TicketEvents.anchorExtracted(ticketSn, diagnosisId, 1, u.anchor().type().name(), u.anchor().value(), u.confidence(), now));
        } else if (result instanceof UnderstandingResult.AnchorMissing m) {
            ledger.append(TicketEvents.anchorMissing(ticketSn, diagnosisId, m.reason(), now));
        }
    }

    private UnderstandingResult interpret(Map<String, Object> parsed, VersionSnapshot snapshot) {
        if (Boolean.TRUE.equals(parsed.get("anchorMissing"))) {
            Object reason = parsed.get("reason");
            if (!(reason instanceof String r) || r.isBlank()) {
                throw new IllegalArgumentException("anchorMissing=true 但缺少非空的 reason 字段");
            }
            return new UnderstandingResult.AnchorMissing(r, snapshot);
        }

        Object anchorTypeRaw = parsed.get("anchorType");
        Object anchorValueRaw = parsed.get("anchorValue");
        Object symptomsRaw = parsed.get("symptoms");
        Object confidenceRaw = parsed.get("confidence");

        if (!(anchorTypeRaw instanceof String typeStr)) throw new IllegalArgumentException("缺少或非法的 anchorType 字段");
        if (!(anchorValueRaw instanceof String valueStr) || valueStr.isBlank()) throw new IllegalArgumentException("缺少或非法的 anchorValue 字段");
        if (!(symptomsRaw instanceof List<?> symptomsList)) throw new IllegalArgumentException("缺少或非法的 symptoms 字段（应为字符串数组）");
        if (!(confidenceRaw instanceof Double confidenceVal)) throw new IllegalArgumentException("缺少或非法的 confidence 字段（应为数字）");
        if (!Double.isFinite(confidenceVal) || confidenceVal < 0.0d || confidenceVal > 1.0d) {
            throw new IllegalArgumentException("confidence 必须是 0.0 到 1.0 之间的有限数字");
        }

        AnchorType type;
        try {
            type = AnchorType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的 anchorType: " + typeStr);
        }

        List<Symptom> symptoms = new ArrayList<>();
        for (Object o : symptomsList) {
            if (!(o instanceof String s) || s.isBlank()) throw new IllegalArgumentException("symptoms 数组里包含非法元素");
            symptoms.add(new Symptom(s));
        }

        return new UnderstandingResult.Understood(new Anchor(type, valueStr), symptoms, confidenceVal, snapshot);
    }

    private UnderstandingResult.Escalated escalate(String ticketSn, String diagnosisId, String reason, int attempts, VersionSnapshot snapshot) {
        ledger.append(TicketEvents.escalated(ticketSn, diagnosisId, 1, reason, clock.getAsLong()));
        return new UnderstandingResult.Escalated(reason, attempts, snapshot);
    }

    private LlmRequest buildRequest(String versionedPrompt, String freeText, String repairHint) {
        // versionedPrompt（版本化提示词）来自 diagnosis 首次 pin（钉住）的 PromptSnapshot；
        // 下面只保留与 Java schema（结构约束）同版本发布的固定输出契约，不再把真正的业务提示词写死在这里。
        String system = versionedPrompt + "\n\n"
            + "任务：从电商工单中提取业务锚点与症状。只输出合法 JSON："
            + "{\"anchorType\":\"ORDER|REFUND|TRACE|...\",\"anchorValue\":\"...\",\"symptoms\":[\"...\"],\"confidence\":0.0-1.0} "
            + "或者找不到锚点时输出 {\"anchorMissing\":true,\"reason\":\"...\"}。" + repairHint;
        return new LlmRequest(system, freeText, 512);
    }
}
