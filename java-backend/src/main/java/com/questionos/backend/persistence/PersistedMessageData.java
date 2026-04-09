package com.questionos.backend.persistence;

import java.time.Instant;

public class PersistedMessageData {
    private String messageId;
    private String sessionId;
    private long turnId;
    private String role;
    private String content;
    private Instant createdAt;
    private String agentSpeakerId;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getTurnId() {
        return turnId;
    }

    public void setTurnId(long turnId) {
        this.turnId = turnId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getAgentSpeakerId() {
        return agentSpeakerId;
    }

    public void setAgentSpeakerId(String agentSpeakerId) {
        this.agentSpeakerId = agentSpeakerId;
    }
}
