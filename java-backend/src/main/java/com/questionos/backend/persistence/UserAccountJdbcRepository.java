package com.questionos.backend.persistence;

import com.questionos.backend.api.dto.AuthDtos;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户账号落库（当前：Google 登录 upsert）。
 * 使用 {@code @Profile("postgres")} 而非 {@code @ConditionalOnBean(DataSource)}，避免 WebFlux 下条件早于 DataSource 注册导致本 Bean 被跳过、登录不写库。
 */
@Repository
@Profile("postgres")
public class UserAccountJdbcRepository {
    public record PasswordAccount(AuthDtos.AuthUser user, String passwordHash) {}
    private final JdbcTemplate jdbc;

    public UserAccountJdbcRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private static final RowMapper<AuthDtos.AuthUser> ROW = (rs, rowNum) -> new AuthDtos.AuthUser(
            rs.getString("user_id"),
            rs.getString("email") != null ? rs.getString("email") : "",
            rs.getString("display_name") != null ? rs.getString("display_name") : "",
            rs.getString("avatar_url") != null ? rs.getString("avatar_url") : ""
    );

    public void upsertGoogleUser(AuthDtos.AuthUser user) {
        String avatar = user.avatar() != null ? user.avatar() : "";
        jdbc.update(
                """
                        INSERT INTO qos_user_account (
                          user_id, email, display_name, avatar_url, provider, created_at, last_login_at
                        ) VALUES (?,?,?,?, 'google', now(), now())
                        ON CONFLICT (user_id) DO UPDATE SET
                          email = EXCLUDED.email,
                          display_name = EXCLUDED.display_name,
                          avatar_url = EXCLUDED.avatar_url,
                          last_login_at = now()
                        """,
                user.id(),
                user.email() != null ? user.email() : "",
                user.name() != null ? user.name() : "",
                avatar);
    }

    public void createPasswordUser(AuthDtos.AuthUser user, String passwordHash) {
        jdbc.update(
                """
                        INSERT INTO qos_user_account (
                          user_id, email, display_name, avatar_url, provider, password_hash, created_at, last_login_at
                        ) VALUES (?,?,?,?, 'password', ?, now(), now())
                        """,
                user.id(), user.email(), user.name(), "", passwordHash);
    }

    public Optional<PasswordAccount> findPasswordAccountByEmail(String email) {
        var list = jdbc.query(
                """
                        SELECT user_id, email, display_name, avatar_url, password_hash
                        FROM qos_user_account
                        WHERE password_hash IS NOT NULL AND lower(btrim(email)) = lower(btrim(?))
                        """,
                (rs, rowNum) -> new PasswordAccount(
                        new AuthDtos.AuthUser(
                                rs.getString("user_id"), rs.getString("email"), rs.getString("display_name"),
                                rs.getString("avatar_url") != null ? rs.getString("avatar_url") : ""),
                        rs.getString("password_hash")),
                email);
        return list.stream().findFirst();
    }

    public Optional<AuthDtos.AuthUser> findByEmail(String email) {
        return jdbc.query("SELECT user_id, email, display_name, avatar_url FROM qos_user_account "
                + "WHERE lower(btrim(email)) = ?", ROW, email).stream().findFirst();
    }

    /** Reserve a one-time challenge. Limits persist across restarts and replicas. */
    @Transactional
    public Optional<AuthDtos.AuthUser> reserveEmailClaim(String email, String tokenHash) {
        var users = jdbc.query("""
                SELECT user_id, email, display_name, avatar_url FROM qos_user_account
                WHERE lower(btrim(email)) = ? AND provider = 'google' AND password_hash IS NULL
                FOR UPDATE
                """, ROW, email);
        if (users.isEmpty()) return Optional.empty();
        var user = users.getFirst();
        int reserved = jdbc.update("""
                INSERT INTO qos_email_claim (user_id, email, token_hash, expires_at, sent_at, window_start, send_count)
                VALUES (?, ?, ?, now() + interval '10 minutes', now(), now(), 1)
                ON CONFLICT (user_id) DO UPDATE SET
                  email = EXCLUDED.email, token_hash = EXCLUDED.token_hash,
                  expires_at = EXCLUDED.expires_at, sent_at = now(),
                  window_start = CASE WHEN qos_email_claim.window_start <= now() - interval '1 hour'
                    THEN now() ELSE qos_email_claim.window_start END,
                  send_count = CASE WHEN qos_email_claim.window_start <= now() - interval '1 hour'
                    THEN 1 ELSE qos_email_claim.send_count + 1 END
                WHERE qos_email_claim.sent_at <= now() - interval '60 seconds'
                  AND (qos_email_claim.window_start <= now() - interval '1 hour' OR qos_email_claim.send_count < 5)
                """, user.id(), email, tokenHash);
        if (reserved == 0) return Optional.empty();
        int budget = jdbc.update("""
                UPDATE qos_email_claim_budget SET
                  send_count = CASE WHEN window_start <= now() - interval '1 hour' THEN 1 ELSE send_count + 1 END,
                  window_start = CASE WHEN window_start <= now() - interval '1 hour' THEN now() ELSE window_start END
                WHERE id = 1 AND (window_start <= now() - interval '1 hour' OR send_count < 30)
                """);
        if (budget == 0) {
            invalidateEmailClaim(user.id(), tokenHash);
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public void invalidateEmailClaim(String userId, String tokenHash) {
        jdbc.update("UPDATE qos_email_claim SET token_hash = NULL WHERE user_id = ? AND token_hash = ?",
                userId, tokenHash);
    }

    public boolean validEmailClaim(String email, String tokenHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM qos_email_claim c JOIN qos_user_account u USING (user_id)
                WHERE c.email = ? AND lower(btrim(u.email)) = c.email AND c.token_hash = ?
                  AND c.expires_at > now() AND u.provider = 'google' AND u.password_hash IS NULL)
                """, Boolean.class, email, tokenHash));
    }

    /** User row lock serializes sending and claiming; password and token change together. */
    @Transactional
    public Optional<AuthDtos.AuthUser> claimEmail(String email, String tokenHash, String passwordHash) {
        var users = jdbc.query("""
                SELECT user_id, email, display_name, avatar_url FROM qos_user_account
                WHERE lower(btrim(email)) = ? AND provider = 'google' AND password_hash IS NULL
                FOR UPDATE
                """, ROW, email);
        if (users.isEmpty()) return Optional.empty();
        var user = users.getFirst();
        int consumed = jdbc.update("""
                UPDATE qos_email_claim SET token_hash = NULL
                WHERE user_id = ? AND email = ? AND token_hash = ? AND expires_at > now()
                """, user.id(), email, tokenHash);
        if (consumed == 0) return Optional.empty();
        jdbc.update("UPDATE qos_user_account SET password_hash = ?, last_login_at = now() WHERE user_id = ?",
                passwordHash, user.id());
        return Optional.of(user);
    }

    public Optional<AuthDtos.AuthUser> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        var list = jdbc.query(
                "SELECT user_id, email, display_name, avatar_url FROM qos_user_account WHERE user_id = ?",
                ROW,
                userId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.getFirst());
    }
}
