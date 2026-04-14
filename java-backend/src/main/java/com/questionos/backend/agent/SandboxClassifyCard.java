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
            case "LOW" -> r.forcedSecondary() ? "中（已二次校准入室）" : "中";
            default -> "—";
        };
        String forcedNote = r.forcedSecondary()
                ? "\n\n> 初次分诊偏泛或信心偏低，已启动**强制入室**，将议题暂钉在 **"
                        + roomTitle
                        + "** 主轴上继续；若与你的心意不符，可用一句话纠正「我到底在决策什么」。\n"
                : "";
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
                + forcedNote
                + interactionBlock
                + "\n---\n\n"
                + (needClarification
                ? "当前暂停在步骤①，等待你补充后再进入 **审议路由（步骤 ②）**。\n"
                : "接下来展示 **审议路由（步骤 ②）**：思想家面板与追问表；其后进入多角色轮流发言。\n");
    }
}
