package com.smartfarm360.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Authentication settings ({@code app.auth.*}). See application.properties for defaults and environment variables.
 *
 * @param jwtSecret         HMAC secret for access tokens, at least 32 bytes. When blank a random secret is generated
 *                          at start-up (tokens then stop working after a restart) — set it in every real deployment.
 * @param publicWebUrl      Base URL of the web app, used in password-reset and invitation links.
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        boolean refreshCookieSecure,
        int maxFailedLogins,
        Duration lockoutDuration,
        Duration passwordResetTtl,
        Duration invitationTtl,
        String publicWebUrl) {
}
