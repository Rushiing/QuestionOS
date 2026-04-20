package com.questionos.backend.agent;

/**
 * 沙盘首轮 LLM 分诊结果：用于步骤 ①「议题确认与入室」卡片与持久化。
 *
 * @param semanticIgnitionOverride 为 true 表示：分诊室模型原为 {@code LOW}，经「是否足以启动沙盘」语义把关后升为 {@code HIGH} 入室。
 */
public record SandboxClassificationResult(
        SandboxDeliberationScene scene,
        String normalizedIssue,
        String confidence,
        boolean forcedSecondary,
        boolean semanticIgnitionOverride
) {
    public static SandboxClassificationResult fromSceneOnly(SandboxDeliberationScene scene) {
        return new SandboxClassificationResult(scene, "", "UNKNOWN", false, false);
    }

    /** 在分诊为 LOW 且语义点火为真时调用：写入 HIGH 并打标，供步骤②与卡片展示。 */
    public SandboxClassificationResult withSemanticIgnitionHigh() {
        return new SandboxClassificationResult(scene, normalizedIssue, "HIGH", forcedSecondary, true);
    }
}
