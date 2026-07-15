package com.questionos.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsOriginEvaluatorTest {

    private final CorsOriginEvaluator evaluator =
            new CorsOriginEvaluator("https://questionos.example.com,http://localhost:3000");

    @Test
    void allowsConfiguredRailwayAndLocalOrigins() {
        assertTrue(evaluator.allowed("https://questionos.example.com"));
        assertTrue(evaluator.allowed("https://preview.up.railway.app"));
        assertTrue(evaluator.allowed("http://127.0.0.1:3100"));
    }

    @Test
    void rejectsLookalikeAndMissingOrigins() {
        assertFalse(evaluator.allowed("https://preview.up.railway.app.evil.example"));
        assertFalse(evaluator.allowed("https://questionos.example.com.evil.example"));
        assertFalse(evaluator.allowed(null));
    }
}
