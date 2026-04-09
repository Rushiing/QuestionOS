package com.questionos.backend.persistence;

import com.questionos.backend.api.dto.AuthDtos;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * 用户账号落库（当前：Google 登录 upsert）。
 * 使用 {@code @Profile("postgres")} 而非 {@code @ConditionalOnBean(DataSource)}，避免 WebFlux 下条件早于 DataSource 注册导致本 Bean 被跳过、登录不写库。
 */
@Repository
@Profile("postgres")
public class UserAccountJdbcRepository {
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
