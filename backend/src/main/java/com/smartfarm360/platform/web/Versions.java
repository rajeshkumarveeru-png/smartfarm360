package com.smartfarm360.platform.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Version tokens for day records (mobile offline design, N7). A token is a short hash of what the user can change on that
 * record (never of derived figures), so it changes exactly when someone else saved different values. {@link #NONE} stands
 * for "nothing stored yet". A save based on an older token is refused with {@code STALE_RECORD}.
 */
public final class Versions {
    public static final String NONE = "0";

    private Versions() {}

    public static String token(Object... parts) {
        StringBuilder s = new StringBuilder();
        for (Object p : parts) {
            Object v = p instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : p;
            s.append(v == null ? "∅" : v.toString()).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Refuses a save based on another version; {@code base} null means the caller does not check (older clients). */
    public static void requireCurrent(String base, String current) {
        if (base != null && !base.equals(current)) {
            throw ApiException.conflict("STALE_RECORD", "Someone else changed this since you opened it. Check their version first.");
        }
    }
}
