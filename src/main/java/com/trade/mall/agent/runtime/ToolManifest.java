package com.trade.mall.agent.runtime;

import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.proposal.ActionType;
import com.trade.mall.agent.verification.IndependentFactSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 当前进程实际装配的工具清单；摘要用于证明同一版本对应的具体能力集合。 */
public record ToolManifest(String version, String digest, List<ToolDefinition> tools) {
    public ToolManifest {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("tool manifest version required");
        if (digest == null || digest.isBlank()) throw new IllegalArgumentException("tool manifest digest required");
        tools = List.copyOf(tools);
    }

    public static ToolManifest from(String version, List<EvidenceCollector<?>> collectors,
                                    List<IndependentFactSource> factSources) {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("llm.ticket_understanding", "模型", "只读", "v1", "工单锚点提取"));
        collectors.stream().map(EvidenceCollector::sourceType).distinct().sorted().forEach(source ->
            tools.add(new ToolDefinition("evidence." + source.toLowerCase(Locale.ROOT), "证据", "只读", "v1", source)));
        tools.add(new ToolDefinition("llm.reasoning", "模型", "只读", "v1", "基于证据判定"));
        tools.add(new ToolDefinition("policy.remediation", "策略", "只读", "v1", "生成受控处置提议"));
        tools.add(new ToolDefinition("approval.gate", "审批", "受控写入", "v1", "资金动作审批闸门"));
        for (ActionType action : ActionType.values()) {
            tools.add(new ToolDefinition("action.execution", "动作", action.requiresApproval() ? "资金审批后写入" : "幂等写入",
                action.actionVersion(), action.name() + "@" + action.sourceType()));
        }
        String sources = factSources.stream().map(IndependentFactSource::sourceType).distinct().sorted()
            .reduce((a, b) -> a + "," + b).orElse("NONE");
        tools.add(new ToolDefinition("verification.recovery", "验证", "只读", "v1", sources));
        tools.sort(java.util.Comparator.comparing(ToolDefinition::name).thenComparing(ToolDefinition::detail));
        List<ToolDefinition> immutable = List.copyOf(tools);
        String canonical = immutable.stream().map(ToolDefinition::canonical).reduce((a, b) -> a + "\n" + b).orElse("");
        return new ToolManifest(version, sha256(canonical), immutable);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public record ToolDefinition(String name, String category, String mode, String contractVersion, String detail) {
        private String canonical() { return String.join("|", name, category, mode, contractVersion, detail); }
    }
}
