package com.questionos.backend.api;

import com.questionos.backend.api.dto.AuthDtos;
import com.questionos.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/google")
    public Mono<ResponseEntity<AuthDtos.AuthSuccessResponse>> google(@Valid @RequestBody AuthDtos.GoogleAuthRequest request) {
        return authService.loginWithGoogle(request.id_token())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<Object>> register(@Valid @RequestBody AuthDtos.PasswordRegisterRequest request) {
        return Mono.fromCallable(() -> ResponseEntity.ok((Object) authService.registerWithPassword(
                        request.email(), request.password(), request.name())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(
                        ResponseEntity.status(HttpStatus.CONFLICT).body((Object) java.util.Map.of("detail", e.getMessage()))));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@Valid @RequestBody AuthDtos.PasswordLoginRequest request) {
        return Mono.fromCallable(() -> ResponseEntity.ok((Object)
                        authService.loginWithPassword(request.email(), request.password())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body((Object) java.util.Map.of("detail", e.getMessage()))));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String token = extractBearer(authHeader);
        AuthDtos.AuthUser user = authService.verifySessionToken(token);
        if (user == null) {
            return ResponseEntity.status(401).body(java.util.Map.of("detail", "未登录"));
        }
        return ResponseEntity.ok(new AuthDtos.MeResponse(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String token = extractBearer(authHeader);
        authService.logout(token);
        return ResponseEntity.ok(java.util.Map.of("status", "ok"));
    }

    private String extractBearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return "";
        return authHeader.substring("Bearer ".length()).trim();
    }
}
