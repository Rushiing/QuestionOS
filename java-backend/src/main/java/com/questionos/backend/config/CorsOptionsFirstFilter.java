package com.questionos.backend.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 最高优先级：直接结束 OPTIONS 预检并返回 CORS 头（先于其它 WebFilter）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsOptionsFirstFilter implements WebFilter {

    private static final String ALLOW_HEADERS =
            "Authorization, Content-Type, X-API-Version, Idempotency-Key, Last-Event-ID, Accept, Origin";

    private final CorsOriginEvaluator origins;

    public CorsOptionsFirstFilter(CorsOriginEvaluator origins) {
        this.origins = origins;
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
        if (!origins.allowed(origin)) {
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
