package com.smartfarm360.platform.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TenantDtos {
    private TenantDtos() {}

    /** {@code modules}: optional farming modules to switch on; defaults to CROP. */
    public record CreateTenantRequest(@NotBlank @Size(max = 140) String name,
                                      @Pattern(regexp = "en|ta") String preferredLanguage,
                                      List<FarmModule> modules) {}

    public record UpdateTenantRequest(@Size(min = 1, max = 140) String name, @Pattern(regexp = "en|ta") String preferredLanguage) {}

    /** A tenant as seen by one of its members, including what that member may do. */
    public record TenantSummary(long id, String name, String slug, String preferredLanguage, boolean active,
                                List<String> roles, List<String> permissions, List<String> enabledModules, Instant createdAt) {}

    public record SettingsResponse(String timezone, String currencyCode, String areaUnit, String weightUnit, String volumeUnit) {}

    public record SettingsRequest(@NotBlank @Size(max = 60) String timezone,
                                  @NotBlank @Pattern(regexp = "INR") String currencyCode,
                                  @NotBlank @Pattern(regexp = "ACRE|HECTARE|CENT|SQFT") String areaUnit,
                                  @NotBlank @Pattern(regexp = "KG|QUINTAL|TONNE") String weightUnit,
                                  @NotBlank @Pattern(regexp = "LITRE") String volumeUnit) {}

    public record ModuleStatus(String code, boolean enabled, boolean available) {}

    public record UpdateModuleRequest(@NotNull Boolean enabled) {}

    public record TenantDetail(long id, String name, String slug, String preferredLanguage, String planCode, boolean active,
                               Instant createdAt, SettingsResponse settings, List<ModuleStatus> modules) {}
}
