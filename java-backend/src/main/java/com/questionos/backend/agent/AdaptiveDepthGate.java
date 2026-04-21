package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 沙盘步骤②之后（Round 1 完成）：评估多角色间的共识强度，决策是否启动 Round 2 Hegelian 深挖。
 * 用于自适应深度：HIGH 共识时可跳过 Round 2，MEDIUM/LOW 时推荐深挖。
 */
@Component
public class AdaptiveDepthGate {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveDepthGate.class);

    private static final String CONSENSUS_ASSESSMENT_SYSTEM = """
            你是 Agora 审议的「深度门槛评估器」，在 Round 1 多角色独立分析后评估共识强度。
            任务：阅读所有 Round 1 角色的分析，评估他们在**核心方向上的共识程度**。

            ## 三层共识等级

            ### HIGH 共识 (>80% 同向)
            - 大多数角色指向**同一方向的建议**（尽管理由或细节可能不同）
            - 有分歧但都是**实现方式/优先级的细节**，不是**根本方向**
            - 例：都认为「应该先优化 X，再考虑 Y」，但对 X 的方法细节有分歧
            - 结论：**可接受**跳过 Round 2，多数意见已足够推动决策

            ### MEDIUM 共识 (60-80% 同向)
            - 存在**有实质区别的两条主线**（例：激进 vs 保守、快 vs 稳、技术 vs 人文）
            - 多数倾向一边，但**少数观点有真正的价值**（不只是细节）
            - 例：大多数说「应该调整战略」，但有人坚持「应该深化现有方向」；两方都有理
            - 结论：**建议**深挖，少数观点值得对话

            ### LOW 共识 (<60% 同向)
            - **根本分歧**：角色对问题的定义、优先级、价值判断本身有重大差异
            - 例：一派认为「核心问题是市场」，另一派认为「核心问题是产品」，第三派认为「是团队」
            - 没有多数派，或多数派优势不足 50%
            - 结论：**必须**深挖，否则武断地选一方会遗漏关键视角

            ## 评估方法
            1. 提取每位角色的**核心建议/判断**（1 句话）
            2. 对比这些建议的**方向是否一致**（不是理由，只看指向）
            3. 统计**同向的比例**
            4. 识别**最强的少数派观点**（即使是一个人，如果论证有力也要标出）
            5. 给出 MEDIUM 时，简述少数派的**合理性根据**

            ## 输出格式
            仅输出一个合法 JSON 对象：
            {
              "consensusLevel": "HIGH|MEDIUM|LOW",
              "majorityView": "多数角色指向的核心方向（1～2句中文）",
              "minorityView": "少数角色的关键观点（若有）；若无异议则空字符串",
              "minorityStrength": "若 MEDIUM/LOW，描述少数派为何有价值（逻辑、风险、补充视角等）",
              "reasoning": "简述共识评估的依据（参考哪些角色的观点）",
              "shouldDeepen": "基于共识级别的建议：「HIGH 可接受跳过」 / 「MEDIUM 建议深挖」 / 「LOW 必须深挖」",
              "allViews": ["角色1的核心建议", "角色2的核心建议", ...]
            }
            """;

    private final OpenClawInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public AdaptiveDepthGate(OpenClawInvokeService invokeService, ObjectMapper objectMapper) {
        this.invokeService = invokeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 在 Round 1 完成后调用，评估是否应深入 Round 2 Hegelian 对话。
     * 返回共识级别与建议。
     */
    public Mono<ConsensusAssessment> assessAsync(List<String> round1Analyses) {
        if (round1Analyses == null || round1Analyses.isEmpty()) {
            return Mono.just(new ConsensusAssessment(
                    "LOW",
                    "",
                    "",
                    "",
                    "无 Round 1 输出可评估",
                    "未知",
                    List.of()
            ));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("多角色 Round 1 独立分析如下（将各角色核心观点整理后评估共识度）：\n\n");
        for (int i = 0; i < round1Analyses.size(); i++) {
            String a = round1Analyses.get(i);
            sb.append("【角色 ").append(i + 1).append("】\n")
                    .append(truncateForAssessment(a, 1200))
                    .append("\n\n");
        }

        String payload = sb.toString();
        return invokeService.invokeDefaultLlmCompact(
                CONSENSUS_ASSESSMENT_SYSTEM,
                payload,
                "adaptive:consensus-assess",
                180,
                20
        )
                .timeout(Duration.ofSeconds(30))
                .map(this::parseAssessment)
                .doOnNext(result -> {
                    log.info("adaptive consensus assessed: consensusLevel={} shouldDeepen={}",
                            result.consensusLevel, result.shouldDeepen);
                })
                .onErrorResume(e -> {
                    log.warn("consensus assessment failed: {}", e.toString());
                    return Mono.just(new ConsensusAssessment(
                            "LOW",
                            "",
                            "",
                            "评估失败：" + e.getMessage(),
                            "请检查 LLM 服务",
                            "评估失败，建议手动决策",
                            List.of()
                    ));
                });
    }

    private ConsensusAssessment parseAssessment(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ConsensusAssessment("LOW", "", "", "", "模型无输出", "评估失败", List.of());
        }
        try {
            String s = raw.trim();
            int brace = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (brace >= 0 && end > brace) {
                s = s.substring(brace, end + 1);
            }
            JsonNode root = objectMapper.readTree(s);

            String consensusLevel = root.path("consensusLevel").asText("LOW");
            String majorityView = root.path("majorityView").asText("");
            String minorityView = root.path("minorityView").asText("");
            String minorityStrength = root.path("minorityStrength").asText("");
            String reasoning = root.path("reasoning").asText("");
            String shouldDeepen = root.path("shouldDeepen").asText("评估失败");

            List<String> allViews = List.of();
            JsonNode viewsNode = root.path("allViews");
            if (viewsNode.isArray()) {
                allViews = viewsNode.findValues("ignored").stream()
                        .map(n -> n.asText(""))
                        .toList();
            }

            return new ConsensusAssessment(
                    consensusLevel,
                    majorityView,
                    minorityView,
                    minorityStrength,
                    reasoning,
                    shouldDeepen,
                    allViews
            );
        } catch (Exception e) {
            log.warn("consensus assessment parse failed: {}", e.toString());
            return new ConsensusAssessment("LOW", "", "", "", "结果解析失败", "评估失败", List.of());
        }
    }

    private static String truncateForAssessment(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    /** 共识评估结果：用于决策是否进入 Round 2 深挖。 */
    public static class ConsensusAssessment {
        public final String consensusLevel; // HIGH, MEDIUM, LOW
        public final String majorityView;
        public final String minorityView;
        public final String minorityStrength;
        public final String reasoning;
        public final String shouldDeepen;
        public final List<String> allViews;

        public ConsensusAssessment(
                String consensusLevel,
                String majorityView,
                String minorityView,
                String minorityStrength,
                String reasoning,
                String shouldDeepen,
                List<String> allViews
        ) {
            this.consensusLevel = consensusLevel;
            this.majorityView = majorityView;
            this.minorityView = minorityView;
            this.minorityStrength = minorityStrength;
            this.reasoning = reasoning;
            this.shouldDeepen = shouldDeepen;
            this.allViews = allViews;
        }

        public boolean isHighConsensus() {
            return "HIGH".equalsIgnoreCase(consensusLevel);
        }

        public boolean shouldAskForDeepenChoice() {
            return "MEDIUM".equalsIgnoreCase(consensusLevel) || "LOW".equalsIgnoreCase(consensusLevel);
        }
    }
}
