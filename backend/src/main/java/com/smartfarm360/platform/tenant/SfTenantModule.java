package com.smartfarm360.platform.tenant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "sf_tenant_modules")
@IdClass(SfTenantModule.Key.class)
@Getter @Setter @NoArgsConstructor
public class SfTenantModule {
    @Id @Column(name = "tenant_id") private Long tenantId;
    @Id @Enumerated(EnumType.STRING) @Column(name = "module_code", length = 30) private FarmModule module;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public SfTenantModule(Long tenantId, FarmModule module, boolean enabled) {
        this.tenantId = tenantId;
        this.module = module;
        this.enabled = enabled;
    }

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public record Key(Long tenantId, FarmModule module) implements Serializable {
        public Key() { this(null, null); }
    }
}
