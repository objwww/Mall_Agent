package com.trade.mall.agent.llm;

/**
 * VersionSnapshot —— `ADR-015`（版本钉住）的值对象本体：`(modelId, promptVersion,
 * toolSchemaVersion)`。真正归属的聚合是 `Diagnosis`（`ddd_design.md` §7.1 的 BC-2），
 * 但 `Diagnosis` 聚合要到 D7-D8 才出现——D6 独立交付，这个 VO 暂时挂在 `llm` 包下，
 * 由 {@link LlmRegistry#pin} 产出；D7 接入 `Diagnosis` 聚合时，`Diagnosis.request()`
 * 工厂会在创建时调用一次 `pin()` 把返回值钉进聚合自己的字段，这个 VO 的定义不需要搬家，
 * 只是多了一个持有它的地方（同 D5 `EvidenceBundle` 类头"用 anchor 代替 diagnosisId"
 * 那条简化说明是同一类"提前定义契约，等真正的聚合出现时只是多一个使用方"的处理方式）。
 *
 * <p>三个字段一旦钉入就不可变——这正是"版本钉住"这个词的字面意思：一次诊断全程只认
 * 创建时看到的这一份快照，全局热切换（无论是模型热切换还是提示词发布新版本）只影响
 * <b>之后新开始</b>的诊断，不会让一次进行中的诊断出现"前半段用模型 A 的推理、
 * 后半段用模型 B 的推理"这种拼接结论——`prior_art_and_design_influences.md` §8
 * 引用 Restate 的教训："The journal no longer matches the code"，这种错误不会报错，
 * 只会悄悄给一个看起来合理的结论，比 crash 更难发现。</p>
 */
public record VersionSnapshot(String modelId, String promptVersion, String skillVersion,
                              String toolSchemaVersion, String toolManifestDigest) implements java.io.Serializable {
    private static final long serialVersionUID = 0L;
    public static final String LEGACY_SKILL_VERSION = "legacy-no-skill";
    public static final String LEGACY_TOOL_MANIFEST_DIGEST = "legacy-no-manifest";

    /** 兼容升级前调用方；升级前运行没有真实 Skill 指令。 */
    public VersionSnapshot(String modelId, String promptVersion, String toolSchemaVersion) {
        this(modelId, promptVersion, LEGACY_SKILL_VERSION, toolSchemaVersion, LEGACY_TOOL_MANIFEST_DIGEST);
    }

    public VersionSnapshot(String modelId, String promptVersion, String skillVersion, String toolSchemaVersion) {
        this(modelId, promptVersion, skillVersion, toolSchemaVersion, LEGACY_TOOL_MANIFEST_DIGEST);
    }

    public VersionSnapshot {
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId must not be blank");
        if (promptVersion == null || promptVersion.isBlank()) throw new IllegalArgumentException("promptVersion must not be blank");
        if (skillVersion == null || skillVersion.isBlank()) skillVersion = LEGACY_SKILL_VERSION;
        if (toolSchemaVersion == null || toolSchemaVersion.isBlank()) throw new IllegalArgumentException("toolSchemaVersion must not be blank");
        if (toolManifestDigest == null || toolManifestDigest.isBlank()) toolManifestDigest = LEGACY_TOOL_MANIFEST_DIGEST;
    }
}
