package com.questionos.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CorsOriginEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CorsOriginEvaluator.class);

    private final List<String> exactOrigins;

    public CorsOriginEvaluator(
            @Value("${questionos.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOriginsCsv
    ) {
        this.exactOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    @PostConstruct
    void logOrigins() {
        log.info("CORS exact origins count={} (+ patterns *.up.railway.app / localhost)", exactOrigins.size());
    }

    public boolean allowed(String origin) {
        if (origin == null || origin.isBlank()) return false;
        if (exactOrigins.contains(origin)) return true;
        if (origin.startsWith("https://") && origin.endsWith(".up.railway.app")) return true;
        if (origin.startsWith("http://localhost:")) return true;
        if (origin.startsWith("http://127.0.0.1:")) return true;
        return false;
    }

    public List<String> exactOrigins() {
        return exactOrigins;
    }
}
