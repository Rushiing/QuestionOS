package com.questionos.backend.agent;

/**
 * 沙盘首轮 LLM 分诊结果：用于步骤 ①「议题确认与入室」卡片与持久化。
 */
public record SandboxClassificationResult(
        SandboxDeliberationScene scene,
        String normalizedIssue,
        String confidence,
        boolean forcedSecondary
) {
    public static SandboxClassificationResult fromSceneOnly(SandboxDeliberationScene scene) {
        return new SandboxClassificationResult(scene, "", "UNKNOWN", false);
    }
}
