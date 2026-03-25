package com.questionos.backend.agent;

import reactor.core.publisher.Flux;

public interface AgentExecutor {
    String agentId();
    Flux<AgentReplyChunk> reply(String sessionId, long turnId, String input);
}
