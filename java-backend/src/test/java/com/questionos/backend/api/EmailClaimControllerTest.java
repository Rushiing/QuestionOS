package com.questionos.backend.api;

import com.questionos.backend.api.dto.AuthDtos;
import com.questionos.backend.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.mockito.Mockito.*;

class EmailClaimControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private WebTestClient client;

    @BeforeEach void setup() { client = WebTestClient.bindToController(new AuthController(auth)).build(); }

    @Test void requestDoesNotRequireGoogleOrSandboxAndDoesNotExposeAccount() {
        client.post().uri("/api/auth/email-claim/request").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "old@example.test")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.detail").exists().jsonPath("$.token").doesNotExist()
                .jsonPath("$.user").doesNotExist();
        verify(auth).requestEmailClaim("old@example.test");
    }

    @Test void rejectsMalformedEmailAndTokenBeforeService() {
        client.post().uri("/api/auth/email-claim/request").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "bad")).exchange().expectStatus().isBadRequest();
        client.post().uri("/api/auth/email-claim/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "old@example.test", "token", "sandbox", "password", "password"))
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(auth);
    }

    @Test void mailUnavailableHasSanitized503() {
        doThrow(new IllegalStateException("private diagnostics")).when(auth).requestEmailClaim("old@example.test");
        client.post().uri("/api/auth/email-claim/request").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "old@example.test")).exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.detail").isEqualTo("邮箱验证暂不可用，请稍后重试");
    }

    @Test void validConfirmationReturnsOriginalIdentity() {
        String token = "a".repeat(43);
        when(auth.claimEmail("old@example.test", token, "password")).thenReturn(new AuthDtos.AuthSuccessResponse(
                "test-jwt", new AuthDtos.AuthUser("google_old", "old@example.test", "Old", "")));
        client.post().uri("/api/auth/email-claim/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "old@example.test", "token", token, "password", "password"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user.id").isEqualTo("google_old");
    }

    @Test void invalidOrExpiredConfirmationIsRejected() {
        String token = "a".repeat(43);
        when(auth.claimEmail("old@example.test", token, "password")).thenThrow(new IllegalArgumentException("验证口令无效或已过期，请重新申请"));
        client.post().uri("/api/auth/email-claim/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "old@example.test", "token", token, "password", "password"))
                .exchange().expectStatus().isBadRequest();
    }
}
