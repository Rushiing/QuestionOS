package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContractFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MainCalibrateAgent agent = new MainCalibrateAgent(null, objectMapper);

    @Test
    void calibrationPhaseFixturesKeepStableMarkdownContracts() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/calibration-contract-cases.json")) {
            JsonNode fixtures = objectMapper.readTree(input);
            assertEquals(5, fixtures.size());
            for (JsonNode fixture : fixtures) {
                String markdown = agent.formatCalibrationJson(objectMapper.writeValueAsString(fixture.get("payload")));
                assertTrue(markdown.contains("*" + fixture.get("expectedPhaseLabel").asText() + "*"), fixture.get("id").asText());
                assertTrue(markdown.contains(fixture.get("expectedQuestion").asText()), fixture.get("id").asText());
                if (fixture.has("expectedMirror")) {
                    assertTrue(markdown.contains("*你的结论（回放）：*"), fixture.get("id").asText());
                    assertTrue(markdown.contains(fixture.get("expectedMirror").asText()), fixture.get("id").asText());
                }
            }
        }
    }

    @Test
    void decisionFormatterShowsOnlyTheFirstNonBlankQuestion() {
        String markdown = agent.formatCalibrationJson("""
                {"calibration_mode":"decision","phase":"socratic","questions":["", "唯一展示的问题？", "不得展示的第二问？"]}
                """);

        assertTrue(markdown.contains("唯一展示的问题？"));
        assertFalse(markdown.contains("不得展示的第二问？"));
    }

    @Test
    void sandboxClarifyParserFailsClosedOnInvalidShapes() {
        assertEquals("", agent.formatSandboxStep1ClarifyLite("not-json"));
        assertEquals("", agent.formatSandboxStep1ClarifyLite("{\"questions\":[]}"));
        assertEquals("", agent.formatSandboxStep1ClarifyLite("{\"questions\":[\"\"]}"));
    }
}
