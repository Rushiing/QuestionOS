package com.questionos.backend.governance;

import com.questionos.backend.api.dto.AuthDtos;
import com.questionos.backend.service.AuthService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AuthAndRateLimitFilter implements WebFilter {
    private final int maxMessagesPerMinute;
    private final MeterRegistry meterRegistry;
    private final AuthService authService;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public AuthAndRateLimitFilter(
            @Value("${questionos.limits.maxMessagesPerMinute}") int maxMessagesPerMinute,
            MeterRegistry meterRegistry,
            AuthService authService
    ) {
        this.maxMessagesPerMinute = maxMessagesPerMinute;
        this.meterRegistry = meterRegistry;
        this.authService = authService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/auth")) {
            return chain.filter(exchange);
        }
        if (!path.startsWith("/api/v1")) {
            return chain.filter(exchange);
        }
        if (isPublicOnboardingPath(path, exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        // Let CORS preflight pass through without auth challenge.
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        String token = extractBearer(auth);
        AuthDtos.AuthUser user = authService.verifySessionToken(token);
        if (user == null) {
            meterRegistry.counter("questionos.auth.rejected").increment();
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录后再继续对话");
        }
        exchange.getAttributes().put("authUserId", user.id());
        if (path.contains("/messages") && "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())) {
            if (!allow(user.id())) {
                meterRegistry.counter("questionos.rate_limited").increment();
                return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求频率超限");
            }
        }
        return chain.filter(exchange);
    }

    private boolean allow(String token) {
        WindowCounter counter = counters.computeIfAbsent(token, k -> new WindowCounter(Instant.now().getEpochSecond() / 60, new AtomicInteger(0)));
        long minute = Instant.now().getEpochSecond() / 60;
        if (counter.minute != minute) {
            counter.minute = minute;
            counter.count.set(0);
        }
        return counter.count.incrementAndGet() <= maxMessagesPerMinute;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String requestId = exchange.getRequest().getId();
        String body = "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"" + requestId + "\"}}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }

    private String extractBearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return "";
        return authHeader.substring("Bearer ".length()).trim();
    }

    /**
     * Agent 自助接入需要外部可直接访问说明与提交接口（通过 submitToken 二次校验）。
     */
    private boolean isPublicOnboardingPath(String path, HttpMethod method) {
        if (!path.startsWith("/api/v1/agents/onboarding-jobs/")) {
            return false;
        }
        // 允许 agent 拉取机器说明：GET /onboarding-jobs/{jobId}
        if (HttpMethod.GET.equals(method) && !path.endsWith("/status")) {
            return true;
        }
        // 允许 agent 提交结果：POST /onboarding-jobs/{jobId}/submit
        return HttpMethod.POST.equals(method) && path.endsWith("/submit");
    }

    private static class WindowCounter {
        volatile long minute;
        final AtomicInteger count;
        WindowCounter(long minute, AtomicInteger count) {
            this.minute = minute;
            this.count = count;
        }
    }
}
