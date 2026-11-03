package com.smartfarm360.platform.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Time-based one-time codes (RFC 6238: HMAC-SHA1, 6 digits, 30-second steps), as used by authenticator apps
 * (SaaS operations design, O6). A code is accepted for the current step and one step either side.
 */
public final class Totp {
    public static final long STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new 160-bit secret, base32 (what authenticator apps expect). */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    public static long step(long epochSeconds) { return Math.floorDiv(epochSeconds, STEP_SECONDS); }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The step the code matches (now, or one step either side), or -1. */
    public static long matchingStep(String base32Secret, String code, long epochSeconds) {
        if (code == null || !code.trim().matches("\\d{6}")) return -1;
        long now = step(epochSeconds);
        for (long s = now - 1; s <= now + 1; s++) {
            if (java.security.MessageDigest.isEqual(code(base32Secret, s).getBytes(), code.trim().getBytes())) return s;
        }
        return -1;
    }

    /** The link an authenticator app reads from a QR code. */
    public static String otpauthUrl(String base32Secret, String accountEmail) {
        String label = java.net.URLEncoder.encode("SmartFarm360:" + accountEmail, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=SmartFarm360&digits=6&period=30";
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] decode(String base32) {
        String s = base32.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
