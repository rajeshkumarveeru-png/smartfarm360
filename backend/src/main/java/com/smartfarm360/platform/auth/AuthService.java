package com.smartfarm360.platform.auth;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.auth.AuthDtos.*;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.ratelimit.RateLimiter;
import com.smartfarm360.platform.security.AccessTokenService;
import com.smartfarm360.platform.security.AuthProperties;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.user.SfUserAccount;
import com.smartfarm360.platform.user.SfUserAccountRepository;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registration, sign-in, refresh-token rotation, sign-out and password recovery (ADR 0003).
 * Responses never reveal whether an email address has an account.
 */
@Service
public class AuthService {
    private final SfUserAccountRepository users;
    private final SfRefreshTokenRepository refreshTokens;
    private final SfPasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;
    private final RateLimiter rateLimiter;
    private final EmailSender emailSender;
    private final AuditService audit;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(SfUserAccountRepository users, SfRefreshTokenRepository refreshTokens,
                       SfPasswordResetTokenRepository resetTokens, PasswordEncoder passwordEncoder,
                       AccessTokenService accessTokens, AuthProperties properties, RateLimiter rateLimiter,
                       EmailSender emailSender, AuditService audit, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.emailSender = emailSender;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equalisation-only");
    }

    /** Tokens for a successful sign-in or refresh. {@code refreshToken} is the raw secret; the controller decides how it is delivered. */
    public record Session(AccessTokenService.IssuedAccessToken accessToken, String refreshToken, UserResponse user) {}

    @Transactional
    public void register(RegisterRequest request, String clientIp) {
        rateLimiter.check("register:ip:" + clientIp, 10, Duration.ofHours(1));
        String email = normalize(request.email());
        PasswordPolicy.check(request.password(), email);
        String hash = passwordEncoder.encode(request.password()); // always hash, so both paths take similar time
        users.findByEmailIgnoreCase(email).ifPresentOrElse(
                existing -> AfterCommit.run(() -> emailSender.sendRegistrationAttempt(existing.getEmail(), existing.getPreferredLanguage())),
                () -> {
                    SfUserAccount user = new SfUserAccount();
                    user.setEmail(email);
                    user.setDisplayName(request.displayName().trim());
                    user.setPasswordHash(hash);
                    user.setPreferredLanguage(request.preferredLanguage() == null ? "en" : request.preferredLanguage());
                    user.setPasswordChangedAt(clock.instant());
                    users.save(user);
                });
    }

    /** Failed-attempt counters must persist even though the request fails, hence noRollbackFor. */
    @Transactional(noRollbackFor = ApiException.class)
    public Session login(LoginRequest request, String clientIp) {
        String email = normalize(request.email());
        rateLimiter.check("login:ip:" + clientIp, 30, Duration.ofMinutes(5));
        rateLimiter.check("login:email:" + email, 10, Duration.ofMinutes(15));
        Instant now = clock.instant();

        SfUserAccount user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || !user.isActive()) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            if (attempts >= properties.maxFailedLogins()) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(now.plus(properties.lockoutDuration()));
                audit.record(null, user.getId(), "USER_LOCKED", "USER", user.getId(), Map.of("reason", "FAILED_LOGINS"));
            } else {
                user.setFailedLoginAttempts(attempts);
            }
            throw invalidCredentials();
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        return newSession(user, UUID.randomUUID(), now);
    }

    /**
     * Rotates a refresh token. Presenting a token that was already rotated means it was copied: the whole token family
     * is revoked, signing out every device that shares it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw invalidRefreshToken();
        Instant now = clock.instant();
        SfRefreshToken token = refreshTokens.findByTokenHash(SecretTokens.hash(rawToken)).orElseThrow(AuthService::invalidRefreshToken);
        if (token.getRevokedAt() != null) {
            if (SfRefreshToken.ROTATED.equals(token.getRevokeReason())) {
                refreshTokens.revokeFamily(token.getFamilyId(), SfRefreshToken.REUSE_DETECTED, now);
                audit.record(null, token.getUserId(), "REFRESH_TOKEN_REUSE_DETECTED", "USER", token.getUserId(), Map.of());
            }
            throw invalidRefreshToken();
        }
        if (!token.getExpiresAt().isAfter(now)) throw invalidRefreshToken();
        SfUserAccount user = users.findById(token.getUserId()).filter(SfUserAccount::isActive).orElseThrow(AuthService::invalidRefreshToken);

        token.revoke(SfRefreshToken.ROTATED, now);
        IssuedRefresh next = issueRefresh(user.getId(), token.getFamilyId(), now);
        token.setReplacedById(next.entity().getId());
        return new Session(accessTokens.issue(user), next.raw(), toResponse(user));
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        refreshTokens.findByTokenHash(SecretTokens.hash(rawToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), SfRefreshToken.LOGOUT, clock.instant()));
    }

    @Transactional
    public void requestPasswordReset(PasswordResetRequest request, String clientIp) {
        String email = normalize(request.email());
        rateLimiter.check("reset:ip:" + clientIp, 10, Duration.ofHours(1));
        rateLimiter.check("reset:email:" + email, 3, Duration.ofHours(1));
        users.findByEmailIgnoreCase(email).filter(SfUserAccount::isActive).ifPresent(user -> {
            Instant now = clock.instant();
            resetTokens.invalidateOpenTokens(user.getId(), now);
            String raw = SecretTokens.generate();
            SfPasswordResetToken token = new SfPasswordResetToken();
            token.setUserId(user.getId());
            token.setTokenHash(SecretTokens.hash(raw));
            token.setExpiresAt(now.plus(properties.passwordResetTtl()));
            resetTokens.save(token);
            // The token is in the URL fragment so it is not sent to servers or written to access logs.
            String url = properties.publicWebUrl() + "/reset-password#token=" + raw;
            AfterCommit.run(() -> emailSender.sendPasswordReset(user.getEmail(), url, user.getPreferredLanguage()));
        });
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirm request) {
        Instant now = clock.instant();
        SfPasswordResetToken token = resetTokens.findByTokenHash(SecretTokens.hash(request.token()))
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(() -> ApiException.badRequest("INVALID_RESET_TOKEN", "This reset link is invalid or has expired."));
        SfUserAccount user = users.findById(token.getUserId()).filter(SfUserAccount::isActive)
                .orElseThrow(() -> ApiException.badRequest("INVALID_RESET_TOKEN", "This reset link is invalid or has expired."));
        PasswordPolicy.check(request.newPassword(), user.getEmail());
        changePasswordInternal(user, request.newPassword(), now);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        token.setUsedAt(now);
        audit.record(null, user.getId(), "PASSWORD_RESET_COMPLETED", "USER", user.getId(), Map.of());
    }

    /** Signs the user out everywhere; the client must sign in again. */
    @Transactional
    public void changePassword(CurrentUser current, ChangePasswordRequest request) {
        SfUserAccount user = requireUser(current);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "The current password is incorrect.");
        }
        PasswordPolicy.check(request.newPassword(), user.getEmail());
        changePasswordInternal(user, request.newPassword(), clock.instant());
        audit.record(null, user.getId(), "PASSWORD_CHANGED", "USER", user.getId(), Map.of());
    }

    @Transactional(readOnly = true)
    public UserResponse me(CurrentUser current) { return toResponse(requireUser(current)); }

    @Transactional
    public UserResponse updateProfile(CurrentUser current, UpdateProfileRequest request) {
        SfUserAccount user = requireUser(current);
        if (request.displayName() != null) {
            if (request.displayName().isBlank()) throw ApiException.badRequest("VALIDATION_FAILED", "Display name is required.");
            user.setDisplayName(request.displayName().trim());
        }
        if (request.preferredLanguage() != null) user.setPreferredLanguage(request.preferredLanguage());
        return toResponse(user);
    }

    private void changePasswordInternal(SfUserAccount user, String newPassword, Instant now) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(now);
        refreshTokens.revokeAllForUser(user.getId(), SfRefreshToken.PASSWORD_CHANGED, now);
    }

    private Session newSession(SfUserAccount user, UUID familyId, Instant now) {
        IssuedRefresh refresh = issueRefresh(user.getId(), familyId, now);
        return new Session(accessTokens.issue(user), refresh.raw(), toResponse(user));
    }

    private record IssuedRefresh(String raw, SfRefreshToken entity) {}

    private IssuedRefresh issueRefresh(Long userId, UUID familyId, Instant now) {
        String raw = SecretTokens.generate();
        SfRefreshToken refresh = new SfRefreshToken();
        refresh.setUserId(userId);
        refresh.setFamilyId(familyId);
        refresh.setTokenHash(SecretTokens.hash(raw));
        refresh.setIssuedAt(now);
        refresh.setExpiresAt(now.plus(properties.refreshTokenTtl()));
        return new IssuedRefresh(raw, refreshTokens.save(refresh));
    }

    private SfUserAccount requireUser(CurrentUser current) {
        return users.findById(current.id()).filter(SfUserAccount::isActive)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Sign in is required."));
    }

    static UserResponse toResponse(SfUserAccount u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getPreferredLanguage(), u.isPlatformAdmin());
    }

    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect, or the account is temporarily locked.");
    }

    private static ApiException invalidRefreshToken() {
        return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Your session has ended. Please sign in again.");
    }
}
