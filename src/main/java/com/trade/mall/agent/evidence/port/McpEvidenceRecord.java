package com.trade.mall.agent.evidence.port;

import com.trade.mall.agent.evidence.EvidencePayload;

/** 受信任只读 MCP 工具返回的文本证据。 */
public record McpEvidenceRecord(String toolName, String anchor, String content) implements EvidencePayload {
    public McpEvidenceRecord {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName required");
        if (anchor == null || anchor.isBlank()) throw new IllegalArgumentException("anchor required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");
    }
}
