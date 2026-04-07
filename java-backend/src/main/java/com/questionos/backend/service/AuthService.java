package com.questionos.backend.service;

import com.questionos.backend.api.dto.AuthDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final WebClient webClient;
    private final String googleClientId;
    /** 与前端 NEXT_PUBLIC_SANDBOX_TOKEN 对齐；本地调试可免 Google 登录 */
    private final String sandboxToken;
    private final Map<String, AuthDtos.AuthUser> sessionStore = new ConcurrentHashMap<>();
    private final Map<String, AuthDtos.AuthUser> googleUsers = new ConcurrentHashMap<>();
    private static final AuthDtos.AuthUser SANDBOX_USER = new AuthDtos.AuthUser(
            "sandbox_local",
            "dev@local",
            "本地沙盒（调试）",
            ""
    );

    public AuthService(
            @Value("${questionos.auth.google.client-id:}") String googleClientId,
            @Value("${questionos.auth.sandbox-token:}") String sandboxToken
    ) {
        this.webClient = WebClient.builder().build();
        this.googleClientId = googleClientId;
        this.sandboxToken = sandboxToken == null ? "" : sandboxToken.trim();
    }

    public Mono<AuthDtos.AuthSuccessResponse> loginWithGoogle(String idToken) {
        return webClient.get()
                .uri("https://oauth2.googleapis.com/tokeninfo?id_token={token}", idToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Map.class)
                .map(raw -> {
                    String aud = str(raw.get("aud"));
                    if (!googleClientId.isBlank() && !googleClientId.equals(aud)) {
                        throw new IllegalArgumentException("Google Client ID 不匹配");
                    }
                    String sub = str(raw.get("sub"));
                    if (sub.isBlank()) {
                        throw new IllegalArgumentException("Google token 无效");
                    }
                    String userId = "google_" + sub;
                    String email = str(raw.get("email"));
                    String name = str(raw.get("name"));
                    String picture = str(raw.get("picture"));
                    if (name.isBlank()) {
                        name = email.contains("@") ? email.substring(0, email.indexOf('@')) : "Google User";
                    }
                    AuthDtos.AuthUser user = new AuthDtos.AuthUser(userId, email, name, picture);
                    googleUsers.put(userId, user);

                    String sessionToken = "qos_" + UUID.randomUUID().toString().replace("-", "");
                    sessionStore.put(sessionToken, user);
                    return new AuthDtos.AuthSuccessResponse(sessionToken, user);
                });
    }

    public AuthDtos.AuthUser verifySessionToken(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) return null;
        AuthDtos.AuthUser fromSession = sessionStore.get(bearerToken);
        if (fromSession != null) {
            return fromSession;
        }
        if (!sandboxToken.isBlank() && sandboxToken.equals(bearerToken)) {
            return SANDBOX_USER;
        }
        return null;
    }

    public void logout(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) return;
        if (!sandboxToken.isBlank() && sandboxToken.equals(bearerToken)) {
            return;
        }
        sessionStore.remove(bearerToken);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
