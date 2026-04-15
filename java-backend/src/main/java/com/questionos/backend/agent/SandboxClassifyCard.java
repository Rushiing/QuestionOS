package com.questionos.backend.agent;

/**
 * 沙盘步骤 ①：议题确认与进入 Agora 审议室（在「审议路由」卡片之前展示）。
 */
public final class SandboxClassifyCard {
    private SandboxClassifyCard() {}

    public static String markdown(SandboxClassificationResult r) {
        return markdown(r, false);
    }

    public static String markdownNeedClarification(SandboxClassificationResult r) {
        return markdown(r, true);
    }

    /**
     * 议题尚未形成可分诊的「决策对象」：不调用分诊模型、不写入审议室、不进入步骤②。
     * 与 {@link #markdownInvalidInput(String)} 区分：此处允许有字，但多为敷衍/缺口过大。
     */
    public static String markdownIssueNotYetConcrete(String combinedIssue) {
        String shown = combinedIssue == null ? "" : combinedIssue.trim().replaceAll("\\s+", " ");
        if (shown.length() > 120) {
            shown = shown.substring(0, 120) + "…";
        }
        if (shown.isBlank()) {
            shown = "（空）";
        }
        return "### \uD83D\uDD0E 议题确认与入室（步骤 ①）\n\n"
                + "**当前信息仍不足以可靠分诊，暂不进入步骤②。**\n\n"
                + "**已读到的输入汇总**（可能含多句）：\n\n> "
                + shown
                + "\n\n请用**至少一两句完整中文**说明：你在**决策什么**、**主要顾虑或约束**、以及**时间边界**（若适用）。"
                + "单字敷衍、纯附和或「不知道」类回答无法入室。\n\n"
                + "---\n\n"
                + "当前仍停在步骤①；补充清楚后，才会展示 **审议路由（步骤 ②）** 并进入多角色发言。\n";
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

    private static String markdown(SandboxClassificationResult r, boolean needClarification) {
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
        String interactionBlock = needClarification
                ? "\n\n### 💬 需要你补一句（继续步骤 ①）\n\n"
                + "请用一句话补充：**你这次最想保住什么、最怕失去什么、以及时间约束**。补充后我再正式入室并进入步骤②。\n"
                : "";

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
}
