package com.questionos.backend.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * 在写出响应前注入 Access-Control-Allow-Origin（预检外的 GET/POST/SSE 也需要）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CorsExposeResponseFilter implements WebFilter {

    private final CorsOriginEvaluator origins;

    public CorsExposeResponseFilter(CorsOriginEvaluator origins) {
        this.origins = origins;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api")) {
            return chain.filter(exchange);
        }
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (!origins.allowed(origin)) {
            return chain.filter(exchange);
        }

        final String allowOrigin = origin;
        ServerHttpResponse original = exchange.getResponse();
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(original) {
            private void ensureCors() {
                if (!getDelegate().getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)) {
                    getDelegate().getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowOrigin);
                }
            }

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                ensureCors();
                return super.writeWith(body);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                ensureCors();
                return super.writeAndFlushWith(body);
            }

            @Override
            public Mono<Void> setComplete() {
                ensureCors();
                return super.setComplete();
            }
        };

        return chain.filter(exchange.mutate().response(decorated).build());
    }
}
