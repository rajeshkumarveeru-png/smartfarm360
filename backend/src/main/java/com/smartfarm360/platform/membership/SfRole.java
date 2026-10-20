package com.smartfarm360.platform.membership;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.HashSet;
import java.util.Set;

/** A system role (tenant_id NULL) seeded by Liquibase. Read-only from the application. */
@Entity
@Table(name = "sf_roles")
@Getter @NoArgsConstructor
public class SfRole {
    @Id private Long id;
    @Column(name = "tenant_id", insertable = false, updatable = false) private Long tenantId;
    @Column(nullable = false, length = 40, insertable = false, updatable = false) private String code;
    @Column(nullable = false, length = 120, insertable = false, updatable = false) private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sf_role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", length = 80)
    private Set<String> permissions = new HashSet<>();
}
