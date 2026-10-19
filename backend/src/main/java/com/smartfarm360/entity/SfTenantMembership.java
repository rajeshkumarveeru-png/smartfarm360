package com.smartfarm360.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "sf_tenant_memberships", uniqueConstraints = @UniqueConstraint(name = "uq_sf_tenant_membership", columnNames = {"tenant_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class SfTenantMembership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private SfTenant tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private SfUserAccount user;
    @Column(nullable = false, length = 30) private String role;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
