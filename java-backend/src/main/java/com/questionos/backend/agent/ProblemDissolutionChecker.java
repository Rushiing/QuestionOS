package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 沙盘步骤①：在追问前，用 LLM 检测伪问题、XY问题、症状vs根因、信息缺陷，帮用户形成**真实决策议题**。 */
@Component
public class ProblemDissolutionChecker {
    private static final Logger log = LoggerFactory.getLogger(ProblemDissolutionChecker.class);

    private static final String DISSOLUTION_SYSTEM = """
            你是「问题溶解检测器」，Agora 审议系统第①步的质量把关。
            任务：通读用户累积发言，检测**伪问题、XY问题、症状vs根因混淆、信息严重缺陷**。

            ## 三类检测（任一触发则需汇报）

            1. **伪问题 Pseudo-Problem**：问题**本身无决策张力**，答案已包含于问题陈述
               - 例：「我应该快乐吗？」「我应该在乎别人怎么看吗？」「我应该有梦想吗？」
               - 特征：选项为哲学公理（快乐好、健康好、自由好）或人类不可避免的条件（时间有限、会死亡）

            2. **XY问题**：用户咨询**技术/方法（Y）**，但真实困境在**需求或上游（X）**
               - 例：「怎样快速清空焦虑症状？」（Y = 快速法术），真问题是「如何找到重心」（X）
               - 例：「应该优化这个数据库查询吗？」（Y = 优化），真问题是「该重构整个架构吗」（X）
               - 特征：用户全力论证「为什么要做 Y」，但越论证反而陷入循环

            3. **症状vs根因混淆**：用户把**症状当决策对象**，而真正决策应指向**动因**
               - 例：「焦虑导致失眠，我要不要吃安眠药？」（症状：失眠），真问题可能是「为什么焦虑、如何消解焦虑根源」
               - 例：「团队离职严重，招不到人」（症状：离职率），真问题是「为什么流失、是否管理/薪资/文化问题」

            4. **信息严重缺陷**：关键背景缺失，无法开展任何严肃讨论
               - 例：「要不要投资加密货币？」（无：风险偏好、资金规模、财务目标、时间窗口）
               - 例：「我们要做国际化吗？」（无：现有产品成熟度、资金、目标市场、竞争态势）

            ## 输出格式
            仅输出一个合法 JSON 对象，不要代码块、不要文字：
            {
              "isPseudoProblem": boolean,
              "pseudoProblemExplain": "若 true，解释哲学公理性或为何无决策张力；否则空字符串",
              "isXyProblem": boolean,
              "xyExplain": "若 true，说明表述的 Y（技术/方法）与可能的真问题 X；否则空字符串",
              "isSymptomVsRoot": boolean,
              "symptomExplain": "若 true，识别所述症状与可能的根因；否则空字符串",
              "hasInformationDeficit": boolean,
              "deficitDetails": "缺失的关键上下文（逗号分隔）或空字符串",
              "overallAssessment": "综合判断：「问题形成」「需要澄清」「可严肃推演」之一",
              "suggestedReframe": "若检测到混淆，建议的问题重述（不超过 30 字）；否则空字符串"
            }
            """;

    private final OpenClawInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public ProblemDissolutionChecker(OpenClawInvokeService invokeService, ObjectMapper objectMapper) {
        this.invokeService = invokeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 检测用户累积发言中的问题质量问题（伪问题、XY、症状vs根因、信息缺陷）。
     * 返回 {@code null} 或 {@code READY} 时表示可进入步骤②；
     * 返回 {@code DISSOLUTION_DETECTED} 或类似标志时表示需先在步骤①处理。
     */
    public Mono<DissolutionCheckResult> checkAsync(
            String combinedIssueStatement,
            SandboxDeliberationScene scene
    ) {
        String issue = combinedIssueStatement == null ? "" : combinedIssueStatement.trim();
        if (issue.isBlank()) {
            return Mono.just(new DissolutionCheckResult(
                    null,
                    "问题表述为空，无法判断",
                    false,
                    false,
                    false,
                    false,
                    ""
            ));
        }
        String snippet = issue.length() > 2400 ? issue.substring(0, 2400) + "\n…（已截断）" : issue;
        String sceneHint = scene == null ? "" : "（暂定审议室：" + scene.name() + "）";
        String userPayload = "用户累积发言：\n\n" + snippet + sceneHint;

        return invokeService.invokeDefaultLlmCompact(
                DISSOLUTION_SYSTEM,
                userPayload,
                "dissolution:check",
                180,
                25
        )
                .timeout(Duration.ofSeconds(35))
                .map(raw -> parseResult(raw))
                .doOnNext(result -> {
                    if (result.hasIssues()) {
                        log.info("dissolution check detected: pseudo={} xy={} symptom={} deficit={} overall={}",
                                result.isPseudoProblem,
                                result.isXyProblem,
                                result.isSymptomVsRoot,
                                result.hasInformationDeficit,
                                result.overallAssessment);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("dissolution check failed: {}", e.toString());
                    return Mono.just(new DissolutionCheckResult(null, "检查失败：" + e.getMessage(), false, false, false, false, ""));
                });
    }

    private DissolutionCheckResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return new DissolutionCheckResult(null, "模型无输出", false, false, false, false, "");
        }
        try {
            String s = raw.trim();
            int brace = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (brace >= 0 && end > brace) {
                s = s.substring(brace, end + 1);
            }
            JsonNode root = objectMapper.readTree(s);
            boolean pseudo = root.path("isPseudoProblem").asBoolean(false);
            boolean xy = root.path("isXyProblem").asBoolean(false);
            boolean symptom = root.path("isSymptomVsRoot").asBoolean(false);
            boolean deficit = root.path("hasInformationDeficit").asBoolean(false);
            String assessment = root.path("overallAssessment").asText("");
            String reframe = root.path("suggestedReframe").asText("");

            List<String> reasons = new ArrayList<>();
            if (pseudo) {
                String explain = root.path("pseudoProblemExplain").asText("");
                reasons.add("伪问题：" + explain);
            }
            if (xy) {
                String explain = root.path("xyExplain").asText("");
                reasons.add("XY问题：" + explain);
            }
            if (symptom) {
                String explain = root.path("symptomExplain").asText("");
                reasons.add("症状vs根因：" + explain);
            }
            if (deficit) {
                String details = root.path("deficitDetails").asText("");
                reasons.add("信息缺陷：" + details);
            }

            String summary = reasons.isEmpty() ? "（无检测）" : String.join(" | ", reasons);
            return new DissolutionCheckResult(assessment, summary, pseudo, xy, symptom, deficit, reframe);
        } catch (Exception e) {
            log.warn("dissolution result parse failed: {}", e.toString());
            return new DissolutionCheckResult(null, "结果解析失败", false, false, false, false, "");
        }
    }

    /** 检测结果：若有任何标志为 true，则表示需要在步骤①处理。 */
    public static class DissolutionCheckResult {
        public final String overallAssessment; // "问题形成", "需要澄清", "可严肃推演", null
        public final String summary; // 人类可读的检测理由
        public final boolean isPseudoProblem;
        public final boolean isXyProblem;
        public final boolean isSymptomVsRoot;
        public final boolean hasInformationDeficit;
        public final String suggestedReframe;

        public DissolutionCheckResult(
                String overallAssessment,
                String summary,
                boolean isPseudoProblem,
                boolean isXyProblem,
                boolean isSymptomVsRoot,
                boolean hasInformationDeficit,
                String suggestedReframe
        ) {
            this.overallAssessment = overallAssessment;
            this.summary = summary;
            this.isPseudoProblem = isPseudoProblem;
            this.isXyProblem = isXyProblem;
            this.isSymptomVsRoot = isSymptomVsRoot;
            this.hasInformationDeficit = hasInformationDeficit;
            this.suggestedReframe = suggestedReframe;
        }

        public boolean hasIssues() {
            return isPseudoProblem || isXyProblem || isSymptomVsRoot || hasInformationDeficit;
        }

        public boolean isReadyForStep2() {
            return !hasIssues() && ("问题形成".equals(overallAssessment) || "可严肃推演".equals(overallAssessment));
        }
    }
}
