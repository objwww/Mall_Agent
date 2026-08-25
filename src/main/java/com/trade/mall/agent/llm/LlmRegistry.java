package com.trade.mall.agent.llm;

/**
 * LlmRegistry —— `M-LLM-01`：持有当前生效的 {@link LlmClient}，配置变更时构建新实例、
 * 健康探测通过后原子替换，探测失败则保持旧实例（`INV-CFG-002`）。
 *
 * <p><b>D6 在文档原始契约（`M-LLM-01-llm-registry.md` §2）之上新增了 {@code pin}/
 * {@code forPinned}/{@code release} 三个方法</b>——落实 `ADR-015`（版本钉住）：
 * "模型/提示词的热更新只对新诊断生效；在途诊断在启动时钉住其版本快照，全程不变"。
 * 这不是随手加的方法，是 `prior_art_and_design_influences.md` §8 引用 Restate 教训后
 * 明确要补的设计缺口——原始的 `current()` 契约只回答"现在用哪个模型"，一次诊断如果
 * 中途反复调用 `current()`，模型被换掉之后，同一次诊断会拼出"前半段用模型 A、后半段
 * 用模型 B"的结论，这种错误**不会报错，只会给一个看起来合理的错误答案**——对一个
 * 结论要驱动资金处置的系统，这比 crash 可怕得多。</p>
 */
public interface LlmRegistry {

    /** 取当前生效客户端。永不返回 null（INV-CFG-002）。 */
    LlmClient current();

    /** 当前生效的模型标识，用于写入 Finding 事件的 modelVersion。 */
    String currentModelId();

    /** 手动触发切换（配置监听也走这里）。 */
    SwitchResult switchTo(String modelId);

    /**
     * 钉住一次诊断的版本快照——捕获调用瞬间的 {@code (modelId, promptVersion, toolSchemaVersion)}，
     * 并记住"这次诊断应该继续用哪一个 {@link LlmClient} 实例"，不受之后任何全局切换影响。
     * 同一个 {@code diagnosisId} 重复调用是幂等的：返回第一次钉住时的那份快照。
     */
    VersionSnapshot pin(String diagnosisId);

    /**
     * 取一个已钉住诊断应该使用的客户端——即使全局 {@link #current()} 已经因为热切换
     * 指向了别的实例，这里依然返回 {@link #pin} 那一刻捕获的那一个。
     *
     * @throws IllegalStateException 从未对这个 diagnosisId 调用过 {@link #pin}
     */
    LlmClient forPinned(String diagnosisId);

    /**
     * 取这个 diagnosisId（诊断编号）在首次 pin（钉住）时冻结的 PromptSnapshot（提示词快照）。
     * 后续全局发布新提示词不会影响已经开始的诊断。
     */
    PromptSnapshot promptForPinned(String diagnosisId);

    /** 诊断结束，释放钉住记录。 */
    void release(String diagnosisId);
}

