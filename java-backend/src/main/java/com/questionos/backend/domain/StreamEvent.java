package com.questionos.backend.domain;

import java.time.Instant;

public record StreamEvent(
        String eventId,
        String sessionId,
        long seq,
        long turnId,
        String eventType,
        String payload,
        Instant createdAt
) {
}
