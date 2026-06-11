package com.questionos.backend.persistence;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 把会话持久化状态挂进 /actuator/health：
 * postgres profile 下 persistence 被禁用（如 Dockerfile/profile 配置漂移）时直接报 DOWN，
 * 让 Railway healthcheck 在部署阶段拦截，而不是带着"会话重启即丢"的状态假装健康。
 * 2026-06 曾因 Root Directory 误配导致该状态静默上线，故增设此闸门。
 */
@Component
public class SessionPersistenceHealthIndicator implements HealthIndicator {

    private final SessionSnapshotPersistence persistence;
    private final Environment environment;

    public SessionPersistenceHealthIndicator(SessionSnapshotPersistence persistence, Environment environment) {
        this.persistence = persistence;
        this.environment = environment;
    }

    @Override
    public Health health() {
        boolean enabled = persistence.isEnabled();
        boolean productionProfile = Arrays.asList(environment.getActiveProfiles()).contains("postgres");
        Health.Builder builder = (productionProfile && !enabled) ? Health.down() : Health.up();
        return builder
                .withDetail("backend", persistence.getClass().getSimpleName())
                .withDetail("enabled", enabled)
                .withDetail("profilePostgres", productionProfile)
                .build();
    }
}
