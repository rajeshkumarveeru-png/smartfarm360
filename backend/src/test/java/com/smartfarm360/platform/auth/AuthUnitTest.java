package com.smartfarm360.platform.auth;

import com.smartfarm360.platform.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthUnitTest {
    @Test
    void passwordPolicyCountsCharactersButLimitsBytes() {
        PasswordPolicy.check("ten-chars!", "a@b.com");
        assertThatThrownBy(() -> PasswordPolicy.check("nine-char", "a@b.com"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PASSWORD_TOO_SHORT");
        // 10 Tamil letters = 30 bytes: allowed
        PasswordPolicy.check("விவசாயம்விவ", null);
        assertThatThrownBy(() -> PasswordPolicy.check("x".repeat(73), null)).extracting("code").isEqualTo("PASSWORD_TOO_LONG");
        assertThatThrownBy(() -> PasswordPolicy.check("farmer@example.com", "Farmer@Example.com")).extracting("code").isEqualTo("PASSWORD_TOO_WEAK");
    }

    @Test
    void secretTokensAreRandomAndOnlyTheirHashIsDeterministic() {
        String a = SecretTokens.generate();
        String b = SecretTokens.generate();
        assertThat(a).isNotEqualTo(b).hasSizeGreaterThanOrEqualTo(43);
        assertThat(SecretTokens.hash(a)).hasSize(64).isEqualTo(SecretTokens.hash(a)).isNotEqualTo(SecretTokens.hash(b));
    }
}
