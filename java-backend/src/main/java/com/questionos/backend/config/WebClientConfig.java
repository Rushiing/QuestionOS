package com.questionos.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * LLM 响应体偶发较大（或错误页 HTML）；默认 256KB 内存缓冲不够时会抛 DataBufferLimitException。
 */
@Configuration
public class WebClientConfig {

    private static final int MAX_IN_MEMORY_MB = 4;

    @Bean
    public WebClient.Builder webClientBuilder() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_MB * 1024 * 1024))
                .build();
        return WebClient.builder().exchangeStrategies(strategies);
    }
}
