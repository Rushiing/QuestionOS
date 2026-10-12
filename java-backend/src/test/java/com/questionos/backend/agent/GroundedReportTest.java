package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroundedReportTest {
    private final List<ConversationMessage> history = List.of(
            new ConversationMessage("u1", "s", 1, MessageRole.USER, "预算六万元，修复至少十天。", Instant.EPOCH, null),
            new ConversationMessage("u2", "s", 2, MessageRole.USER, "只做可退款原型预售，不承诺交付日。", Instant.EPOCH, null),
            new ConversationMessage("a", "s", 1, MessageRole.AGENT, "账面现金六万元", Instant.EPOCH, "auditor"));

    private String reply(String id, String effect) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("source_ids", List.of(id), "unknowns", List.of("现金余额与实际支出未知"),
                "judgments", List.of(Map.of("condition", "原型展示不占用研发", "consequence", effect)),
                "plan", List.of(Map.of("action", "核实人员占用", "owner", "负责人", "check", "工作量清单", "stop", "影响核心修复则暂停预售验证"))));
    }

    @Test
    void coreEvidenceAndCorrectionAreQuotedWithoutInventingCashBalance() throws Exception {
        String text = GroundedReport.render(reply("u2", "可考虑并行"), history);
        assertTrue(text.contains("> 预算六万元，修复至少十天。"));
        assertTrue(text.contains("> 只做可退款原型预售，不承诺交付日。"));
        assertFalse(text.contains("账面现金六万元"));
        assertTrue(text.contains("建议负责人"));
    }

    @Test
    void omittedUnverifiedJudgmentLeavesOriginalEvidenceAndSuggestedPlan() throws Exception {
        String text = GroundedReport.render(reply("u2", "十天内完成"), history, true);
        assertFalse(text.contains("十天内完成"));
        assertTrue(text.contains("核对提示"));
        assertTrue(text.contains("至少十天"));
        assertTrue(text.contains("核实人员占用"));
    }

    @Test
    void rejectsModelEvidenceMissingFieldsAndReversedBound() throws Exception {
        for (String bad : List.of(reply("a", "可并行"), reply("missing", "可并行"), reply("u2", "十天内完成"), "{}", "null")) {
            assertThrows(IllegalArgumentException.class, () -> GroundedReport.render(bad, history));
        }
    }
}
