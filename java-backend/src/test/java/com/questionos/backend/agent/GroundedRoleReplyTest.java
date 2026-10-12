package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.integrations.OpenClawInvokeService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroundedRoleReplyTest {
    private final List<ConversationMessage> history = List.of(
            new ConversationMessage("u", "s", 1, MessageRole.USER, "假设客户付款不足开发成本，修复至少十天。", Instant.EPOCH, null),
            new ConversationMessage("a", "s", 1, MessageRole.AGENT, "销售不占研发", Instant.EPOCH, "auditor"));

    private String reply(String source, String consequence) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("source_id", source,
                "uncertainty", "销售是否占用研发尚未明确", "condition", "销售可独立完成验证且不影响修复",
                "consequence", consequence, "proposal", "先核实任务所需人员再决定是否并行", "question", "销售验证需要研发参与吗？"));
    }

    @Test
    void quotesOriginalHypothesisWithoutLettingModelRewriteIt() throws Exception {
        String rendered = GroundedRoleReply.render(reply("u", "并行验证可能可行"), history);
        assertTrue(rendered.contains("> 假设客户付款不足开发成本，修复至少十天。"));
        assertTrue(rendered.contains("条件推演（未验证）"));
        assertFalse(rendered.contains("销售不占研发"));
        assertTrue(GroundedRoleReply.render(reply("u", "修复至少十天的估计准确时，需先核对日历口径"), history).contains("修复至少十天的估计"));
    }

    @Test
    void rejectsInventedOrAgentSourceAndUnsupportedQuantities() throws Exception {
        for (String id : List.of("missing", "a")) {
            String raw = reply(id, "并行可能可行");
            assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(raw, history));
        }
        for (String claim : List.of("剩余四天", "十天内完成", "最多十天", "损失2000元", "两周流失一名客户", "比例２０％", "现金还能撑三个月", "损失百分之二十", "剩余半周", "流失三分之一")) {
            String raw = reply("u", claim);
            assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(raw, history), claim);
        }
        for (String malformed : List.of("不是JSON", "{}", "null", "[]")) {
            assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(malformed, history));
        }
    }

    @Test
    void quantityMatchingDoesNotAcceptSubstringOfAnotherAmount() throws Exception {
        var source = List.of(new ConversationMessage("u", "s", 1, MessageRole.USER, "预算20000元", Instant.EPOCH, null));
        String raw = reply("u", "损失2000元");
        assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(raw, source));
    }

    @Test
    void reportsAllUnsupportedQuantitiesForTheSingleRepairAttempt() throws Exception {
        String raw = reply("u", "剩余四天，损失2000元");
        var error = assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(raw, history));
        assertTrue(error.getMessage().contains("四天"));
        assertTrue(error.getMessage().contains("2000元"));
    }

    @Test
    void keepsOnlyFirstQuestionWithoutRejectingTheUsefulReply() {
        assertEquals("是否核算过支出？", GroundedRoleReply.firstQuestion("是否核算过支出？新工作节奏确认了吗？"));
    }

    @Test
    void preservesLowerBoundEvenWhenNeedVerbSeparatesItFromDuration() throws Exception {
        var source = List.of(new ConversationMessage("u", "s", 1, MessageRole.USER, "修复至少需要十天", Instant.EPOCH, null));
        String invalid = reply("u", "十天内完成");
        assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(invalid, source));
        assertTrue(GroundedRoleReply.render(reply("u", "修复至少十天"), source).contains("修复至少十天"));
    }

    @Test
    void afterRepairCanOmitOnlyUnsupportedJudgmentWithoutPublishingItsNumbers() throws Exception {
        String text = GroundedRoleReply.render(reply("u", "剩余四天，损失2000元"), history, true);
        assertFalse(text.contains("四天"));
        assertFalse(text.contains("2000元"));
        assertTrue(text.contains("核对提示"));
        assertTrue(text.contains("先核实任务所需人员"));
        String badSource = reply("a", "可并行");
        assertThrows(IllegalArgumentException.class, () -> GroundedRoleReply.render(badSource, history, true));
    }

    @Test
    void retriesInvalidOutputOnceAndNeverPublishesIt() throws Exception {
        var llm = mock(OpenClawInvokeService.class);
        when(llm.invokeDefaultLlm(anyString(), anyString(), eq("sandbox:grounded-role")))
                .thenReturn(Mono.just(reply("u", "剩余四天")), Mono.just(reply("u", "并行验证可能可行")));
        var chunks = new AgentOrchestrator(null, llm, null)
                .runPipeline("s", 2, "", SessionMode.SANDBOX, history, 0, "BUSINESS").collectList().block();
        assertNotNull(chunks);
        assertFalse(chunks.stream().anyMatch(c -> c.content().contains("剩余四天")));
        assertTrue(chunks.stream().anyMatch(c -> c.content().contains("并行验证可能可行")));
        verify(llm, times(2)).invokeDefaultLlm(anyString(), anyString(), eq("sandbox:grounded-role"));
    }

    @Test
    void repeatedInvalidOutputFailsClosedWithoutAnUnboundedLoop() {
        var llm = mock(OpenClawInvokeService.class);
        when(llm.invokeDefaultLlm(anyString(), anyString(), eq("sandbox:grounded-role"))).thenReturn(Mono.just("{}"));
        var chunks = new AgentOrchestrator(null, llm, null)
                .runPipeline("s", 2, "", SessionMode.SANDBOX, history, 0, "BUSINESS").collectList().block();
        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(c -> c.eventType().equals("agent_error")));
        verify(llm, times(2)).invokeDefaultLlm(anyString(), anyString(), eq("sandbox:grounded-role"));
    }
}
