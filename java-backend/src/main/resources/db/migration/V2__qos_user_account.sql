-- 登录用户资料（与 JWT subject 一致，如 google_<sub>）

CREATE TABLE qos_user_account (
    user_id       VARCHAR(256) PRIMARY KEY,
    email         VARCHAR(512) NOT NULL DEFAULT '',
    display_name  VARCHAR(512) NOT NULL DEFAULT '',
    avatar_url    TEXT,
    provider      VARCHAR(32)  NOT NULL DEFAULT 'google',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_qos_user_account_email ON qos_user_account (email);
