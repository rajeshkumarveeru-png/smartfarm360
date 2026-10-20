package com.smartfarm360.farm;

import com.smartfarm360.platform.tenant.SfTenant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sf_land_parcels", indexes = @Index(name = "ix_sf_parcel_tenant_farm", columnList = "tenant_id,farm_id"))
@Getter @Setter @NoArgsConstructor
public class SfLandParcel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private SfTenant tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "farm_id", nullable = false) private SfFarm farm;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, precision = 12, scale = 3) private BigDecimal area;
    @Column(name = "area_unit", nullable = false, length = 20) private String areaUnit = "ACRE";
    @Column(name = "soil_type", length = 80) private String soilType;
    @Column(name = "irrigation_type", length = 80) private String irrigationType;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }
}
