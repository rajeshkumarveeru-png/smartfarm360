package com.smartfarm360.platform.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "sf_users", uniqueConstraints = @UniqueConstraint(name = "uq_sf_user_email", columnNames = "email"))
@Getter @Setter @NoArgsConstructor
public class SfUserAccount {
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 140) private String displayName;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "platform_role", length = 30) private String platformRole;
    @Column(name = "preferred_language", nullable = false, length = 5) private String preferredLanguage = "en";
    @Column(name = "failed_login_attempts", nullable = false) private int failedLoginAttempts;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "password_changed_at", nullable = false) private Instant passwordChangedAt = Instant.now();
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public boolean isPlatformAdmin() { return PLATFORM_ADMIN.equals(platformRole); }

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }

    @PreUpdate void touch() { updatedAt = Instant.now(); }
}
