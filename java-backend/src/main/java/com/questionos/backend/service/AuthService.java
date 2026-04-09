package com.questionos.backend.service;

import com.questionos.backend.api.dto.AuthDtos;
import com.questionos.backend.persistence.UserAccountJdbcRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final WebClient webClient;
    private final String googleClientId;
    /** 与前端 NEXT_PUBLIC_SANDBOX_TOKEN 对齐；本地调试可免 Google 登录 */
    private final String sandboxToken;
    /**
     * 旧版 qos_ 随机 token 的内存会话（仅兼容已登录用户）；新登录一律签发 JWT。
     */
    private final Map<String, AuthDtos.AuthUser> sessionStore = new ConcurrentHashMap<>();
    private final ObjectProvider<UserAccountJdbcRepository> userAccountRepository;
    private final String jwtSecretRaw;

    private static final AuthDtos.AuthUser SANDBOX_USER = new AuthDtos.AuthUser(
            "sandbox_local",
            "dev@local",
            "本地沙盒（调试）",
            ""
    );

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofDays(30);

    public AuthService(
            @Value("${questionos.auth.google.client-id:}") String googleClientId,
            @Value("${questionos.auth.sandbox-token:}") String sandboxToken,
            @Value("${questionos.auth.jwt.secret:}") String jwtSecret,
            ObjectProvider<UserAccountJdbcRepository> userAccountRepository
    ) {
        this.webClient = WebClient.builder().build();
        this.googleClientId = googleClientId;
        this.sandboxToken = sandboxToken == null ? "" : sandboxToken.trim();
        this.jwtSecretRaw = jwtSecret == null ? "" : jwtSecret.trim();
        this.userAccountRepository = userAccountRepository;
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
                    userAccountRepository.ifAvailable(repo -> repo.upsertGoogleUser(user));

                    String accessToken = issueAccessToken(user);
                    return new AuthDtos.AuthSuccessResponse(accessToken, user);
                });
    }

    private SecretKey jwtSigningKey() {
        String raw = jwtSecretRaw.isBlank()
                ? "questionos-dev-jwt-secret-change-with-QUESTIONOS_AUTH_JWT_SECRET"
                : jwtSecretRaw;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (Exception e) {
            throw new IllegalStateException("JWT signing key init failed", e);
        }
    }

    private String issueAccessToken(AuthDtos.AuthUser user) {
        Instant now = Instant.now();
        String avatar = user.avatar() != null ? user.avatar() : "";
        return Jwts.builder()
                .subject(user.id())
                .claim("email", user.email())
                .claim("name", user.name())
                .claim("avatar", avatar)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TOKEN_TTL)))
                .signWith(jwtSigningKey())
                .compact();
    }

    public AuthDtos.AuthUser verifySessionToken(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        if (!sandboxToken.isBlank() && sandboxToken.equals(bearerToken)) {
            return SANDBOX_USER;
        }
        AuthDtos.AuthUser fromSession = sessionStore.get(bearerToken);
        if (fromSession != null) {
            return fromSession;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(jwtSigningKey())
                    .build()
                    .parseSignedClaims(bearerToken)
                    .getPayload();
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                return null;
            }
            AuthDtos.AuthUser fromJwt = new AuthDtos.AuthUser(
                    sub,
                    str(claims.get("email")),
                    str(claims.get("name")),
                    str(claims.get("avatar"))
            );
            UserAccountJdbcRepository repo = userAccountRepository.getIfAvailable();
            if (repo != null) {
                Optional<AuthDtos.AuthUser> fromDb = repo.findByUserId(sub);
                if (fromDb.isPresent()) {
                    return fromDb.get();
                }
            }
            return fromJwt;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public void logout(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return;
        }
        if (!sandboxToken.isBlank() && sandboxToken.equals(bearerToken)) {
            return;
        }
        sessionStore.remove(bearerToken);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
