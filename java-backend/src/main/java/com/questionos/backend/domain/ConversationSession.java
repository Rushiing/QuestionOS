package com.questionos.backend.domain;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ConversationSession {
    private final String sessionId;
    private final String ownerUserId;
    private final SessionMode mode;
    private volatile SessionStatus status;
    private final Instant createdAt;
    private volatile Instant lastActivityAt;
    private volatile Instant expiresAt;
    /** LLM 生成的列表展示标题（异步写入，可能短暂为 null） */
    private volatile String displayTitle;
    private final AtomicLong messageCount;
    private final AtomicLong turnSeq;
    /** 沙盘模式：用户每发一条消息，轮转一位 agent 发言（一问一答） */
    private final AtomicInteger sandboxSpeakerRound;

    public ConversationSession(String sessionId, String ownerUserId, SessionMode mode, Instant createdAt, Instant expiresAt) {
        this.sessionId = sessionId;
        this.ownerUserId = ownerUserId;
        this.mode = mode;
        this.status = SessionStatus.CREATED;
        this.createdAt = createdAt;
        this.lastActivityAt = createdAt;
        this.expiresAt = expiresAt;
        this.messageCount = new AtomicLong(0);
        this.turnSeq = new AtomicLong(0);
        this.sandboxSpeakerRound = new AtomicInteger(0);
    }

    public int nextSandboxSpeakerRound() {
        return sandboxSpeakerRound.getAndIncrement();
    }

    public long nextTurn() {
        return turnSeq.incrementAndGet();
    }

    public long markMessage(Instant now, Instant newExpiry) {
        status = SessionStatus.ACTIVE;
        lastActivityAt = now;
        expiresAt = newExpiry;
        return messageCount.incrementAndGet();
    }

    public void complete() {
        status = SessionStatus.COMPLETED;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public SessionMode getMode() {
        return mode;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getMessageCount() {
        return messageCount.get();
    }

    public String getDisplayTitle() {
        return displayTitle;
    }

    public void setDisplayTitle(String displayTitle) {
        this.displayTitle = displayTitle;
    }
}
