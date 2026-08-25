package com.trade.mall.agent.orchestration;

import java.util.List;
import java.util.Optional;

/**
 * DiagnosisRunStore（诊断运行存储）——只负责保存/恢复现有 {@link DiagnosisRun} 检查点，
 * 不承担状态转移、不承担调度，也不是通用 Workflow Engine（工作流引擎）。
 *
 * <p>这是一个刻意很薄的端口：当前 Finding/Proposal/Evidence 尚没有各自独立的生产仓储，
 * 因此先把一次 {@code DiagnosisRun} 作为整体快照持久化；等未来这些对象真的独立持久化后，
 * 再把快照缩成 id 引用，而不是现在提前新增三套 Repository（仓储）。</p>
 */
public interface DiagnosisRunStore {

    void save(DiagnosisRun run);

    Optional<DiagnosisRun> find(String diagnosisId);

    /** 找出停在某个状态的诊断检查点，供启动恢复/周期恢复使用。 */
    default List<DiagnosisRun> findByState(DiagnosisState state, int limit) {
        return List.of();
    }

    /**
     * 最近完成的诊断，用作“历史处置经验”提示；它只是历史上下文，不是 Evidence（当前事实），
     * 也不能作为审批/自动执行依据。默认空实现保持旧调用方兼容。
     */
    default List<DiagnosisRun> recentTerminal(int limit) {
        return List.of();
    }

    /** 兼容现有 D8 调用方：不注入持久化实现时保持原有行为。生产环境不要使用。 */
    static DiagnosisRunStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final DiagnosisRunStore INSTANCE = new DiagnosisRunStore() {
            @Override public void save(DiagnosisRun run) {}
            @Override public Optional<DiagnosisRun> find(String diagnosisId) { return Optional.empty(); }
        };
        private NoopHolder() {}
    }
}

