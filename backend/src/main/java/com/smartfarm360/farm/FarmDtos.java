package com.smartfarm360.farm;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public final class FarmDtos {
    private FarmDtos() {}

    static final String FARM_TYPES = "CROP|DAIRY|POULTRY|GOAT_SHEEP|AQUACULTURE|ORGANIC|MIXED|OTHER";
    static final String AREA_UNITS = "ACRE|HECTARE|CENT|SQFT";

    public record FarmRequest(@NotBlank @Size(max = 140) String name,
                              @Pattern(regexp = FARM_TYPES) String farmType,
                              @Size(max = 120) String village,
                              @Size(max = 120) String district,
                              @Size(max = 120) String state,
                              @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal area,
                              @Pattern(regexp = AREA_UNITS) String areaUnit) {}

    /** Partial update: null fields are left unchanged. */
    public record FarmPatch(@Size(min = 1, max = 140) String name,
                            @Pattern(regexp = FARM_TYPES) String farmType,
                            @Size(max = 120) String village,
                            @Size(max = 120) String district,
                            @Size(max = 120) String state) {}

    public record LandParcelRequest(@NotBlank @Size(max = 120) String name,
                                    @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal area,
                                    @Pattern(regexp = AREA_UNITS) String areaUnit,
                                    @Size(max = 80) String soilType,
                                    @Size(max = 80) String irrigationType) {}

    public record LandParcelPatch(@Size(min = 1, max = 120) String name,
                                  @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal area,
                                  @Pattern(regexp = AREA_UNITS) String areaUnit,
                                  @Size(max = 80) String soilType,
                                  @Size(max = 80) String irrigationType) {}

    public record Farm(Long id, Long tenantId, String name, String farmType, String village, String district, String state,
                       String country, boolean active, Instant createdAt, String areaSummary) {}

    public record LandParcel(Long id, Long tenantId, Long farmId, String name, BigDecimal area, String areaUnit, String soilType,
                             String irrigationType, boolean active, Instant createdAt) {}
}
