package com.questionos.backend.agent;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@Component
public class MainCalibrateAgent implements AgentExecutor {
    @Override
    public String agentId() {
        return "main-calibrate";
    }

    @Override
    public Flux<AgentReplyChunk> reply(String sessionId, long turnId, String input) {
        List<String> chunks = List.of(
                "我先帮你拆解问题边界。",
                "你提到的核心顾虑是学习速度和路径不清晰。",
                "先给我你本周可投入时长与当前基础，我再给下一轮校准问题。"
        );
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(120))
                .map(text -> new AgentReplyChunk("agent_chunk", text));
    }
}
