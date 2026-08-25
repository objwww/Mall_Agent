package com.trade.mall.agent.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 SelfCheck §78-79 一一对应。穷举 19 态 x 21 触发的全部组合，确认恰好 24 条
 * 合法转移、5 个终态零出边——与 D1 {@code ExecutionTransitionPolicyTest} 同一验证纪律
 * 在诊断流程这张更大的表上的复现。
 */
class DiagnosisTransitionPolicyTest {

    @Test void exhaustive_exactly24LegalTransitions_terminalStatesHaveNoOutboundEdges() {
        int legal = 0;
        for (DiagnosisState from : DiagnosisState.values()) {
            for (DiagnosisTrigger tr : DiagnosisTrigger.values()) {
                var to = DiagnosisTransitionPolicy.next(from, tr);
                if (to.isPresent()) {
                    legal++;
                    assertFalse(from.isTerminal(), "终态不应有出边: " + from + "/" + tr);
                }
            }
        }
        assertEquals(24, legal);
        assertEquals(24, DiagnosisTransitionPolicy.size());
    }

    @Test void exactlyFiveTerminalStates() {
        long terminalCount = java.util.Arrays.stream(DiagnosisState.values()).filter(DiagnosisState::isTerminal).count();
        assertEquals(5, terminalCount);
    }

    @Test void illegalTransition_throws() {
        assertThrows(IllegalDiagnosisTransitionException.class,
            () -> DiagnosisTransitionPolicy.apply(DiagnosisState.RECEIVED, DiagnosisTrigger.VERIFY_RECOVERED));
    }

    @Test void notRecovered_routesBackToReasoning_notExecuting() {
        // O23：不重复上一动作，NOT_RECOVERED 之后回到 REASONING，而不是重新 EXECUTING。
        assertEquals(DiagnosisState.REASONING,
            DiagnosisTransitionPolicy.next(DiagnosisState.NOT_RECOVERED, DiagnosisTrigger.REOPEN_REASONING).orElseThrow());
    }

    @Test void verifyUnavailable_isIndependentState_notMergedIntoNotRecovered() {
        // 三个最容易做错的分支之一：VERIFY_UNAVAILABLE 必须是独立状态，不能与 NOT_RECOVERED 合并。
        assertEquals(DiagnosisState.VERIFY_UNAVAILABLE,
            DiagnosisTransitionPolicy.next(DiagnosisState.VERIFYING, DiagnosisTrigger.VERIFY_SOURCE_UNAVAILABLE).orElseThrow());
        assertNotEquals(DiagnosisState.NOT_RECOVERED, DiagnosisState.VERIFY_UNAVAILABLE);
    }

    @Test void noConclusion_routesToEscalatedHuman_neverForcedToConclusion() {
        assertEquals(DiagnosisState.ESCALATED_HUMAN,
            DiagnosisTransitionPolicy.next(DiagnosisState.NO_CONCLUSION, DiagnosisTrigger.ESCALATE_TO_HUMAN).orElseThrow());
    }

    @Test void archUnit_orchestrationPackage_hasNoDependencyOnLlmPackage() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/trade/mall/agent/orchestration");
        if (!java.nio.file.Files.exists(root)) return;
        try (var stream = java.nio.file.Files.walk(root)) {
            boolean anyImportsLlm = stream
                .filter(p -> p.toString().endsWith(".java"))
                .anyMatch(p -> {
                    try {
                        return java.nio.file.Files.readString(p).contains("import com.trade.mall.agent.llm.");
                    } catch (Exception e) {
                        return false;
                    }
                });
            assertFalse(anyImportsLlm, "agent.orchestration 不得依赖 agent.llm（ADR-009）");
        }
    }
}

