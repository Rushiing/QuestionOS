package com.questionos.backend.agent;

public record AgentReplyChunk(
        String eventType,
        String content
) {
}
