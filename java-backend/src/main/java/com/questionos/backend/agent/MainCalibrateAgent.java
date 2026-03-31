package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@Component
public class MainCalibrateAgent implements AgentExecutor {
    /**
     * 与 v0.2/backend/app/prompts/__init__.py 中 QUESTION_GENERATION_PROMPT 对齐：
     * 仅输出 JSON（questions / detected_biases / clarity_change / reasoning / suggested_direction）。
     */
    private static final String CALIBRATION_PROMPT = """
            你是问题校准专家。你的任务是通过追问帮助用户理清问题本质。

            ## 核心原则
            1. 不提供解决方案，只生成追问
            2. questions 数组长度 1～3，问题要具体、可回答、有层级递进
            3. 识别并列出 detected_biases（若无则 []）
            4. 引导用户自我发现，不替用户下结论

            ## 禁止行为
            - 在 JSON 外输出任何文字、Markdown、代码围栏
            - 在 questions 里写「行动建议」或替用户选方案（只能是问句或澄清式表述）
            - 使用「你应该」「建议你」等指导性句式

            ## 输出格式（仅一行合法 JSON 对象，不要换行前缀/后缀）
            {
              "questions": ["追问1", "追问2", "追问3"],
              "detected_biases": ["偏差1", "偏差2"],
              "clarity_change": 0.1,
              "reasoning": "为什么这样追问",
              "suggested_direction": "建议探索方向（探索向，不是行动清单）"
            }

            clarity_change：模拟本轮若用户认真回答后，清晰度变化，范围约 -0.1 ～ +0.3。

            用户消息（本轮输入）在 user 消息中给出，请据此生成 JSON。
            """;

    private static final String FALLBACK_JSON = """
            {"questions":["请先用自己的话描述：你当前最想解决的一个具体问题是什么？它卡住你的最关键不确定点是什么？"],"detected_biases":[],"clarity_change":0.0,"reasoning":"降级占位：模型调用失败","suggested_direction":"重试发送或检查 LLM 配置"}
            """;

    private final OpenClawInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public MainCalibrateAgent(OpenClawInvokeService invokeService, ObjectMapper objectMapper) {
        this.invokeService = invokeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String agentId() {
        return "main-calibrate";
    }

    @Override
    public Flux<AgentReplyChunk> reply(String sessionId, long turnId, String input) {
        return invokeService.invokeDefaultLlm(CALIBRATION_PROMPT, input)
                .map(this::formatCalibrationJson)
                .flatMapMany(text -> Flux.just(new AgentReplyChunk("agent_chunk", text)))
                .onErrorResume(e -> Flux.fromIterable(List.of(
                                new AgentReplyChunk("agent_error", "调用失败: " + e.getMessage()),
                                new AgentReplyChunk("agent_chunk", formatCalibrationJson(FALLBACK_JSON))
                        ))
                        .delayElements(Duration.ofMillis(80)));
    }

    /**
     * 将模型返回的 JSON（可含 ```json 围栏）转为可读 Markdown，供接入台展示。
     */
    String formatCalibrationJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = stripMarkdownFence(raw.trim());
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw;
        }
        String json = trimmed.substring(start, end + 1);
        try {
            JsonNode root = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();

            JsonNode questions = root.get("questions");
            if (questions != null && questions.isArray() && questions.size() > 0) {
                sb.append("## 追问\n\n");
                for (int i = 0; i < questions.size(); i++) {
                    String q = textOrEmpty(questions.get(i));
                    if (!q.isEmpty()) {
                        sb.append(i + 1).append(". ").append(q).append("\n\n");
                    }
                }
            }

            JsonNode biases = root.get("detected_biases");
            if (biases != null && biases.isArray() && biases.size() > 0) {
                sb.append("## 认知偏差\n\n");
                for (JsonNode b : biases) {
                    String line = textOrEmpty(b);
                    if (!line.isEmpty()) {
                        sb.append("- ").append(line).append("\n");
                    }
                }
                sb.append("\n");
            }

            if (root.hasNonNull("clarity_change")) {
                sb.append("**清晰度变化（预估）**：").append(root.get("clarity_change").asText()).append("\n\n");
            }

            if (root.hasNonNull("reasoning")) {
                String r = root.get("reasoning").asText().trim();
                if (!r.isEmpty()) {
                    sb.append("## 追问理由\n\n").append(r).append("\n\n");
                }
            }

            if (root.hasNonNull("suggested_direction")) {
                String s = root.get("suggested_direction").asText().trim();
                if (!s.isEmpty()) {
                    sb.append("## 建议探索方向\n\n").append(s).append("\n");
                }
            }

            String out = sb.toString().trim();
            return out.isEmpty() ? raw : out;
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String stripMarkdownFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNl = s.indexOf('\n');
        if (firstNl < 0) {
            return s;
        }
        String inner = s.substring(firstNl + 1);
        int fence = inner.lastIndexOf("```");
        if (fence >= 0) {
            inner = inner.substring(0, fence);
        }
        return inner.trim();
    }

    private static String textOrEmpty(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        if (n.isTextual()) {
            return n.asText().trim();
        }
        if (n.isObject() && n.hasNonNull("question")) {
            return n.get("question").asText().trim();
        }
        return n.toString().trim();
    }
}
