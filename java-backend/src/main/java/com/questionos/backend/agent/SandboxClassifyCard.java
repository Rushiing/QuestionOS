package com.questionos.backend.agent;

/**
 * 沙盘步骤 ①：议题确认与进入 Agora 审议室（在「审议路由」卡片之前展示）。
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
     * @param calibrationFollowupMd 由 {@link MainCalibrateAgent#generateSandboxStep1ClarifyFollowup} 生成的 Markdown；
     *                                为空时表示大模型多次重试仍不可用，展示「请重试」说明（非伪造追问）。
     */
    public static String markdownNeedClarification(SandboxClassificationResult r, String calibrationFollowupMd) {
        return markdown(r, true, calibrationFollowupMd);
    }

    /**
     * 议题尚未形成可分诊的「决策对象」：不调用分诊模型、不写入审议室、不进入步骤②。
     * 与 {@link #markdownInvalidInput(String)} 区分：此处允许有字，但多为敷衍/缺口过大。
     */
    public static String markdownIssueNotYetConcrete(String combinedIssue) {
        return markdownIssueNotYetConcrete(combinedIssue, null);
    }

    public static String markdownIssueNotYetConcrete(String combinedIssue, String calibrationFollowupMd) {
        String shown = combinedIssue == null ? "" : combinedIssue.trim().replaceAll("\\s+", " ");
        if (shown.length() > 120) {
            shown = shown.substring(0, 120) + "…";
        }
        if (shown.isBlank()) {
            shown = "（空）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### \uD83D\uDD0E 议题确认与入室（步骤 ①）\n\n")
                .append("**当前信息仍不足以可靠分诊，暂不进入步骤②。**\n\n")
                .append("**已读到的输入汇总**（可能含多句）：\n\n> ")
                .append(shown)
                .append("\n\n");
        if (calibrationFollowupMd != null && !calibrationFollowupMd.isBlank()) {
            sb.append(calibrationFollowupMd.trim()).append("\n\n");
        } else {
            sb.append(step1ClarifyModelUnavailableBlock())
                    .append("若你仍想先手写补充，请用**至少一两句完整中文**说明：你在**决策什么**、**主要顾虑或约束**、以及**时间边界**（若适用）。")
                    .append("单字敷衍、纯附和或「不知道」类回答无法入室。\n\n");
        }
        sb.append("---\n\n")
                .append("当前仍停在步骤①；补充清楚后，才会展示 **审议路由（步骤 ②）** 并进入多角色发言。\n");
        return sb.toString();
    }

    /** 输入噪声拦截卡：在步骤①先让用户补足最小可分诊信息。 */
    public static String markdownInvalidInput(String rawInput) {
        String shown = rawInput == null ? "" : rawInput.trim();
        if (shown.length() > 48) {
            shown = shown.substring(0, 48) + "…";
        }
        if (shown.isBlank()) {
            shown = "（空）";
        }
        return "### \uD83D\uDD0E 议题确认与入室（步骤 ①）\n\n"
                + "**检测到当前输入信息不足，暂不进入步骤②。**\n\n"
                + "**你刚输入的是**：`" + shown + "`\n\n"
                + "请补成一句可分诊的问题，至少包含以下三项中的两项：\n"
                + "- 你要达成的目标\n"
                + "- 你最担心的损失或约束\n"
                + "- 时间边界（如 1 周/1 月）\n\n"
                + "示例：`用户流失升高，我想在 4 周内把留存拉回 15%，同时不增加预算。`\n\n"
                + "---\n\n"
                + "当前仍停在步骤①，等待你补充后再进入 **审议路由（步骤 ②）**。\n";
    }

    private static String markdown(SandboxClassificationResult r, boolean needClarification, String calibrationFollowupMd) {
        SandboxDeliberationScene sc = r.scene();
        String roomTitle = SandboxAgoraRouteCard.roomTitle(sc);
        String roomSubtitle = SandboxAgoraRouteCard.roomSubtitle(sc);
        String issue = r.normalizedIssue() == null || r.normalizedIssue().isBlank()
                ? "（议题较泛，将在下文追问中逐步钉死）"
                : r.normalizedIssue().trim();
        String confLabel = switch (r.confidence() == null ? "" : r.confidence().toUpperCase()) {
            case "HIGH" -> "高";
            case "LOW" -> "中";
            default -> "—";
        };
        String interactionBlock = "";
        if (needClarification) {
            if (calibrationFollowupMd != null && !calibrationFollowupMd.isBlank()) {
                interactionBlock = "\n\n" + calibrationFollowupMd.trim() + "\n";
            } else {
                interactionBlock = "\n\n" + step1ClarifyModelUnavailableBlock()
                        + "你也可以先**直接回复一两句**，围绕上面「已理解的决策焦点」补充背景或关键约束。\n";
            }
        }

        return "### \uD83D\uDD0E 议题确认与入室（步骤 ①）\n\n"
                + "**已理解的决策焦点**\n\n"
                + issue
                + "\n\n**进入审议室**\n\n"
                + "**"
                + roomTitle
                + "**（"
                + roomSubtitle
                + "）\n\n"
                + "**分诊信心**："
                + confLabel
                + interactionBlock
                + "\n---\n\n"
                + (needClarification
                ? "当前暂停在步骤①，等待你补充后再进入 **审议路由（步骤 ②）**。\n"
                : "接下来展示 **审议路由（步骤 ②）**：思想家面板与追问表；其后进入多角色轮流发言。\n");
    }

    /** 步骤①智能追问未生成时展示：不冒充模型输出，引导用户重试发送。 */
    private static String step1ClarifyModelUnavailableBlock() {
        return "### ⚠️ 本轮智能追问未生成\n\n"
                + "大模型在多次重试后仍未返回可用结果（可能为超时、网关或上游空响应）。"
                + "请**稍后再点击发送**重试同一句或略改写后再发；追问只会来自真模型，不会用本地模板冒充。\n\n";
    }
}
