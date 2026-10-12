package com.questionos.backend.agent;

import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.questionos.backend.integrations.OpenClawInvokeService;
import com.questionos.backend.domain.SessionMode;
import reactor.core.publisher.Mono;

class IntegratorPromptContractTest {

    @Test
    void integratorPromptCarriesCoreIssueUserFactsAndPriorClaims() throws Exception {
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, null, null);
        List<ConversationMessage> prior = List.of(
                message("m1", 1, MessageRole.USER, "是否重构支付系统？", null),
                message("m2", 1, MessageRole.AGENT, "**先钉死停机预算。**\n❓ 你最多能接受停机多久？", "auditor"),
                message("m3", 2, MessageRole.USER, "最多五分钟。", null),
                message("m4", 2, MessageRole.AGENT, "**回滚必须先演练。**\n❓ 灰度失败后由谁决定回滚？", "risk_officer")
        );

        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "buildIntegratorUserMessage",
                List.class,
                String.class,
                SandboxDeliberationScene.class,
                String.class,
                AdaptiveDepthGate.ConsensusAssessment.class
        );
        method.setAccessible(true);
        String prompt = (String) method.invoke(
                orchestrator,
                prior,
                "我会先灰度一周。",
                SandboxDeliberationScene.ENGINEERING,
                "## 本轮审议阶段：综合裁决",
                null
        );

        assertTrue(prompt.contains("是否重构支付系统？"));
        assertTrue(prompt.contains("最多五分钟。"));
        assertTrue(prompt.contains("回应"));
        assertTrue(prompt.contains("先灰度一周"));
        assertTrue(prompt.contains("先钉死停机预算"));
        assertTrue(prompt.contains("回滚必须先演练"));
        assertTrue(prompt.contains("已提出过的追问"));
        assertTrue(prompt.contains("输出你的决策沙盘报告"));
    }

    @Test
    void newUserMessageIsNotAutomaticallyAnAnswerOrConfirmation() throws Exception {
        var prior = List.of(message("m1", 1, MessageRole.USER, "是否开发新功能？", null),
                message("m2", 1, MessageRole.AGENT, "如果销售完全独立，可以并行。\n❓ 你愿意押上全部预算吗？", "auditor"));
        var method = AgentOrchestrator.class.getDeclaredMethod("buildAttackerUserMessage",
                List.class, String.class, SandboxDeliberationScene.class, String.class);
        method.setAccessible(true);
        String input = (String) method.invoke(new AgentOrchestrator(null, null, null), prior,
                "补充信息：研发至少需要十天，请核对资源假设。", SandboxDeliberationScene.BUSINESS, "交叉审查");
        assertFalse(input.contains("这句话是在回答"));
        assertFalse(input.contains("前序观点速览"));
        assertTrue(input.contains("不能自动认定为回答或认可其前提"));
        assertTrue(input.contains("如果销售完全独立，可以并行。"));
        assertTrue(input.contains("研发至少需要十天"));
    }

    @Test
    void explicitInterimOverridesSecondLapAndDoesNotAskConsensusGate() {
        OpenClawInvokeService llm = mock(OpenClawInvokeService.class);
        AdaptiveDepthGate gate = mock(AdaptiveDepthGate.class);
        when(llm.invokeDefaultLlm(anyString(), anyString(), eq("sandbox:deliberation"))).thenReturn(Mono.just("中场小结"));
        when(llm.invokeDefaultLlm(anyString(), anyString(), eq("sandbox:evidence-review"))).thenReturn(Mono.just("中场小结"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, llm, gate);
        var history = List.of(
                message("m1", 1, MessageRole.USER, "预算尚未确定。", null),
                message("m2", 1, MessageRole.AGENT, "假设预算五万元。", "auditor"),
                message("m3", 2, MessageRole.USER, "先不下最终结论，请做中场小结。", null));
        orchestrator.runPipeline("fixture", 2, "", SessionMode.SANDBOX, history, 7, "BUSINESS")
                .collectList().block();
        verifyNoInteractions(gate);
        verify(llm).invokeDefaultLlm(argThat(s -> s.endsWith("本轮输出任务（程序确定）：中场小结")), anyString(), eq("sandbox:evidence-review"));
        verify(llm).invokeDefaultLlm(
                argThat(s -> s.contains("中场主持人") && s.contains("事实与计算规则")),
                argThat(s -> s.contains("不要输出终局报告") && s.contains("模型观点，未经用户确认")), eq("sandbox:deliberation"));
    }

    @Test
    void oldInterimRequestCannotChangeFinalStructuredReportTask() {
        OpenClawInvokeService llm = mock(OpenClawInvokeService.class);
        when(llm.invokeDefaultLlm(anyString(), anyString(), anyString())).thenReturn(Mono.just("{}"));
        var history = List.of(message("m1", 1, MessageRole.USER, "先做中场小结", null),
                message("m2", 1, MessageRole.AGENT, "小结已完成", "integrator"),
                message("m3", 2, MessageRole.USER, "请给出最终报告", null));
        new AgentOrchestrator(null, llm, null).runPipeline("fixture", 2, "", SessionMode.SANDBOX,
                history, 7, "BUSINESS").collectList().block();
        verify(llm, times(2)).invokeDefaultLlm(eq(GroundedReport.PROMPT), anyString(), eq("sandbox:grounded-report"));
        verify(llm, never()).invokeDefaultLlm(anyString(), anyString(), eq("sandbox:evidence-review"));
    }

    @Test
    void evidenceReviewReceivesUserStatementsWithoutAgentHistory() {
        String input = AgentOrchestrator.evidenceReviewInput(List.of(
                message("m1", 1, MessageRole.USER, "只有预算，没有余额数据", null),
                message("m2", 1, MessageRole.AGENT, "模型编造余额五万元", "auditor"),
                message("m3", 2, MessageRole.USER, "如果有预付款再考虑", null)), "待审草稿");
        assertTrue(input.contains("只有预算，没有余额数据"));
        assertTrue(input.contains("如果有预付款再考虑"));
        assertFalse(input.contains("模型编造余额五万元"));
        assertTrue(input.contains("待审草稿"));
        assertTrue(input.endsWith("如果有预付款再考虑"));
    }

    @Test
    void failedReviewDoesNotPublishUncheckedDraft() {
        OpenClawInvokeService llm = mock(OpenClawInvokeService.class);
        when(llm.invokeDefaultLlm(anyString(), anyString(), eq("sandbox:deliberation"))).thenReturn(Mono.just("未经校对的错误数字"));
        when(llm.invokeDefaultLlm(anyString(), anyString(), eq("sandbox:evidence-review")))
                .thenReturn(Mono.error(new IllegalStateException("review unavailable")));
        var chunks = new AgentOrchestrator(null, llm, null).runPipeline("fixture", 1, "", SessionMode.SANDBOX,
                List.of(message("m1", 1, MessageRole.USER, "预算未知，先做中场小结", null)), 3, "BUSINESS")
                .collectList().block();
        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(c -> c.eventType().equals("agent_error")));
        assertFalse(chunks.stream().anyMatch(c -> c.content().contains("未经校对的错误数字")));
    }

    @Test
    void negativeCloseoutIsNotPositiveIntent() {
        for (String text : List.of("不要给出结论", "暂不出最终报告", "先别收束", "先不下最终结论，请做中场小结")) {
            assertTrue(AgentOrchestrator.userRequestedInterim(text), text);
            assertFalse(AgentOrchestrator.userRequestedCloseout(text), text);
        }
        assertTrue(AgentOrchestrator.userRequestedCloseout("请收束并给出最终报告"));
        assertFalse(AgentOrchestrator.userRequestedInterim("我不要换工作，请给出最终报告"));
        assertTrue(AgentOrchestrator.userRequestedInterim("中场小结"));
        assertFalse(AgentOrchestrator.userRequestedInterim("不要中场小结，请给出最终报告"));
        assertFalse(AgentOrchestrator.userRequestedInterim("请收束并给出最终报告"));
    }

    private static ConversationMessage message(
            String id,
            long turnId,
            MessageRole role,
            String content,
            String agentSpeakerId
    ) {
        return new ConversationMessage(id, "sess_fixture", turnId, role, content, Instant.EPOCH, agentSpeakerId);
    }
}
