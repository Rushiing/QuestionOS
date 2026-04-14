package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 对沙盘「用户核心议题」做一次 LLM 分类；失败时用关键词兜底，结果为 {@link SandboxDeliberationScene#name()}。
 */
@Component
public class SandboxSceneClassifier {
    private static final Logger log = LoggerFactory.getLogger(SandboxSceneClassifier.class);
    private static final int ISSUE_MAX = 2400;

    private static final String CLASSIFY_SYSTEM = """
            你是「Agora 审议室分诊员」。
            任务：先判断用户议题是否可分类；若可分类，给出最匹配审议室。
            只输出一行合法 JSON，不要代码块、不要解释，格式严格为：
            {"scene":"GENERAL","confidence":"LOW","normalizedIssue":"..."}

            约束：
            - scene 取值必须是：BUSINESS, ENGINEERING, LIFE_CROSSROADS, RELATIONSHIP, PSYCHOLOGY, CREATIVE, GENERAL
            - confidence 取值必须是：HIGH 或 LOW
            - normalizedIssue 用一句中文重述「用户到底在决策什么」（<=60字）

            含义：
            - BUSINESS：商业、市场、定价、战略、融资、创业、竞品、营收、增长、组织管理中的商业决策
            - ENGINEERING：技术、代码、架构、系统、工程、性能、部署、技术债、测试、API、数据库
            - LIFE_CROSSROADS：人生重大选择、转行、辞职、意义、方向、迷茫、后悔、值不值得
            - RELATIONSHIP：伴侣、家庭、亲子、朋友、职场人际、沟通、冲突、边界、分手、婚姻
            - PSYCHOLOGY：焦虑、拖延、倦怠、习惯、自律、压力、恢复、动力、心理、情绪
            - CREATIVE：写作、创作、灵感、卡壳、内容、读者、表达、艺术、风格、原创
            - GENERAL：只有在信息极少、无明确决策对象时才允许

            跨域优先级：
            - 若出现「辞职创业」等跨域，优先人生/关系/心理主轴，而非商业/技术表层。
            """;

    private static final String FORCE_ROOM_SYSTEM = """
            你是「Agora 强制入室路由器」。
            任务：必须在六个审议室里选一个最合适主轴，禁止输出 GENERAL。
            只输出一行合法 JSON，不要解释：
            {"scene":"BUSINESS","confidence":"LOW"}

            scene 只能是：
            BUSINESS, ENGINEERING, LIFE_CROSSROADS, RELATIONSHIP, PSYCHOLOGY, CREATIVE

            若信息不足，也要按「当前最可讨论的主轴」做暂定入室。
            若跨域，优先生命/关系/心理主轴，再考虑商业/技术。
            """;

    private final OpenClawInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public SandboxSceneClassifier(OpenClawInvokeService invokeService, ObjectMapper objectMapper) {
        this.invokeService = invokeService;
        this.objectMapper = objectMapper;
    }

    public String classifyBlocking(String issuePlainText) {
        return classifyDetailed(issuePlainText).scene().name();
    }

    /**
     * 用于「步骤②前」的强制入室：返回结果保证不是 GENERAL。
     */
    public SandboxClassificationResult classifyDetailedNoGeneral(String issuePlainText) {
        SandboxClassificationResult first = classifyDetailed(issuePlainText);
        if (first.scene() != SandboxDeliberationScene.GENERAL) {
            return first;
        }
        String trimmed = issuePlainText == null ? "" : issuePlainText.trim();
        String snippet = trimmed.length() <= ISSUE_MAX ? trimmed : trimmed.substring(0, ISSUE_MAX) + "\n…（已截断）";
        SandboxDeliberationScene forced = forceRoom(snippet, "{\"scene\":\"GENERAL\",\"confidence\":\"LOW\"}");
        if (forced != null && forced != SandboxDeliberationScene.GENERAL) {
            return new SandboxClassificationResult(forced, first.normalizedIssue(), "LOW", true);
        }
        SandboxDeliberationScene fb = keywordFallback(trimmed);
        if (fb == SandboxDeliberationScene.GENERAL) {
            fb = SandboxDeliberationScene.BUSINESS;
        }
        return new SandboxClassificationResult(fb, first.normalizedIssue(), "LOW", true);
    }

    /**
     * 完整分诊结果（步骤 ① 卡片）：含归一化议题句、信心与是否触发二次强制入室。
     */
    public SandboxClassificationResult classifyDetailed(String issuePlainText) {
        String trimmed = issuePlainText == null ? "" : issuePlainText.trim();
        if (trimmed.isEmpty()) {
            return new SandboxClassificationResult(SandboxDeliberationScene.GENERAL, "", "LOW", false);
        }
        String snippet = trimmed.length() <= ISSUE_MAX ? trimmed : trimmed.substring(0, ISSUE_MAX) + "\n…（已截断）";
        try {
            String raw = invokeService
                    .invokeDefaultLlmCompact(
                            CLASSIFY_SYSTEM,
                            "用户议题如下（可能含中英文）：\n\n" + snippet,
                            "sandbox:scene-classify",
                            180,
                            20)
                    .block(Duration.ofSeconds(22));
            JsonNode root = parseJsonObject(raw);
            SandboxDeliberationScene fromLlm = null;
            if (root != null && root.hasNonNull("scene")) {
                fromLlm = SandboxDeliberationScene.parseStored(root.get("scene").asText());
            }
            if (fromLlm == null) {
                fromLlm = parseSceneJson(raw);
            }
            if (fromLlm != null) {
                String norm = extractNormalizedIssue(root, trimmed);
                String conf = extractConfidence(root, raw);
                boolean needForceRoom = fromLlm == SandboxDeliberationScene.GENERAL || isLowConfidence(raw);
                if (!needForceRoom) {
                    log.info("sandbox scene classified by llm scene={} confidence={}", fromLlm, conf);
                    return new SandboxClassificationResult(fromLlm, norm, conf, false);
                }
                SandboxDeliberationScene forced = forceRoom(snippet, raw);
                if (forced != null) {
                    log.info("sandbox scene forced into room scene={} from={}", forced, fromLlm);
                    return new SandboxClassificationResult(forced, norm, "LOW", true);
                }
                log.info("sandbox scene classified by llm scene={} confidence=LOW(no-force-result)", fromLlm);
                return new SandboxClassificationResult(fromLlm, norm, "LOW", false);
            }
        } catch (Exception e) {
            log.warn("sandbox scene llm classify failed: {}", e.toString());
        }
        SandboxDeliberationScene fb = keywordFallback(trimmed);
        log.info("sandbox scene fallback keyword scene={}", fb);
        return new SandboxClassificationResult(fb, extractNormalizedIssue(null, trimmed), "LOW", false);
    }

    private JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int brace = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (brace >= 0 && end > brace) {
            s = s.substring(brace, end + 1);
        }
        try {
            return objectMapper.readTree(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractNormalizedIssue(JsonNode root, String fallbackIssue) {
        if (root != null && root.hasNonNull("normalizedIssue")) {
            String n = root.get("normalizedIssue").asText(null);
            if (n != null) {
                n = n.trim().replaceAll("\\s+", " ");
                if (!n.isEmpty()) {
                    return truncateForCard(n, 96);
                }
            }
        }
        return truncateForCard(fallbackIssue == null ? "" : fallbackIssue.trim(), 160);
    }

    private String extractConfidence(JsonNode root, String raw) {
        if (root != null && root.hasNonNull("confidence")) {
            String c = root.get("confidence").asText("").trim().toUpperCase(Locale.ROOT);
            if ("HIGH".equals(c) || "LOW".equals(c)) {
                return c;
            }
        }
        return isLowConfidence(raw) ? "LOW" : "HIGH";
    }

    private static String truncateForCard(String t, int maxChars) {
        if (t == null || t.isEmpty()) {
            return "";
        }
        String x = t.replaceAll("\\s+", " ").trim();
        if (x.length() <= maxChars) {
            return x;
        }
        return x.substring(0, maxChars) + "…";
    }

    private SandboxDeliberationScene parseSceneJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int brace = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (brace >= 0 && end > brace) {
            s = s.substring(brace, end + 1);
        }
        try {
            JsonNode root = objectMapper.readTree(s);
            if (root.hasNonNull("scene")) {
                return SandboxDeliberationScene.parseStored(root.get("scene").asText());
            }
        } catch (Exception ignored) {
            // try substring match
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        for (SandboxDeliberationScene v : SandboxDeliberationScene.values()) {
            if (upper.contains("\"" + v.name() + "\"") || upper.contains(v.name())) {
                return v;
            }
        }
        return null;
    }

    private boolean isLowConfidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        return upper.contains("\"CONFIDENCE\":\"LOW\"") || upper.contains("\"CONFIDENCE\": \"LOW\"");
    }

    private SandboxDeliberationScene forceRoom(String snippet, String firstPassRaw) {
        try {
            String raw = invokeService
                    .invokeDefaultLlmCompact(
                            FORCE_ROOM_SYSTEM,
                            "原始用户议题：\n" + snippet + "\n\n初次分诊结果：\n" + String.valueOf(firstPassRaw),
                            "sandbox:scene-force-room",
                            72,
                            20)
                    .block(Duration.ofSeconds(18));
            SandboxDeliberationScene v = parseSceneJson(raw);
            if (v != null && v != SandboxDeliberationScene.GENERAL) {
                return v;
            }
        } catch (Exception e) {
            log.warn("sandbox scene force-room failed: {}", e.toString());
        }
        SandboxDeliberationScene fb = keywordFallback(snippet);
        return fb == SandboxDeliberationScene.GENERAL ? null : fb;
    }

    /** Agora 式信号词的轻量兜底（仅当 LLM 不可用时）。 */
    static SandboxDeliberationScene keywordFallback(String t) {
        String x = t.toLowerCase(Locale.ROOT);
        int[] score = new int[SandboxDeliberationScene.values().length];
        bump(score, SandboxDeliberationScene.ENGINEERING, x,
                "代码", "架构", "api", "数据库", "微服务", "部署", "性能", "重构", "bug", "技术债", "测试", "framework");
        bump(score, SandboxDeliberationScene.BUSINESS, x,
                "市场", "定价", "竞争", "融资", "增长", "商业模式", "客户", "营收", "创业", "战略", "投资", "竞品", "pmf",
                "产品", "用户反馈", "口碑", "留存", "转化", "复购", "上线");
        bump(score, SandboxDeliberationScene.LIFE_CROSSROADS, x,
                "要不要", "辞职", "转行", "人生", "意义", "方向", "迷茫", "后悔", "离开", "留下", "值不值得", "中年");
        bump(score, SandboxDeliberationScene.RELATIONSHIP, x,
                "关系", "伴侣", "孩子", "父母", "家庭", "朋友", "同事", "沟通", "分手", "婚姻", "冲突", "边界");
        bump(score, SandboxDeliberationScene.PSYCHOLOGY, x,
                "焦虑", "拖延", "燃尽", "burnout", "习惯", "自律", "压力", "恢复", "心理", "情绪", "动力", "抑郁", "失眠");
        bump(score, SandboxDeliberationScene.CREATIVE, x,
                "写作", "创作", "灵感", "卡壳", "内容", "读者", "表达", "艺术", "风格", "原创");
        int best = -1;
        int idx = 0;
        for (int i = 0; i < score.length; i++) {
            if (score[i] > best) {
                best = score[i];
                idx = i;
            }
        }
        if (best <= 0) {
            return SandboxDeliberationScene.GENERAL;
        }
        // 人生 × 商业 常见：辞职创业 —— 与 Agora 一致偏向 LIFE
        if (score[SandboxDeliberationScene.LIFE_CROSSROADS.ordinal()] > 0
                && score[SandboxDeliberationScene.BUSINESS.ordinal()] > 0
                && score[SandboxDeliberationScene.LIFE_CROSSROADS.ordinal()] >= score[SandboxDeliberationScene.BUSINESS.ordinal()]) {
            return SandboxDeliberationScene.LIFE_CROSSROADS;
        }
        return SandboxDeliberationScene.values()[idx];
    }

    private static void bump(int[] score, SandboxDeliberationScene scene, String hay, String... needles) {
        for (String n : needles) {
            if (n != null && hay.contains(n.toLowerCase(Locale.ROOT))) {
                score[scene.ordinal()]++;
            }
        }
    }
}
