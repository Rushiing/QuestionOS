package com.questionos.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CorsWebFilter + {@link CorsOptionsFirstFilter} 显式 OPTIONS；并增加 Railway / 本地 pattern，避免环境变量未生效时预检失败。
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter(
            @Value("${questionos.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOriginsCsv
    ) {
        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        CorsConfiguration config = new CorsConfiguration();
        origins.forEach(config::addAllowedOrigin);
        config.addAllowedOriginPattern("https://*.up.railway.app");
        config.addAllowedOriginPattern("http://localhost:*");
        config.addAllowedOriginPattern("http://127.0.0.1:*");

        config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-API-Version",
                "Idempotency-Key",
                "Last-Event-ID",
                "Accept",
                "Origin",
                "X-Requested-With"
        ));
        config.addExposedHeader("X-Request-Id");
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        log.info("CORS: exact origins count={}, plus patterns *.up.railway.app / localhost", origins.size());
        return new CorsWebFilter(source);
    }
}
