package com.smartfarm360.platform.security;

import com.smartfarm360.platform.user.SfUserAccount;
import com.smartfarm360.platform.user.SfUserAccountRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Turns a verified access token into a {@link CurrentUser}. Rejects tokens for users that were deactivated or that
 * were issued before the user's last password change, so those take effect immediately.
 */
@Component
public class AccessTokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final SfUserAccountRepository users;

    public AccessTokenAuthenticationConverter(SfUserAccountRepository users) { this.users = users; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("Invalid token subject");
        }
        SfUserAccount user = users.findById(userId).filter(SfUserAccount::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown or inactive user"));
        Instant issuedAt = jwt.getIssuedAt();
        // iat has second precision, so compare against the password change truncated to seconds.
        if (issuedAt == null || issuedAt.isBefore(user.getPasswordChangedAt().truncatedTo(ChronoUnit.SECONDS))) {
            throw new InvalidBearerTokenException("Token issued before the last password change");
        }
        return new CurrentUserAuthentication(new CurrentUser(user.getId(), user.getEmail(), user.isPlatformAdmin()), jwt);
    }
}
