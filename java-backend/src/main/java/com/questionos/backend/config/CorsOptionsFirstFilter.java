package com.questionos.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 显式结束 OPTIONS 预检并返回 CORS 头（兜底，避免部分部署里 CorsWebFilter 预检仍无 ACAO）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorsOptionsFirstFilter implements WebFilter {

    private final List<String> exactOrigins;
    private static final String ALLOW_HEADERS =
            "Authorization, Content-Type, X-API-Version, Idempotency-Key, Last-Event-ID, Accept, Origin";

    public CorsOptionsFirstFilter(
            @Value("${questionos.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOriginsCsv
    ) {
        this.exactOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    static boolean originAllowed(String origin, List<String> exactOrigins) {
        if (origin == null || origin.isBlank()) return false;
        if (exactOrigins.contains(origin)) return true;
        if (origin.startsWith("https://") && origin.endsWith(".up.railway.app")) return true;
        if (origin.startsWith("http://localhost:")) return true;
        if (origin.startsWith("http://127.0.0.1:")) return true;
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api")) {
            return chain.filter(exchange);
        }
        if (!HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (!originAllowed(origin, exactOrigins)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse res = exchange.getResponse();
        res.setStatusCode(HttpStatus.OK);
        res.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        res.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
        res.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOW_HEADERS);
        res.getHeaders().set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
        return res.setComplete();
    }
}
