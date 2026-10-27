package com.smartfarm360.platform.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Two-step codes match RFC 6238 and authenticator apps (O6). */
class TotpTest {
    // RFC 6238 appendix B, SHA-1 secret "12345678901234567890"
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void codesMatchTheRfcTestVectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(59))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(1234567890))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(2000000000))).isEqualTo("279037");
    }

    @Test
    void aCodeIsAcceptedOneStepEitherSideOnly() {
        long now = 1_800_000_000L;
        String current = Totp.code(RFC_SECRET, Totp.step(now));
        assertThat(Totp.matchingStep(RFC_SECRET, current, now)).isEqualTo(Totp.step(now));
        assertThat(Totp.matchingStep(RFC_SECRET, current, now + 30)).isEqualTo(Totp.step(now));
        assertThat(Totp.matchingStep(RFC_SECRET, current, now + 90)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, "12345", now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, null, now)).isEqualTo(-1);
    }

    @Test
    void secretsRoundTripThroughBase32AndTheSecretBox() {
        String secret = Totp.newSecret();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(Totp.base32(Totp.decode(secret))).isEqualTo(secret);
        SecretBox box = new SecretBox(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        String sealed = box.seal(secret);
        assertThat(sealed).doesNotContain(secret);
        assertThat(box.seal(secret)).isNotEqualTo(sealed);   // a new nonce every time
        assertThat(box.open(sealed)).isEqualTo(secret);
        assertThat(Totp.otpauthUrl(secret, "a b@example.com")).isEqualTo("otpauth://totp/SmartFarm360%3Aa%20b%40example.com?secret=" + secret
                + "&issuer=SmartFarm360&digits=6&period=30");
    }
}
