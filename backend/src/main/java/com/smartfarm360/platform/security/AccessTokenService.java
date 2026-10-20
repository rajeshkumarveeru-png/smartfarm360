package com.smartfarm360.platform.security;

import com.smartfarm360.platform.user.SfUserAccount;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

/** Issues and verifies short-lived HS256 access tokens. The subject is the user id; no other personal data is included. */
@Configuration
public class AccessTokenService {
    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);
    private final AuthProperties properties;
    private final SecretKey key;
    private final Clock clock;

    public AccessTokenService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = secretKey(properties.jwtSecret());
    }

    private static SecretKey secretKey(String configured) {
        byte[] bytes;
        if (configured == null || configured.isBlank()) {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            log.warn("APP_JWT_SECRET is not set: using a random signing key. Sessions will not survive a restart.");
        } else {
            bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) throw new IllegalStateException("APP_JWT_SECRET must be at least 32 bytes long");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public IssuedAccessToken issue(SfUserAccount user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder().encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, properties.accessTokenTtl().toSeconds());
    }

    public record IssuedAccessToken(String value, long expiresInSeconds) {}
}
