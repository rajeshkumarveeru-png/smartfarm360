package com.smartfarm360.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts two-step sign-in secrets at rest with AES-GCM and the key in {@code APP_MFA_ENCRYPTION_KEY} (O6).
 * Production requires the key (ProductionSettings). Without it (development) a fixed development key is used and a
 * warning is logged.
 */
@Component
public class SecretBox {
    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(@Value("${app.mfa.encryption-key:}") String configured) {
        byte[] bytes;
        if (configured == null || configured.isBlank()) {
            log.warn("APP_MFA_ENCRYPTION_KEY is not set: two-step secrets use a development key. Never run production like this.");
            bytes = sha256("smartfarm360-development-only-mfa-key");
        } else {
            bytes = Base64.getDecoder().decode(configured.trim());
            if (bytes.length != 32) throw new IllegalStateException("APP_MFA_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String seal(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String open(String sealedText) {
        try {
            byte[] all = Base64.getDecoder().decode(sealedText);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("A two-step secret could not be read; was APP_MFA_ENCRYPTION_KEY changed?", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
