package com.questionos.backend.agent;

/**
 * 沙盘步骤①中的「问题溶解检测」卡片：在生成追问前，检测伪问题、XY问题等。
 * 若检测到问题质量问题，呈现给用户确认；若用户确认问题本身可能不实，提示反思。
 */
public final class SandboxProblemDissolutionCard {
    private SandboxProblemDissolutionCard() {}

    /**
     * 问题溶解检测结果的 Markdown 呈现。
     * 若没有检测到问题，返回空字符串（不展示卡片）。
     */
    public static String markdown(ProblemDissolutionChecker.DissolutionCheckResult result) {
        if (!result.hasIssues()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 问题质量检查\n\n");

        // 展示检测到的问题类型
        if (result.isPseudoProblem) {
            sb.append("**⚠️ 可能是伪问题**\n\n");
            sb.append("这个问题的答案可能已经包含在问题本身里。例如：\n");
            sb.append("- 「我应该快乐吗？」——快乐是人的基本需求，答案是 yes\n");
            sb.append("- 「我应该在乎别人怎么看吗？」——人天生社交，一定程度的在乎是正常的\n\n");
        }

        if (result.isXyProblem) {
            sb.append("**⚠️ 可能是 XY 问题**\n\n");
            sb.append("你问的方案(Y)可能不是真正的困境(X)。\n");
            sb.append("真正的问题可能是：").append(result.suggestedReframe.isEmpty() ? "（待澄清）" : result.suggestedReframe).append("\n\n");
        }

        if (result.isSymptomVsRoot) {
            sb.append("**⚠️ 症状 vs 根因混淆**\n\n");
            sb.append("你描述的可能是表象，根源在别处。例如：\n");
            sb.append("- 「失眠」←可能的根因：「焦虑」或「压力」\n");
            sb.append("- 「离职率高」←可能的根因：「管理风格」或「薪资」或「发展空间」\n\n");
        }

        if (result.hasInformationDeficit) {
            sb.append("**❓ 关键信息缺失**\n\n");
            sb.append("以下背景信息可能对讨论很重要，但你还未提及：\n");
            sb.append("- 你的风险偏好、时间窗口、资金约束\n");
            sb.append("- 目标市场、竞争态势、现有产品成熟度\n");
            sb.append("- 团队状态、组织结构、决策权限\n\n");
        }

        // 总体评估
        sb.append("### 接下来怎么做\n\n");
        if ("需要澄清".equals(result.overallAssessment)) {
            sb.append("建议**先停下来**，在追问之前反思一下：\n");
            sb.append("1. 这个问题的**真正困境**是什么？（而不是表面症状）\n");
            sb.append("2. 你**最想保住**什么、**最怕失去**什么？\n");
            sb.append("3. 如果不解决这个，**最坏结果**是什么？\n\n");
            sb.append("用一两句话重新表述你的困境，我们再继续。\n");
        } else {
            sb.append("上述检测仅供参考。如果你认为问题本身是清晰的，可以继续；\n");
            sb.append("或者如果你想先反思一下，也可以重新表述。\n");
        }

        return sb.toString();
    }

    public static String markdownNoIssues(String reason) {
        return "";
    }
}
