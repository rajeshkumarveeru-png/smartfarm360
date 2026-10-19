package com.smartfarm360.dto;
import java.math.BigDecimal;
import java.time.Instant;
public final class SfResponses {
    private SfResponses() {}
    public record Tenant(Long id, String name, String slug, String preferredLanguage, String role, Instant createdAt) {}
    public record Farm(Long id, Long tenantId, String name, String farmType, String village, String district, String state, String country, boolean active, Instant createdAt) {}
    public record LandParcel(Long id, Long tenantId, Long farmId, String name, BigDecimal area, String areaUnit, String soilType, String irrigationType, boolean active, Instant createdAt) {}
}
