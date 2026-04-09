package com.questionos.backend.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 可序列化的会话快照（落盘），用于按账号持久化历史对话。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PersistedSessionData {
    private String sessionId;
    private String ownerUserId;
    private String mode;
    private String status;
    private Instant createdAt;
    private Instant lastActivityAt;
    private Instant expiresAt;
    private String displayTitle;
    private long turnSeq;
    private int sandboxSpeakerRound;
    private List<PersistedMessageData> messages = new ArrayList<>();

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Instant lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getDisplayTitle() {
        return displayTitle;
    }

    public void setDisplayTitle(String displayTitle) {
        this.displayTitle = displayTitle;
    }

    public long getTurnSeq() {
        return turnSeq;
    }

    public void setTurnSeq(long turnSeq) {
        this.turnSeq = turnSeq;
    }

    public int getSandboxSpeakerRound() {
        return sandboxSpeakerRound;
    }

    public void setSandboxSpeakerRound(int sandboxSpeakerRound) {
        this.sandboxSpeakerRound = sandboxSpeakerRound;
    }

    public List<PersistedMessageData> getMessages() {
        return messages;
    }

    public void setMessages(List<PersistedMessageData> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }
}
