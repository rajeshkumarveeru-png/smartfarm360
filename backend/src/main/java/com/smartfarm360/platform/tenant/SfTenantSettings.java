package com.smartfarm360.platform.tenant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "sf_tenant_settings")
@Getter @Setter @NoArgsConstructor
public class SfTenantSettings {
    @Id @Column(name = "tenant_id") private Long tenantId;
    @Column(nullable = false, length = 60) private String timezone = "Asia/Kolkata";
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode = "INR";
    @Column(name = "area_unit", nullable = false, length = 20) private String areaUnit = "ACRE";
    @Column(name = "weight_unit", nullable = false, length = 20) private String weightUnit = "KG";
    @Column(name = "volume_unit", nullable = false, length = 20) private String volumeUnit = "LITRE";
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public SfTenantSettings(Long tenantId) { this.tenantId = tenantId; }

    @PreUpdate void touch() { updatedAt = Instant.now(); }
}
