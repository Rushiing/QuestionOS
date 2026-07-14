ALTER TABLE qos_conversation_session
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_qos_session_owner_visible
    ON qos_conversation_session (owner_user_id, created_at DESC)
    WHERE deleted_at IS NULL;
