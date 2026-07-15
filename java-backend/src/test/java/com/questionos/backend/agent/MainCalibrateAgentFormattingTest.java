package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCalibrateAgentFormattingTest {

    private final MainCalibrateAgent agent = new MainCalibrateAgent(null, new ObjectMapper());

    @Test
    void synthesisJsonRendersAStableUserConclusionSection() {
        String markdown = agent.formatCalibrationJson("""
                {
                  "calibration_mode": "decision",
                  "phase": "synthesis",
                  "questions": ["这是你当下的真实判断吗？"],
                  "scenario_echo": "你在稳定与速度之间取舍。",
                  "user_conclusion_mirror": "你愿意先做可回滚的小实验。",
                  "reasoning": "确认结论是否来自用户。",
                  "suggested_direction": ""
                }
                """);

        assertTrue(markdown.contains("结论回放"));
        assertTrue(markdown.contains("你的结论（回放）"));
        assertTrue(markdown.contains("这是你当下的真实判断吗？"));
    }

    @Test
    void malformedModelOutputFallsBackWithoutInventingContent() {
        assertEquals("不是 JSON", agent.formatCalibrationJson("不是 JSON"));
    }
}
