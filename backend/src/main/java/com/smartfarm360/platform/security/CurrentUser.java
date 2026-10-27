package com.smartfarm360.platform.security;

/** The authenticated user, resolved from the access token and re-validated against the database on every request. */
public record CurrentUser(long id, String email, boolean platformAdmin, boolean secondStep) {
    public CurrentUser(long id, String email, boolean platformAdmin) { this(id, email, platformAdmin, false); }
}
