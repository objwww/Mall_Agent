package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.McpEvidenceRecord;
import com.trade.mall.agent.mcp.McpReadOnlyClient;

/** 把一个明确白名单的只读 MCP 工具接入现有证据三态协议。 */
public final class McpEvidenceCollector implements EvidenceCollector<McpEvidenceRecord> {
    private final String sourceType;
    private final String toolName;
    private final String argumentName;
    private final McpReadOnlyClient client;

    public McpEvidenceCollector(String sourceType, String toolName, String argumentName, McpReadOnlyClient client) {
        this.sourceType = required(sourceType, "MCP证据类型");
        this.toolName = required(toolName, "MCP工具名");
        this.argumentName = required(argumentName, "MCP参数名");
        this.client = client;
    }

    @Override public String sourceType() { return sourceType; }

    @Override public EvidenceResult<McpEvidenceRecord> collect(String anchor) {
        try {
            return new EvidenceResult.Present<>(new McpEvidenceRecord(toolName, anchor, client.call(argumentName, anchor)));
        } catch (RuntimeException failed) {
            return new EvidenceResult.Unavailable<>("只读MCP证据不可用：" + failed.getMessage());
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空"); return value;
    }
}
