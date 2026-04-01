package com.questionos.backend.api.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthDtos {
    public record GoogleAuthRequest(@NotBlank String id_token) {}

    public record AuthUser(
            String id,
            String email,
            String name,
            String avatar
    ) {}

    public record AuthSuccessResponse(
            String access_token,
            AuthUser user
    ) {}

    public record MeResponse(
            AuthUser user
    ) {}
}
