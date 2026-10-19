package com.smartfarm360.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class SfRequests {
    private SfRequests() {}
    public record CreateTenant(@NotBlank @Size(max = 140) String name, @Pattern(regexp = "(?i)en|ta") String preferredLanguage) {}
    public record CreateFarm(@NotBlank @Size(max = 140) String name, @Pattern(regexp = "CROP|DAIRY|POULTRY|GOAT_SHEEP|AQUACULTURE|ORGANIC|MIXED|OTHER") String farmType, @Size(max = 120) String village, @Size(max = 120) String district, @Size(max = 120) String state) {}
    public record CreateLandParcel(@NotBlank @Size(max = 120) String name, @NotNull @DecimalMin(value = "0.001") @Digits(integer = 9, fraction = 3) BigDecimal area, @Pattern(regexp = "ACRE|HECTARE|CENT|SQFT") String areaUnit, @Size(max = 80) String soilType, @Size(max = 80) String irrigationType) {}
}
