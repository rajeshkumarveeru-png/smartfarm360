package com.smartfarm360.crop;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Crop request bodies. Field names match the earlier map-based API so existing clients keep working. */
public final class CropDtos {
    private CropDtos() {}

    public record CropRequest(@NotBlank @Size(max = 120) String name, @Size(max = 120) String localName,
                              @Size(max = 60) String category, @Size(max = 120) String description) {}

    public record VarietyRequest(@NotBlank @Size(max = 120) String name, @Size(max = 120) String localName,
                                 @Positive @Max(3650) Integer durationDays, @Size(max = 120) String notes) {}

    public record SeasonRequest(@NotBlank @Size(max = 120) String name, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                @Size(max = 40) String seasonType) {}

    public record CycleRequest(@NotNull Long farmId, @NotNull Long landParcelId, @NotNull Long cropId, Long varietyId, Long seasonId,
                               @NotBlank @Size(max = 120) String name, @NotNull LocalDate plannedSowingDate,
                               LocalDate expectedHarvestDate,
                               @Positive @Digits(integer = 9, fraction = 3) BigDecimal cultivatedArea,
                               @Pattern(regexp = "ACRE|HECTARE|CENT|SQFT") String areaUnit,
                               @Pattern(regexp = "PLANNED|IN_PROGRESS|COMPLETED|CANCELLED") String status) {}

    public record ActivityRequest(@NotNull Long cycleId,
                                  @NotNull @Pattern(regexp = "LAND_PREPARATION|SOWING|TRANSPLANTING|FERTILIZER|PESTICIDE|IRRIGATION|WEEDING|OTHER") String activityType,
                                  @NotNull LocalDate activityDate, @Size(max = 500) String notes, @Size(max = 120) String inputName,
                                  @PositiveOrZero @Digits(integer = 10, fraction = 3) BigDecimal quantity,
                                  @Size(max = 40) String quantityUnit,
                                  @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal cost) {}

    public record HarvestRequest(@NotNull Long cycleId, @NotNull LocalDate harvestDate,
                                 @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 3) BigDecimal quantity,
                                 @NotBlank @Size(max = 40) String quantityUnit,
                                 @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal saleValue,
                                 @Size(max = 500) String notes) {}
}
