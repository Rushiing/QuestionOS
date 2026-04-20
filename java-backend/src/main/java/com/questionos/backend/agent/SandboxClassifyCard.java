package com.questionos.backend.agent;

/**
 * 沙盘步骤 ①：议题确认与进入 Agora 审议室（在「审议路由」卡片之前展示）。
 * 卡片仅保留四块：本轮追问、追问理由、分诊信心、进入审议室。
 */
public final class SandboxClassifyCard {
    private SandboxClassifyCard() {}

    public static String markdown(SandboxClassificationResult r) {
        return markdown(r, false, null);
    }

    public static String markdownNeedClarification(SandboxClassificationResult r) {
        return markdownNeedClarification(r, null);
    }

    /**
     * @param calibrationFollowupMd 由 {@link MainCalibrateAgent#generateSandboxStep1ClarifyFollowup} 生成的精简 Markdown
     *                                （仅含「## 本轮追问」与「### 追问理由」）；为空表示模型未生成。
     */
    public static String markdownNeedClarification(SandboxClassificationResult r, String calibrationFollowupMd) {
        return markdown(r, true, calibrationFollowupMd);
    }

    public static String markdownIssueNotYetConcrete(String combinedIssue) {
        return markdownIssueNotYetConcrete(combinedIssue, null);
    }

    public static String markdownIssueNotYetConcrete(String combinedIssue, String calibrationFollowupMd) {
        String firstTwo = firstTwoSectionsForIssueNotConcrete(calibrationFollowupMd);
        return joinStep1FourSections(firstTwo, "中", roomLinePending());
    }

    /** 输入噪声拦截：无可分诊议题。 */
    public static String markdownInvalidInput(String rawInput) {
        String shown = rawInput == null ? "" : rawInput.trim();
        if (shown.length() > 48) {
            shown = shown.substring(0, 48) + "…";
        }
        if (shown.isBlank()) {
            shown = "（空）";
        }
        String firstTwo = "## 本轮追问\n\n"
                + "请用**一句中文**写清：你要做的决策对象 + 目标或顾虑中的至少一项 +（若有）时间边界。\n\n"
                + "### 追问理由\n\n"
                + "当前输入过短或像随机字符（例如 `"
                + shown
                + "`），无法可靠分诊。\n";
        return joinStep1FourSections(firstTwo, "—", roomLinePending());
    }

    private static String markdown(SandboxClassificationResult r, boolean needClarification, String calibrationFollowupMd) {
        SandboxDeliberationScene sc = r.scene();
        String roomTitle = SandboxAgoraRouteCard.roomTitle(sc);
        String roomSubtitle = SandboxAgoraRouteCard.roomSubtitle(sc);
        String confLabel = switch (r.confidence() == null ? "" : r.confidence().toUpperCase()) {
            case "HIGH" -> "高";
            case "LOW" -> "中";
            default -> "—";
        };
        String firstTwo;
        if (needClarification) {
            if (calibrationFollowupMd != null && !calibrationFollowupMd.isBlank()) {
                firstTwo = calibrationFollowupMd.trim();
            } else {
                firstTwo = "## 本轮追问\n\n"
                        + "（暂未生成。）请**稍后重试发送**同一句或略改写后再发。\n\n"
                        + "### 追问理由\n\n"
                        + "大模型在多次重试后仍未返回可用 JSON（常见：超时、网关、上游空响应或格式不符）。追问只来自真模型，不会用本地模板冒充。\n";
            }
        } else {
            firstTwo = "## 本轮追问\n\n"
                    + "（无需额外追问。）\n\n"
                    + "### 追问理由\n\n"
                    + "当前分诊为**高信心**，将直接进入步骤②审议路由与多角色发言。\n";
        }
        return joinStep1FourSections(firstTwo, confLabel, roomLineAssigned(roomTitle, roomSubtitle));
    }

    private static String firstTwoSectionsForIssueNotConcrete(String calibrationFollowupMd) {
        if (calibrationFollowupMd != null && !calibrationFollowupMd.isBlank()) {
            return calibrationFollowupMd.trim();
        }
        return "## 本轮追问\n\n"
                + "请用**至少一两句完整中文**说明：你在**决策什么**、**主要顾虑或约束**、以及**时间边界**（若适用）。\n\n"
                + "### 追问理由\n\n"
                + "议题尚未达到「可高信心分诊」门槛；单字敷衍或「不知道」类回答无法入室。\n";
    }

    /** 四段顺序：本轮追问与追问理由（已由上游拼好）→ 分诊信心 → 进入审议室 */
    private static String joinStep1FourSections(String firstTwoMarkdown, String confidenceLabel, String roomBody) {
        return firstTwoMarkdown.trim()
                + "\n\n### 分诊信心\n\n"
                + confidenceLabel
                + "\n\n### 进入审议室\n\n"
                + roomBody.trim()
                + "\n";
    }

    private static String roomLineAssigned(String roomTitle, String roomSubtitle) {
        return "**" + roomTitle + "**（" + roomSubtitle + "）";
    }

    private static String roomLinePending() {
        return "（待定）";
    }
}
