package com.questionos.backend.governance;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RequestTraceFilter implements WebFilter {
    private final MeterRegistry meterRegistry;

    public RequestTraceFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID().toString().substring(0, 8);
        }
        exchange.getResponse().getHeaders().add("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        meterRegistry.counter("questionos.request.total", "path", exchange.getRequest().getPath().value()).increment();
        return chain.filter(exchange).doFinally(signalType -> MDC.remove("requestId"));
    }
}
