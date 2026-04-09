-- 会话与用户的逻辑关联：qos_conversation_session.owner_user_id = qos_user_account.user_id（均为 JWT subject）
-- 不设外键：本地/沙盒 token（如 sandbox_local）可能无用户表记录，但仍需能创建会话

COMMENT ON COLUMN qos_conversation_session.owner_user_id IS '登录用户 id，与 qos_user_account.user_id 一致；可与用户表 JOIN';
COMMENT ON COLUMN qos_user_account.user_id IS '与 qos_conversation_session.owner_user_id 对应';

-- 便于运营/排查：会话行带上用户资料（无用户行时左连接仍保留会话）
CREATE OR REPLACE VIEW qos_session_with_user AS
SELECT
    s.session_id,
    s.owner_user_id,
    s.mode,
    s.status,
    s.created_at           AS session_created_at,
    s.last_activity_at,
    s.expires_at,
    s.display_title,
    s.turn_seq,
    s.sandbox_speaker_round,
    u.email                AS user_email,
    u.display_name         AS user_display_name,
    u.avatar_url           AS user_avatar_url,
    u.provider             AS user_provider,
    u.created_at           AS user_created_at,
    u.last_login_at        AS user_last_login_at
FROM qos_conversation_session s
LEFT JOIN qos_user_account u ON u.user_id = s.owner_user_id;
