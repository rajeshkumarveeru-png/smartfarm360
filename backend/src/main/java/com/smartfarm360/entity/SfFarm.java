package com.smartfarm360.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "sf_farms", indexes = @Index(name = "ix_sf_farm_tenant", columnList = "tenant_id"))
@Getter @Setter @NoArgsConstructor
public class SfFarm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id", nullable = false) private SfTenant tenant;
    @Column(nullable = false, length = 140) private String name;
    @Column(name = "farm_type", nullable = false, length = 40) private String farmType = "MIXED";
    @Column(length = 120) private String village;
    @Column(length = 120) private String district;
    @Column(length = 120) private String state = "Tamil Nadu";
    @Column(nullable = false, length = 80) private String country = "India";
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
