package com.questionos.backend.integrations;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceReviewThinkingTest {
    @Test
    void groundedRoleRequestsJsonWithoutChangingOtherStages() {
        Map<String, Object> body = new HashMap<>(Map.of("enable_thinking", false));
        OpenClawInvokeService.applySandboxRequestOptions(body, "sandbox:grounded-role", "deepseek-v4-flash");
        assertEquals(Map.of("type", "json_object"), body.get("response_format"));
        assertEquals(true, body.get("enable_thinking"));
        Map<String, Object> report = new HashMap<>();
        OpenClawInvokeService.applySandboxRequestOptions(report, "sandbox:grounded-report", "deepseek-v4-flash");
        assertEquals(Map.of("type", "json_object"), report.get("response_format"));
        Map<String, Object> ordinary = new HashMap<>();
        OpenClawInvokeService.applySandboxRequestOptions(ordinary, "calibration", "deepseek-v4-flash");
        assertTrue(ordinary.isEmpty());
    }

    @Test
    void onlySupportedReviewUsesBoundedThinking() {
        Map<String, Object> body = new HashMap<>(Map.of("enable_thinking", false));
        OpenClawInvokeService.applySandboxRequestOptions(body, "llm", "deepseek-v4-flash");
        assertEquals(false, body.get("enable_thinking"));
        OpenClawInvokeService.applySandboxRequestOptions(body, "sandbox:evidence-review", "other-model");
        assertFalse(body.containsKey("thinking_budget"));
        OpenClawInvokeService.applySandboxRequestOptions(body, "sandbox:evidence-review", "deepseek-v4-flash");
        assertEquals(true, body.get("enable_thinking"));
        assertEquals(4096, body.get("thinking_budget"));
        Map<String, Object> deliberation = new HashMap<>(Map.of("enable_thinking", false));
        OpenClawInvokeService.applySandboxRequestOptions(deliberation, "sandbox:deliberation", "deepseek-v4-flash");
        assertEquals(true, deliberation.get("enable_thinking"));
        assertEquals(4096, deliberation.get("thinking_budget"));
        Map<String, Object> otherProvider = new HashMap<>();
        OpenClawInvokeService.applySandboxRequestOptions(otherProvider, "sandbox:evidence-review", "deepseek-v4-flash");
        assertTrue(otherProvider.isEmpty());
    }
}
