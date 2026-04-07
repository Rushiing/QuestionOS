package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class MainCalibrateAgent implements AgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(MainCalibrateAgent.class);
    /**
     * 思维校准采用 Decision 模式，思路来自开源 skill：
     * https://github.com/riiiku/clarify-skill （Part 2: Decision 模式）
     * 与 clarify 的 Prompt 模式不同：Decision 强调深度、每轮只推进一个问句。
     */
    private static final String CALIBRATION_PROMPT = """
            你是「思维校准」助手，运行在 Decision（决策澄清）模式：帮用户把困扰、纠结、冲动问清楚。
            你不替用户做决定，不输出行动方案清单；只通过提问与回放，帮用户自己浮现判断。

            ## Decision 模式流程（按对话进度选用阶段，可在一轮中落在相邻的连续小步，但每轮对用户只暴露一个核心问句）
            1. scenario_confirm：用 1～2 句话复述你理解的用户处境，并确认「是这样吗？」类确认（可合并进同一个问句）。
            2. language_clarify：维特根斯坦式——从用户话里挑 1 个最糊的关键词，用**一个**短问句拆开（不要一次问多个词）。
            3. socratic：苏格拉底式递进——**每次只输出一个问句**；根据上轮用户的回答往下钻，可覆盖消费/工作方向/关系/说不清的苦闷等场景。
            4. polanyi：仅当用户出现「说不上来」「就是感觉」「你懂的吧」等卡住信号，改用默会知识策略，**只问一个**：
               - negative：最不想看到的结果 / 绝对不选的选项
               - exemplar：身边类似选择的故事镜像
               - past_behavior：过去类似纠结怎么选的、后悔吗
               polanyi_strategy 填 negative|exemplar|past_behavior。
            5. synthesis：把用户**自己说过的话**整理成一段「你似乎得出了…」的逻辑回放，末尾用**一个**短问句请用户确认准不准（不要替用户下最终决心）。
            6. action_anchor：若用户已明显想清，用**一个**轻量问句问「接下来你打算怎么做？」；若仍模糊则本阶段可跳过（phase 仍用 socratic 或 synthesis）。

            ## 与 Prompt 模式的区别（必须遵守）
            - Decision：**questions 数组长度必须为 1**（唯一一个问句，一句以内）。
            - 不要一次抛 2～3 个追问；深度比覆盖更重要。
            - 禁止说教、禁止「你应该」「建议你」、禁止「这很正常」等套话开头。
            - 仍禁止在 JSON 外输出任何字符。

            ## 如何选 phase（结合下方「对话摘录」判断）
            - 首轮用户发言 → 通常 scenario_confirm，必要时带一点 language_clarify。
            - 用户已确认场景且语言仍糊 → language_clarify。
            - 已澄清词汇 → 进入 socratic，每轮一问，直到足够做 synthesis。
            - 用户卡住 → polanyi（一问）。
            - 已有清晰倾向或多轮信息够了 → synthesis。
            - 用户表达已准备好行动 → action_anchor。

            ## 输出格式（仅输出一个合法 JSON 对象，不要 Markdown 围栏、不要前后缀文字）
            {
              "calibration_mode": "decision",
              "phase": "scenario_confirm|language_clarify|socratic|polanyi|synthesis|action_anchor",
              "scenario_echo": "可选，1～2 句，复述用户处境",
              "fuzzy_focus": "可选，本轮要拆的模糊词",
              "questions": ["本轮唯一问句"],
              "polanyi_strategy": "negative|exemplar|past_behavior|null",
              "user_conclusion_mirror": "可选，synthesis 阶段填写：用用户原话逻辑回放",
              "detected_biases": ["若有认知偏差线索则列出，否则 []"],
              "clarity_change": 0.12,
              "reasoning": "极短：为何选此 phase/此问",
              "suggested_direction": "探索向提示，不是替用户拍板"
            }

            clarity_change：预估若用户认真回答后清晰度变化，约 -0.1～0.3。
            用户侧输入在下方「结构化对话包」中给出，请通读后再输出 JSON。
            """;

    private static final String FALLBACK_JSON = """
            {"calibration_mode":"decision","phase":"scenario_confirm","scenario_echo":"","fuzzy_focus":"","questions":["请先简单说：你现在最纠结或最卡住的一件事是什么？用一两句话就够。"],"polanyi_strategy":null,"user_conclusion_mirror":"","detected_biases":[],"clarity_change":0.0,"reasoning":"降级占位：模型调用失败","suggested_direction":"检查 LLM 配置后重试"}
            """;

    private final OpenClawInvokeService invokeService;
    private final ObjectMapper objectMapper;

    @Value("${questionos.llm.streamChatCompletions:true}")
    private boolean streamChatCompletions;
    @Value("${questionos.llm.calibrationStreamUi:true}")
    private boolean calibrationStreamUi;

    /** 追问轮次多时，过长摘录会导致兼容接口拒收或超时；助手侧 Markdown 可短截断 */
    private static final int TRANSCRIPT_MAX_MESSAGES = 16;
    /** 用户单条通常短于助手；摘录里把预算多留给助手侧 Markdown */
    private static final int USER_TRANSCRIPT_MAX = 1200;
    private static final int ASSISTANT_TRANSCRIPT_MAX = 4200;
    /** 控制整段 user 消息体量（含进度说明），避免 glm / DashScope 侧限制 */
    private static final int PAYLOAD_CHAR_BUDGET = 32000;

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
        return replyWithHistory(sessionId, turnId, input, List.of());
    }

    /**
     * 思维校准需要完整对话摘录，才能在 Decision 模式下正确选 phase（单问递进）。
     */
    public Flux<AgentReplyChunk> replyWithHistory(
            String sessionId,
            long turnId,
            String input,
            List<ConversationMessage> history
    ) {
        long tBuild = System.currentTimeMillis();
        String userPayload = buildDecisionPayload(history, input);
        long buildPayloadMs = System.currentTimeMillis() - tBuild;
        int historySize = history == null ? 0 : history.size();
        String stage = "calibration|" + sessionId + "|t" + turnId;
        log.info(
                "main-calibrate buildPayload stage={} buildPayloadMs={} userPayloadChars={} systemPromptChars={} historyMsgs={}",
                stage,
                buildPayloadMs,
                userPayload.length(),
                CALIBRATION_PROMPT.length(),
                historySize);

        if (calibrationStreamUi && streamChatCompletions) {
            StringBuilder rawAcc = new StringBuilder();
            return invokeService.invokeDefaultLlmStreamingDeltas(CALIBRATION_PROMPT, userPayload, stage)
                    .doOnNext(rawAcc::append)
                    .map(d -> new AgentReplyChunk("agent_delta", d))
                    .concatWith(Mono.fromCallable(() -> {
                        String raw = rawAcc.toString();
                        long tf = System.currentTimeMillis();
                        String md = formatCalibrationJson(raw);
                        long formatMarkdownMs = System.currentTimeMillis() - tf;
                        log.info(
                                "main-calibrate formatMarkdown stage={} formatMarkdownMs={} rawModelChars={} markdownChars={}",
                                stage,
                                formatMarkdownMs,
                                raw.length(),
                                md == null ? 0 : md.length());
                        return new AgentReplyChunk("agent_chunk", md);
                    }))
                    .onErrorResume(e -> Flux.fromIterable(List.of(
                                    new AgentReplyChunk("agent_error", formatInvokeFailureMessage(e)),
                                    new AgentReplyChunk("agent_chunk", formatCalibrationJson(FALLBACK_JSON))
                            ))
                            .delayElements(Duration.ofMillis(80)));
        }

        return invokeService.invokeDefaultLlm(CALIBRATION_PROMPT, userPayload, stage)
                .map(raw -> {
                    long tf = System.currentTimeMillis();
                    String md = formatCalibrationJson(raw);
                    long formatMarkdownMs = System.currentTimeMillis() - tf;
                    log.info(
                            "main-calibrate formatMarkdown stage={} formatMarkdownMs={} rawModelChars={} markdownChars={}",
                            stage,
                            formatMarkdownMs,
                            raw == null ? 0 : raw.length(),
                            md == null ? 0 : md.length());
                    return md;
                })
                .flatMapMany(text -> Flux.just(new AgentReplyChunk("agent_chunk", text)))
                .onErrorResume(e -> Flux.fromIterable(List.of(
                                new AgentReplyChunk("agent_error", formatInvokeFailureMessage(e)),
                                new AgentReplyChunk("agent_chunk", formatCalibrationJson(FALLBACK_JSON))
                        ))
                        .delayElements(Duration.ofMillis(80)));
    }

    private static String formatInvokeFailureMessage(Throwable e) {
        if (isLikelyAsyncTimeout(e)) {
            return "调用失败: 大模型在配置的超时时间内未返回完整结果（追问带长摘录时更慢）。"
                    + "请在部署环境将 QUESTIONOS_LLM_TIMEOUT_SECONDS 调大（例如 300～420）并重启 java-backend；"
                    + "前端单次等待需略大于该秒数（见 chat/page.tsx 内超时）。";
        }
        String msg = e.getMessage();
        return "调用失败: " + (msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName());
    }

    private static boolean isLikelyAsyncTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && m.contains("Did not observe any item")) {
                return true;
            }
        }
        return false;
    }

    private static String buildDecisionPayload(List<ConversationMessage> history, String inputFallback) {
        if (history == null || history.isEmpty()) {
            return """
                    ### 对话进度
                    - 已有用户发言轮次（含本轮）约：1

                    ### 对话摘录（旧→新）
                    （尚无历史）

                    ### 本轮最新用户输入
                    """ + truncate(inputFallback == null ? "" : inputFallback, USER_TRANSCRIPT_MAX);
        }
        List<ConversationMessage> slice = history.size() > TRANSCRIPT_MAX_MESSAGES
                ? history.subList(history.size() - TRANSCRIPT_MAX_MESSAGES, history.size())
                : history;
        // 仍超长时从最早一轮开始丢弃，避免追问时整包过大触发模型/网关失败
        String transcript;
        boolean trimmedHead = false;
        while (true) {
            transcript = renderTranscriptBlock(slice);
            if (transcript.length() <= PAYLOAD_CHAR_BUDGET || slice.size() <= 2) {
                break;
            }
            slice = slice.subList(2, slice.size());
            trimmedHead = true;
        }
        long userTurns = slice.stream().filter(m -> m.role() == MessageRole.USER).count();
        StringBuilder sb = new StringBuilder();
        sb.append("### 对话进度\n");
        sb.append("- 摘录内用户发言条数约：").append(userTurns).append("\n");
        if (trimmedHead) {
            sb.append("- （更早若干轮已从摘录中省略，请以当前摘录为准）\n");
        }
        sb.append("\n");
        sb.append(transcript);
        return sb.toString();
    }

    private static String renderTranscriptBlock(List<ConversationMessage> slice) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 对话摘录（旧→新）\n");
        for (ConversationMessage m : slice) {
            String roleLabel = m.role() == MessageRole.USER ? "用户" : "助手";
            int lim = m.role() == MessageRole.USER ? USER_TRANSCRIPT_MAX : ASSISTANT_TRANSCRIPT_MAX;
            String body = truncate(m.content() == null ? "" : m.content(), lim).replace("\r\n", "\n");
            sb.append("[").append(roleLabel).append("]\n").append(body).append("\n\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "\n…(已截断)";
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

            boolean decision = isDecisionShape(root);
            JsonNode questions = root.get("questions");

            if (decision) {
                appendDecisionCalibrationMarkdown(sb, root, questions);
            } else if (questions != null && questions.isArray() && questions.size() > 0) {
                appendLegacyQuestionsMarkdown(sb, questions);
            }

            String out = sb.toString().trim();
            return out.isEmpty() ? raw : out;
        } catch (Exception ignored) {
            return raw;
        }
    }

    /**
     * Decision 模式展示顺序：本轮追问置顶突出 → 阶段 → 理解确认（含回放/澄清词）→ 追问理由 → 建议探索方向。
     * 不展示：认知偏差、清晰度变化。
     */
    private static void appendDecisionCalibrationMarkdown(StringBuilder sb, JsonNode root, JsonNode questions) {
        String phaseRaw = textOrEmpty(root.get("phase"));
        String echo = textOrEmpty(root.get("scenario_echo"));
        String fuzzy = textOrEmpty(root.get("fuzzy_focus"));
        String mirror = textOrEmpty(root.get("user_conclusion_mirror"));
        String reasoning = root.hasNonNull("reasoning") ? root.get("reasoning").asText().trim() : "";
        String suggested = root.hasNonNull("suggested_direction") ? root.get("suggested_direction").asText().trim() : "";

        String firstQuestion = "";
        if (questions != null && questions.isArray()) {
            for (int i = 0; i < questions.size(); i++) {
                String q = textOrEmpty(questions.get(i));
                if (!q.isEmpty()) {
                    firstQuestion = q;
                    break;
                }
            }
        }

        if (!firstQuestion.isEmpty()) {
            sb.append("---\n\n");
            sb.append("## 本轮追问\n\n");
            appendBlockquotedParagraph(sb, firstQuestion);
            sb.append("\n---\n\n");
        }

        sb.append("### 阶段\n\n");
        sb.append("*").append(phaseLabel(phaseRaw)).append("*");
        if ("polanyi".equalsIgnoreCase(phaseRaw)) {
            String ps = textOrEmpty(root.get("polanyi_strategy"));
            if (!ps.isEmpty()) {
                sb.append("  \n*（默会策略：").append(polanyiLabel(ps)).append("）*");
            }
        }
        sb.append("\n\n");

        boolean hasUnderstand = !echo.isEmpty() || !fuzzy.isEmpty() || !mirror.isEmpty();
        if (hasUnderstand) {
            sb.append("### 理解确认\n\n");
            if (!echo.isEmpty()) {
                sb.append(echo).append("\n\n");
            }
            if (!fuzzy.isEmpty()) {
                sb.append("*正在澄清的词：* ").append(fuzzy).append("\n\n");
            }
            if (!mirror.isEmpty()) {
                sb.append("*你的结论（回放）：*\n\n").append(mirror).append("\n\n");
            }
        }

        if (!reasoning.isEmpty()) {
            sb.append("### 追问理由\n\n").append(reasoning).append("\n\n");
        }

        if (!suggested.isEmpty()) {
            sb.append("### 建议探索方向\n\n").append(suggested).append("\n");
        }
    }

    /** 多行内容用引用块逐行展示，避免 ** 跨行断掉 */
    private static void appendBlockquotedParagraph(StringBuilder sb, String text) {
        String normalized = text.replace("\r\n", "\n").trim();
        for (String line : normalized.split("\n", -1)) {
            if (line.isEmpty()) {
                sb.append(">\n");
            } else {
                sb.append("> **").append(line).append("**\n");
            }
        }
    }

    private static void appendLegacyQuestionsMarkdown(StringBuilder sb, JsonNode questions) {
        sb.append("---\n\n");
        sb.append("## 本轮追问\n\n");
        int n = 0;
        for (int i = 0; i < questions.size(); i++) {
            String q = textOrEmpty(questions.get(i));
            if (q.isEmpty()) {
                continue;
            }
            n++;
            if (n > 1) {
                sb.append("\n");
            }
            appendBlockquotedParagraph(sb, q);
        }
        sb.append("\n---\n");
    }

    private static boolean isDecisionShape(JsonNode root) {
        if (root == null) {
            return false;
        }
        if (root.hasNonNull("phase")) {
            return true;
        }
        String m = textOrEmpty(root.get("calibration_mode"));
        return "decision".equalsIgnoreCase(m);
    }

    private static String phaseLabel(String phase) {
        if (phase == null || phase.isBlank()) {
            return "深度追问";
        }
        return switch (phase.trim().toLowerCase()) {
            case "scenario_confirm" -> "场景确认";
            case "language_clarify" -> "语言澄清";
            case "socratic" -> "深度追问";
            case "polanyi" -> "默会兜底";
            case "synthesis" -> "结论回放";
            case "action_anchor" -> "下一步";
            default -> phase;
        };
    }

    private static String polanyiLabel(String strategy) {
        return switch (strategy.trim().toLowerCase()) {
            case "negative" -> "反面/否定偏好";
            case "exemplar" -> "他人故事镜像";
            case "past_behavior" -> "过去选择模式";
            default -> strategy;
        };
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
