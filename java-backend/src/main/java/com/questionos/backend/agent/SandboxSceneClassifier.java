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
            你是路由模型。只输出一行合法 JSON，不要代码块、不要解释。格式严格为：
            {"scene":"GENERAL"}
            其中 scene 取值必须是以下之一（全大写英文）：
            BUSINESS, ENGINEERING, LIFE_CROSSROADS, RELATIONSHIP, PSYCHOLOGY, CREATIVE, GENERAL

            含义：
            - BUSINESS：商业、市场、定价、战略、融资、创业、竞品、营收、增长、组织管理中的商业决策
            - ENGINEERING：技术、代码、架构、系统、工程、性能、部署、技术债、测试、API、数据库
            - LIFE_CROSSROADS：人生重大选择、转行、辞职、意义、方向、迷茫、后悔、值不值得
            - RELATIONSHIP：伴侣、家庭、亲子、朋友、职场人际、沟通、冲突、边界、分手、婚姻
            - PSYCHOLOGY：焦虑、拖延、倦怠、习惯、自律、压力、恢复、动力、心理、情绪
            - CREATIVE：写作、创作、灵感、卡壳、内容、读者、表达、艺术、风格、原创
            - GENERAL：无法归类、信息过少、或多域纠缠且没有明显主轴

            若跨域（例如辞职创业），以「更底层的人生/关系/心理主轴」优先于纯商业或纯技术。
            """;

    private final OpenClawInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public SandboxSceneClassifier(OpenClawInvokeService invokeService, ObjectMapper objectMapper) {
        this.invokeService = invokeService;
        this.objectMapper = objectMapper;
    }

    public String classifyBlocking(String issuePlainText) {
        String trimmed = issuePlainText == null ? "" : issuePlainText.trim();
        if (trimmed.isEmpty()) {
            return SandboxDeliberationScene.GENERAL.name();
        }
        String snippet = trimmed.length() <= ISSUE_MAX ? trimmed : trimmed.substring(0, ISSUE_MAX) + "\n…（已截断）";
        try {
            String raw = invokeService
                    .invokeDefaultLlmCompact(
                            CLASSIFY_SYSTEM,
                            "用户议题如下（可能含中英文）：\n\n" + snippet,
                            "sandbox:scene-classify",
                            96,
                            20)
                    .block(Duration.ofSeconds(22));
            SandboxDeliberationScene fromLlm = parseSceneJson(raw);
            if (fromLlm != null) {
                log.info("sandbox scene classified by llm scene={}", fromLlm);
                return fromLlm.name();
            }
        } catch (Exception e) {
            log.warn("sandbox scene llm classify failed: {}", e.toString());
        }
        SandboxDeliberationScene fb = keywordFallback(trimmed);
        log.info("sandbox scene fallback keyword scene={}", fb);
        return fb.name();
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

    /** Agora 式信号词的轻量兜底（仅当 LLM 不可用时）。 */
    static SandboxDeliberationScene keywordFallback(String t) {
        String x = t.toLowerCase(Locale.ROOT);
        int[] score = new int[SandboxDeliberationScene.values().length];
        bump(score, SandboxDeliberationScene.ENGINEERING, x,
                "代码", "架构", "api", "数据库", "微服务", "部署", "性能", "重构", "bug", "技术债", "测试", "framework");
        bump(score, SandboxDeliberationScene.BUSINESS, x,
                "市场", "定价", "竞争", "融资", "增长", "商业模式", "客户", "营收", "创业", "战略", "投资", "竞品", "pmf");
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
