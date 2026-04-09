-- 对话会话与消息（按 owner_user_id 隔离，供历史记录与继续对话）

CREATE TABLE qos_conversation_session (
    session_id            VARCHAR(64) PRIMARY KEY,
    owner_user_id         VARCHAR(256) NOT NULL,
    mode                  VARCHAR(32)  NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    last_activity_at      TIMESTAMPTZ  NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    display_title         TEXT,
    turn_seq              BIGINT       NOT NULL DEFAULT 0,
    sandbox_speaker_round INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_qos_session_owner ON qos_conversation_session (owner_user_id);
CREATE INDEX idx_qos_session_created ON qos_conversation_session (created_at DESC);

CREATE TABLE qos_conversation_message (
    message_id        VARCHAR(64) PRIMARY KEY,
    session_id        VARCHAR(64) NOT NULL REFERENCES qos_conversation_session (session_id) ON DELETE CASCADE,
    turn_id           BIGINT       NOT NULL,
    role              VARCHAR(16)  NOT NULL,
    content           TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    agent_speaker_id  VARCHAR(128)
);

CREATE INDEX idx_qos_message_session ON qos_conversation_message (session_id, created_at);
