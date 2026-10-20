package com.smartfarm360.platform.membership;

import com.smartfarm360.platform.tenant.SfTenant;
import com.smartfarm360.platform.user.SfUserAccount;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "sf_tenant_memberships", uniqueConstraints = @UniqueConstraint(name = "uq_sf_tenant_membership", columnNames = {"tenant_id", "user_id"}))
@Getter @Setter @NoArgsConstructor
public class SfTenantMembership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private SfTenant tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private SfUserAccount user;
    @ManyToMany
    @JoinTable(name = "sf_membership_roles", joinColumns = @JoinColumn(name = "membership_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<SfRole> roles = new HashSet<>();
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public boolean hasRole(String code) { return roles.stream().anyMatch(r -> r.getCode().equals(code)); }

    @PreUpdate void touch() { updatedAt = Instant.now(); }
}
