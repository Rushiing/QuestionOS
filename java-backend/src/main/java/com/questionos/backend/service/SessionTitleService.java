package com.questionos.backend.service;

import com.questionos.backend.integrations.OpenClawInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 根据用户首问生成会话列表展示标题（短中文）。
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    private static final String SYSTEM_PROMPT = """
            你是标题生成器。用户会发来一段问题或任务描述。
            请用不超过 20 个汉字生成一条会话标题，概括主题；不要引号、不要换行、不要前缀说明。
            若内容为空或无法理解，输出「新会话」。
            """;

    private final OpenClawInvokeService openClawInvokeService;

    public SessionTitleService(OpenClawInvokeService openClawInvokeService) {
        this.openClawInvokeService = openClawInvokeService;
    }

    /**
     * 未调用 LLM 前的列表占位：问题截断。
     */
    public String fallbackTitle(String userQuestion) {
        String t = truncate(stripOneLine(userQuestion), 36);
        return t.isEmpty() ? "新会话" : t;
    }

    /**
     * 生成展示标题；LLM 不可用时回退为问题截断。
     */
    public String summarizeTitle(String userQuestion) {
        String fallback = fallbackTitle(userQuestion);
        try {
            String raw = openClawInvokeService.invokeDefaultLlm(SYSTEM_PROMPT, userQuestion == null ? "" : userQuestion)
                    .block(Duration.ofSeconds(30));
            String cleaned = sanitizeTitle(raw);
            if (!cleaned.isEmpty()) {
                return truncate(cleaned, 40);
            }
        } catch (Exception e) {
            log.debug("session title LLM failed, using fallback: {}", e.getMessage());
        }
        return fallback;
    }

    private static String stripOneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String sanitizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replace('\r', ' ').replace('\n', ' ').trim();
        t = t.replace("\"", "").replace("「", "").replace("」", "").replace("《", "").replace("》", "");
        return t.strip();
    }

    private static String truncate(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }
}
