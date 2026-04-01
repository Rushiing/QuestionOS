package com.questionos.backend.api.dto;

import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.domain.SessionStatus;
import com.questionos.backend.domain.MessageRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public class SandboxDtos {
    public record CreateSessionRequest(
            @NotNull SessionMode mode,
            @NotBlank String question
    ) {}

    public record CreateSessionResponse(
            String sessionId,
            String status,
            Instant createdAt
    ) {}

    public record SendMessageRequest(
            @NotBlank String content
    ) {}

    public record SendMessageResponse(
            String messageId,
            String status,
            String idempotencyKey
    ) {}

    public record SessionStatusResponse(
            String sessionId,
            SessionMode mode,
            SessionStatus status,
            long messageCount,
            Instant createdAt,
            Instant expiresAt,
            Instant lastActivityAt
    ) {}

    public record SessionListItem(
            String sessionId,
            SessionMode mode,
            SessionStatus status,
            long messageCount,
            Instant createdAt,
            Instant lastActivityAt,
            /** LLM 摘要标题，失败时为截断首问 */
            String title
    ) {}

    public record SessionListResponse(List<SessionListItem> sessions) {}

    public record SessionMessageItem(
            String messageId,
            MessageRole role,
            String content,
            long turnId,
            Instant createdAt,
            String agentSpeakerId
    ) {}

    public record SessionMessagesResponse(String sessionId, List<SessionMessageItem> messages) {}

    public record DeleteSessionResponse(String status) {}
}
