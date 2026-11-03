package com.smartfarm360.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.support.ApiIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIntegrationTest extends ApiIntegrationTest {

    @Test
    void registerThenSignInReturnsTokensAndProfile() throws Exception {
        User user = newUser("farmer");
        get("/api/v1/auth/me", user).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.platformAdmin").value(false));
    }

    @Test
    void registeringAnExistingEmailLooksTheSameAndNotifiesTheOwner() throws Exception {
        User existing = newUser("existing");
        JsonNode first = body(post("/api/v1/auth/register", null,
                Map.of("email", uniqueEmail("fresh"), "password", PASSWORD, "displayName", "Fresh")).andExpect(status().isAccepted()));
        JsonNode duplicate = body(post("/api/v1/auth/register", null,
                Map.of("email", existing.email().toUpperCase(), "password", "another-password-123", "displayName", "Impostor"))
                .andExpect(status().isAccepted()));
        assertThat(duplicate).isEqualTo(first);
        assertThat(mail.to(existing.email())).anyMatch(s -> s.type().equals("REGISTRATION_ATTEMPT"));
        // The original password still works; the duplicate attempt changed nothing.
        login(existing.email(), PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() throws Exception {
        User user = newUser("generic");
        JsonNode wrong = body(post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", "not-the-password"))
                .andExpect(status().isUnauthorized()));
        JsonNode unknown = body(post("/api/v1/auth/login", null, Map.of("email", uniqueEmail("nobody"), "password", "not-the-password"))
                .andExpect(status().isUnauthorized()));
        assertThat(wrong.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknown.get("code")).isEqualTo(wrong.get("code"));
        assertThat(unknown.get("detail")).isEqualTo(wrong.get("detail"));
    }

    @Test
    void fiveFailedSignInsLockTheAccountEvenForTheCorrectPassword() throws Exception {
        User user = newUser("lockout");
        for (int i = 0; i < 5; i++) {
            post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", "wrong-password-" + i)).andExpect(status().isUnauthorized());
        }
        post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select locked_until is not null from sf_users where id = ?", Boolean.class, user.id())).isTrue();

        jdbc.update("update sf_users set locked_until = now() - interval '1 minute' where id = ?", user.id());
        login(user.email(), PASSWORD);
        assertThat(jdbc.queryForObject("select failed_login_attempts from sf_users where id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void refreshRotatesAndReusingAnOldTokenRevokesTheWholeFamily() throws Exception {
        User user = newUser("rotate");
        String r1 = body(post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD, "refreshTokenDelivery", "BODY")))
                .get("refreshToken").asText();
        String r2 = body(post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1)).andExpect(status().isOk())).get("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);

        // More than 30 seconds after its rotation, a reused token is treated as stolen (O13)
        jdbc.update("update sf_refresh_tokens set revoked_at = revoked_at - interval '31 seconds' where user_id = ? and revoke_reason = 'ROTATED'", user.id());
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        // r2 was never used, but it belongs to the compromised family.
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", r2)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from sf_refresh_tokens where user_id = ? and revoke_reason = 'REUSE_DETECTED'",
                Integer.class, user.id())).isPositive();
    }

    @Test
    void twoTabsRefreshingTogetherStaySignedInButALoggedOutFamilyStaysEnded() throws Exception {
        User user = newUser("two-tabs");
        String r1 = body(post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD, "refreshTokenDelivery", "BODY")))
                .get("refreshToken").asText();
        String tabA = body(post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1)).andExpect(status().isOk())).get("refreshToken").asText();
        // The second tab sends the same token a moment later: it gets its own successor, and nothing is revoked
        String tabB = body(post("/api/v1/auth/refresh", null, Map.of("refreshToken", r1)).andExpect(status().isOk())).get("refreshToken").asText();
        assertThat(tabB).isNotEqualTo(tabA);
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", tabA)).andExpect(status().isOk());
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", tabB)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from sf_refresh_tokens where user_id = ? and revoke_reason = 'REUSE_DETECTED'",
                Integer.class, user.id())).isZero();
        // After sign-out the grace never reopens the family
        String r3 = body(post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD, "refreshTokenDelivery", "BODY")))
                .get("refreshToken").asText();
        String r4 = body(post("/api/v1/auth/refresh", null, Map.of("refreshToken", r3)).andExpect(status().isOk())).get("refreshToken").asText();
        post("/api/v1/auth/logout", null, Map.of("refreshToken", r4)).andExpect(status().isNoContent());
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", r3)).andExpect(status().isUnauthorized());
    }

    @Test
    void cookieRefreshNeedsTheCustomHeaderAndSetsAHardenedCookie() throws Exception {
        User user = newUser("cookie");
        MvcResult loginResult = post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andReturn();
        Cookie cookie = loginResult.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(cookie).isNotNull();

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/refresh").cookie(cookie)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFRESH_HEADER_REQUIRED"));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/refresh").cookie(cookie).header("X-SF360-Refresh", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists(AuthController.REFRESH_COOKIE));
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        User user = newUser("logout");
        String refresh = body(post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD, "refreshTokenDelivery", "BODY")))
                .get("refreshToken").asText();
        post("/api/v1/auth/logout", null, Map.of("refreshToken", refresh)).andExpect(status().isNoContent());
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRequireAValidBearerToken() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        get("/api/v1/tenants", new User(0, "x", "not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetChangesThePasswordAndEndsExistingSessions() throws Exception {
        User user = newUser("reset");
        String refresh = body(post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD, "refreshTokenDelivery", "BODY")))
                .get("refreshToken").asText();
        Thread.sleep(1100); // access tokens have second precision; ensure the old one predates the change

        post("/api/v1/auth/password-reset/request", null, Map.of("email", user.email())).andExpect(status().isAccepted());
        String token = mail.lastToken(user.email(), "RESET");
        assertThat(jdbc.queryForObject("select count(*) from sf_password_reset_tokens where token_hash = ?", Integer.class, token))
                .as("only the hash is stored").isZero();

        post("/api/v1/auth/password-reset/confirm", null, Map.of("token", token, "newPassword", "a-brand-new-password"))
                .andExpect(status().isNoContent());

        post("/api/v1/auth/login", null, Map.of("email", user.email(), "password", PASSWORD)).andExpect(status().isUnauthorized());
        login(user.email(), "a-brand-new-password");
        get("/api/v1/auth/me", user).andExpect(status().isUnauthorized());
        post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).andExpect(status().isUnauthorized());
        // Single use
        post("/api/v1/auth/password-reset/confirm", null, Map.of("token", token, "newPassword", "yet-another-password"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void passwordResetForUnknownEmailIsAcceptedButSendsNothing() throws Exception {
        String email = uniqueEmail("ghost");
        post("/api/v1/auth/password-reset/request", null, Map.of("email", email)).andExpect(status().isAccepted());
        assertThat(mail.to(email)).isEmpty();
    }

    @Test
    void passwordPolicyIsEnforcedIncludingTheBcryptByteLimit() throws Exception {
        post("/api/v1/auth/register", null, Map.of("email", uniqueEmail("short"), "password", "short", "displayName", "S"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
        String tamil = "விவசாயம்விவசாயம்விவசாயம்விவசாயம்"; // 32 characters, 96 UTF-8 bytes
        post("/api/v1/auth/register", null, Map.of("email", uniqueEmail("tamil"), "password", tamil, "displayName", "T"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    void changePasswordRequiresTheCurrentPassword() throws Exception {
        User user = newUser("change");
        post("/api/v1/auth/me/password", user, Map.of("currentPassword", "wrong-current", "newPassword", "a-new-password-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        post("/api/v1/auth/me/password", user, Map.of("currentPassword", PASSWORD, "newPassword", "a-new-password-1"))
                .andExpect(status().isNoContent());
        login(user.email(), "a-new-password-1");
    }

    @Test
    void profileLanguageCanBeChanged() throws Exception {
        User user = newUser("lang");
        patch("/api/v1/auth/me", user, Map.of("preferredLanguage", "ta")).andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value("ta"));
        patch("/api/v1/auth/me", user, Map.of("preferredLanguage", "fr")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("preferredLanguage"));
    }

    @Test
    void signInIsRateLimitedPerEmail() throws Exception {
        String email = uniqueEmail("limited");
        for (int i = 0; i < 10; i++) {
            post("/api/v1/auth/login", null, Map.of("email", email, "password", "whatever-password")).andExpect(status().isUnauthorized());
        }
        post("/api/v1/auth/login", null, Map.of("email", email, "password", "whatever-password"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void responsesCarryACorrelationId() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health").header("X-Correlation-Id", "test-correlation-1234"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "test-correlation-1234"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
