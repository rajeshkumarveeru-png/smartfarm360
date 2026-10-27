package com.smartfarm360.platform.tenant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "sf_tenants")
@Getter @Setter @NoArgsConstructor
public class SfTenant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 140) private String name;
    @Column(nullable = false, unique = true, length = 100) private String slug;
    @Column(name = "preferred_language", nullable = false, length = 5) private String preferredLanguage = "en";
    @Column(nullable = false) private boolean active = true;
    /** The effective plan, kept by the subscription (billing design); PlanLimits reads it. */
    @Column(name = "plan_code", nullable = false, length = 30) private String planCode = "FREE";
    /** Written by the platform console and the deletion request (JDBC); read-only here. */
    @Column(name = "suspended_reason", insertable = false, updatable = false) private String suspendedReason;
    @Column(name = "deletion_scheduled_for", insertable = false, updatable = false) private Instant deletionScheduledFor;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }
}
