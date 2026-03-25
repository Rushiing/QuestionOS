package com.questionos.backend.domain;

import java.time.Instant;

public record ConversationMessage(
        String messageId,
        String sessionId,
        long turnId,
        MessageRole role,
        String content,
        Instant createdAt,
        /** 沙盘/多角色：如 auditor、third-party-adapter；用户消息为 null */
        String agentSpeakerId
) {
}
