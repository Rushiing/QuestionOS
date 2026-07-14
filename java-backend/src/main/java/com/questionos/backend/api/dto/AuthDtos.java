package com.questionos.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class AuthDtos {
    public record GoogleAuthRequest(@NotBlank String id_token) {}

    public record PasswordRegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6, max = 128) String password,
            String name
    ) {}

    public record PasswordLoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

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
