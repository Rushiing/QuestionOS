package com.questionos.backend.agent;

import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
