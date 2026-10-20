package com.smartfarm360.platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(max = 200) String password,
                                  @NotBlank @Size(max = 140) String displayName,
                                  @Pattern(regexp = "en|ta") String preferredLanguage) {}

    /** {@code refreshTokenDelivery}: COOKIE (web, default) or BODY (mobile apps). Email format is not checked so legacy usernames still sign in. */
    public record LoginRequest(@NotBlank @Size(max = 254) String email,
                               @NotBlank @Size(max = 200) String password,
                               @Pattern(regexp = "COOKIE|BODY") String refreshTokenDelivery) {}

    public record RefreshRequest(@Size(max = 200) String refreshToken) {}

    public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record PasswordResetConfirm(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String newPassword) {}

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}

    public record UpdateProfileRequest(@Size(min = 1, max = 140) String displayName, @Pattern(regexp = "en|ta") String preferredLanguage) {}

    public record UserResponse(long id, String email, String displayName, String preferredLanguage, boolean platformAdmin) {}

    /** {@code refreshToken} is only present when it was requested in the body (mobile). */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, UserResponse user) {}

    public record AcceptedResponse(String status) {
        public static final AcceptedResponse ACCEPTED = new AcceptedResponse("ACCEPTED");
    }
}
