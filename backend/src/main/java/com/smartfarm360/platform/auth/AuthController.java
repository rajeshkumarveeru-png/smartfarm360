package com.smartfarm360.platform.auth;

import com.smartfarm360.platform.auth.AuthDtos.*;
import com.smartfarm360.platform.security.AuthProperties;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.SecurityConfig;
import com.smartfarm360.platform.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static final String REFRESH_COOKIE = "sf360_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";
    private final AuthService auth;
    private final AuthProperties properties;

    private final EmailVerificationService verification;
    private final TwoStepService twoStep;

    public AuthController(AuthService auth, AuthProperties properties, EmailVerificationService verification, TwoStepService twoStep) {
        this.verification = verification;
        this.twoStep = twoStep;
        this.auth = auth;
        this.properties = properties;
    }

    /** Always 202, whether or not the email already has an account. The client then signs in. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        auth.register(request, http.getRemoteAddr());
        return AcceptedResponse.ACCEPTED;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.Session session = auth.login(request, http.getRemoteAddr());
        return respond(session, "BODY".equals(request.refreshTokenDelivery()));
    }

    @PostMapping("/login/second-step")
    public ResponseEntity<TokenResponse> secondStep(@Valid @RequestBody SecondStepRequest request, HttpServletRequest http) {
        return respond(auth.completeSecondStep(request, http.getRemoteAddr()), "BODY".equals(request.refreshTokenDelivery()));
    }

    /** Starts two-step sign-in: the secret and the link for an authenticator app (O6). */
    @PostMapping("/me/two-step/setup")
    public TwoStepService.Setup twoStepSetup(CurrentUser user, @Valid @RequestBody PasswordConfirm request) { return twoStep.setup(user, request.password()); }

    /** Confirms it with a code; the recovery codes are shown once. */
    @PostMapping("/me/two-step/enable")
    public TwoStepService.RecoveryCodes twoStepEnable(CurrentUser user, @Valid @RequestBody TwoStepCode request) { return twoStep.enable(user, request.code()); }

    @PostMapping("/me/two-step/recovery-codes")
    public TwoStepService.RecoveryCodes twoStepRecoveryCodes(CurrentUser user, @Valid @RequestBody PasswordConfirm request) {
        return twoStep.regenerate(user, request.password());
    }

    @PostMapping("/me/two-step/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void twoStepDisable(CurrentUser user, @Valid @RequestBody TwoStepOff request) { twoStep.disable(user, request.password(), request.code()); }

    /**
     * Mobile sends {@code {"refreshToken": "..."}}. The web app relies on the HttpOnly cookie and must send the
     * {@value SecurityConfig#REFRESH_HEADER} header, which cross-site forms cannot add.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody(required = false) RefreshRequest body,
                                                 @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
                                                 @RequestHeader(name = SecurityConfig.REFRESH_HEADER, required = false) String header) {
        if (body != null && body.refreshToken() != null) {
            return respond(auth.refresh(body.refreshToken()), true);
        }
        requireRefreshHeader(cookie, header);
        return respond(auth.refresh(cookie), false);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) RefreshRequest body,
                                       @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
                                       @RequestHeader(name = SecurityConfig.REFRESH_HEADER, required = false) String header) {
        if (body != null && body.refreshToken() != null) {
            auth.logout(body.refreshToken());
        } else if (cookie != null) {
            requireRefreshHeader(cookie, header);
            auth.logout(cookie);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedResponse requestReset(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest http) {
        auth.requestPasswordReset(request, http.getRemoteAddr());
        return AcceptedResponse.ACCEPTED;
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody PasswordResetConfirm request) {
        auth.confirmPasswordReset(request);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) { verification.verify(request.token()); }

    /** Sends a new verification link (at most 3 an hour). */
    @PostMapping("/me/verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedResponse resendVerification(CurrentUser user) {
        verification.resend(user);
        return AcceptedResponse.ACCEPTED;
    }

    @GetMapping("/me")
    public UserResponse me(CurrentUser user) { return auth.me(user); }

    @PatchMapping("/me")
    public UserResponse updateMe(CurrentUser user, @Valid @RequestBody UpdateProfileRequest request) {
        return auth.updateProfile(user, request);
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(CurrentUser user, @Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(user, request);
    }

    private static void requireRefreshHeader(String cookie, String header) {
        if (cookie == null) throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Your session has ended. Please sign in again.");
        if (!"1".equals(header)) throw ApiException.badRequest("REFRESH_HEADER_REQUIRED", "Missing " + SecurityConfig.REFRESH_HEADER + " header.");
    }

    private ResponseEntity<TokenResponse> respond(AuthService.Session session, boolean refreshInBody) {
        if (session.secondStepChallenge() != null) {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new TokenResponse(null, null, 0, null, null, session.secondStepChallenge()));
        }
        TokenResponse body = new TokenResponse(session.accessToken().value(), "Bearer", session.accessToken().expiresInSeconds(),
                refreshInBody ? session.refreshToken() : null, session.user());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!refreshInBody) builder.header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), properties.refreshTokenTtl()).toString());
        return builder.body(body);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.refreshCookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
