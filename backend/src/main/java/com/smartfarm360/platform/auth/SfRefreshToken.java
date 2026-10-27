package com.smartfarm360.platform.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sf_refresh_tokens")
@Getter @Setter @NoArgsConstructor
public class SfRefreshToken {
    public static final String ROTATED = "ROTATED";
    public static final String LOGOUT = "LOGOUT";
    public static final String REUSE_DETECTED = "REUSE_DETECTED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "family_id", nullable = false) private UUID familyId;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "issued_at", nullable = false) private Instant issuedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "revoke_reason", length = 30) private String revokeReason;
    @Column(name = "replaced_by_id") private Long replacedById;
    /** The session was opened with the second sign-in step (O6); its access tokens say so. */
    @Column(nullable = false) private boolean mfa;

    public void revoke(String reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }
}
