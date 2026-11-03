package com.smartfarm360.platform.auth;

import com.smartfarm360.platform.web.ApiException;

import java.nio.charset.StandardCharsets;

/** Minimum 10 characters; at most 72 bytes because BCrypt ignores anything longer (Tamil letters take 3 bytes each). */
public final class PasswordPolicy {
    public static final int MIN_CHARS = 10;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    public static void check(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_CHARS) {
            throw ApiException.badRequest("PASSWORD_TOO_SHORT", "Password must be at least " + MIN_CHARS + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "Password is too long.");
        }
        if (password.isBlank() || (email != null && password.equalsIgnoreCase(email))) {
            throw ApiException.badRequest("PASSWORD_TOO_WEAK", "Choose a password that is not your email address.");
        }
    }
}
