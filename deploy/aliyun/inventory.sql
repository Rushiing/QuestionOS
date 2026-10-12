\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SELECT current_setting('server_version') AS server_version,
       pg_database_size(current_database()) AS database_bytes;
SELECT extname, extversion FROM pg_extension ORDER BY extname;
SELECT version, checksum, success FROM flyway_schema_history
ORDER BY installed_rank;
SELECT 'accounts' AS entity, count(*) FROM qos_user_account
UNION ALL SELECT 'sessions', count(*) FROM qos_conversation_session
UNION ALL SELECT 'messages', count(*) FROM qos_conversation_message;
-- Aggregate only: never export email addresses, hashes or conversation content here.
SELECT provider, (password_hash IS NOT NULL) AS has_password, count(*)
FROM qos_user_account GROUP BY provider, (password_hash IS NOT NULL)
ORDER BY provider, has_password;
SELECT count(*) AS duplicate_email_groups FROM (
    SELECT lower(btrim(email)) FROM qos_user_account
    WHERE email IS NOT NULL AND btrim(email) <> ''
    GROUP BY lower(btrim(email)) HAVING count(*) > 1
) AS duplicates;
COMMIT;
