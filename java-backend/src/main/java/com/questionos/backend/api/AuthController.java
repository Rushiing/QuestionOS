package com.questionos.backend.api;

import com.questionos.backend.api.dto.AuthDtos;
import com.questionos.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

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
