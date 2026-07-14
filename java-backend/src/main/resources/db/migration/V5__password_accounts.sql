ALTER TABLE qos_user_account
    ADD COLUMN password_hash VARCHAR(100);

CREATE UNIQUE INDEX uq_qos_user_account_password_email
    ON qos_user_account (lower(email))
    WHERE provider = 'password';
