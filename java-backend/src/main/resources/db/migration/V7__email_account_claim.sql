-- Fail on conflicting emails; never merge or discard accounts automatically.
CREATE UNIQUE INDEX uq_qos_user_account_email
    ON qos_user_account (lower(btrim(email)))
    WHERE email IS NOT NULL AND btrim(email) <> '';

CREATE TABLE qos_email_claim (
    user_id VARCHAR(256) PRIMARY KEY REFERENCES qos_user_account(user_id),
    email TEXT NOT NULL,
    token_hash VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    send_count INTEGER NOT NULL
);
CREATE TABLE qos_email_claim_budget (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    window_start TIMESTAMPTZ NOT NULL,
    send_count INTEGER NOT NULL
);
INSERT INTO qos_email_claim_budget VALUES (1, now(), 0);
