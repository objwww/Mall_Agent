package com.trade.mall.agent.runtime;

import com.trade.mall.agent.evidence.EvidencePayload;
import com.trade.mall.agent.evidence.collector.EvidenceCollector;
import com.trade.mall.agent.evidence.port.EvidenceResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolManifestTest {
    @Test void 摘要由实际装配工具确定且顺序稳定() {
        ToolManifest first = ToolManifest.from("tools-v1", List.of(collector("REFUND"), collector("ORDER")), List.of());
        ToolManifest reordered = ToolManifest.from("tools-v1", List.of(collector("ORDER"), collector("REFUND")), List.of());
        ToolManifest changed = ToolManifest.from("tools-v1", List.of(collector("ORDER")), List.of());

        assertEquals(first.digest(), reordered.digest());
        assertNotEquals(first.digest(), changed.digest());
        assertTrue(first.tools().stream().anyMatch(tool -> tool.name().equals("evidence.refund")));
        assertTrue(first.tools().stream().anyMatch(tool -> tool.name().equals("action.execution")
            && tool.mode().contains("审批")));
    }

    private static EvidenceCollector<EvidencePayload> collector(String sourceType) {
        return new EvidenceCollector<>() {
            @Override public String sourceType() { return sourceType; }
            @Override public EvidenceResult<EvidencePayload> collect(String anchor) {
                return new EvidenceResult.Unavailable<>("测试不执行采集");
            }
        };
    }
}
