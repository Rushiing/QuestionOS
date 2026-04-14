package com.questionos.backend.agent;

/**
 * 沙盘步骤 ①：议题确认与进入 Agora 审议室（在「审议路由」卡片之前展示）。
 */
public final class SandboxClassifyCard {
    private SandboxClassifyCard() {}

    public static String markdown(SandboxClassificationResult r) {
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
                + "\n---\n\n"
                + "接下来展示 **审议路由（步骤 ②）**：思想家面板与追问表；其后进入多角色轮流发言。\n";
    }
}
