package com.smartfarm360.platform.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class CurrentUserAuthentication extends AbstractAuthenticationToken {
    private final CurrentUser user;
    private final Jwt jwt;

    public CurrentUserAuthentication(CurrentUser user, Jwt jwt) {
        super(user.platformAdmin() ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")) : List.of());
        this.user = user;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override public Object getCredentials() { return jwt; }
    @Override public CurrentUser getPrincipal() { return user; }
    @Override public String getName() { return String.valueOf(user.id()); }
}
