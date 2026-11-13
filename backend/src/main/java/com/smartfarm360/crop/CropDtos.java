package com.smartfarm360.crop;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Crop API records (crop management design, approved 2026-10-20). Request field names match the Phase 1 API. */
public final class CropDtos {
    private CropDtos() {}

    public static final String AREA_UNITS = "ACRE|HECTARE|CENT|SQFT";

    // ---- Crops, varieties, seasons ----

    public record CropRequest(@NotBlank @Size(max = 120) String name, @Size(max = 120) String localName,
                              @Size(max = 60) String category, @Size(max = 120) String description) {}

    public record CropPatch(@Size(min = 1, max = 120) String name, @Size(max = 120) String localName, @Size(max = 60) String category,
                            @Size(max = 120) String description, Boolean active) {}

    /** {@code produceItemId}: the stock item its harvests go into. */
    public record Crop(long id, String name, String localName, String category, String description, Long produceItemId, boolean active) {}

    public record VarietyRequest(@NotBlank @Size(max = 120) String name, @Size(max = 120) String localName,
                                 @Positive @Max(3650) Integer durationDays, @Size(max = 120) String notes) {}

    public record Variety(long id, long cropId, String name, String localName, Integer durationDays, String notes, boolean active) {}

    public record SeasonRequest(@NotBlank @Size(max = 120) String name, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                @Size(max = 40) String seasonType) {}

    public record Season(long id, String name, LocalDate startDate, LocalDate endDate, String seasonType, boolean active) {}

    // ---- Cycles ----

    /** A new cycle is always Planned (F1); {@code status} is accepted for older clients and must be PLANNED if given. */
    public record CycleRequest(@NotNull Long farmId, @NotNull Long landParcelId, @NotNull Long cropId, Long varietyId, Long seasonId,
                               @NotBlank @Size(max = 120) String name, @NotNull LocalDate plannedSowingDate,
                               LocalDate expectedHarvestDate,
                               @Positive @Digits(integer = 9, fraction = 3) BigDecimal cultivatedArea,
                               @Pattern(regexp = AREA_UNITS) String areaUnit,
                               @Positive @Digits(integer = 14, fraction = 4) BigDecimal expectedYield,
                               @Pattern(regexp = "PLANNED") String status) {}

    public record CyclePatch(@Size(min = 1, max = 120) String name, Long varietyId, Long seasonId, LocalDate plannedSowingDate,
                             LocalDate expectedHarvestDate, @Positive @Digits(integer = 9, fraction = 3) BigDecimal cultivatedArea,
                             @Pattern(regexp = AREA_UNITS) String areaUnit, @Positive @Digits(integer = 14, fraction = 4) BigDecimal expectedYield) {}

    /** START (sowing done), CLOSE, REOPEN (owners and farm admins), CANCEL (only without records). */
    public record StatusRequest(@NotNull @Pattern(regexp = "START|CLOSE|REOPEN|CANCEL") String action, LocalDate date) {}

    /**
     * {@code warnings}: FIELD_OVERCOMMITTED when cycles growing on the same field at the same time need more area than
     * it has (intercropping is allowed, F11).
     */
    public record Cycle(long id, long farmId, String farmName, long landParcelId, String parcelName, BigDecimal parcelArea, String parcelAreaUnit,
                        long cropId, String cropName, Long varietyId, String varietyName, Long seasonId, String seasonName, String name,
                        LocalDate plannedSowingDate, LocalDate actualSowingDate, LocalDate expectedHarvestDate, LocalDate closedOn,
                        BigDecimal cultivatedArea, String areaUnit, BigDecimal areaAcres, BigDecimal expectedYield, String status,
                        Long costObjectId, Long daysSinceSowing, int harvestCount, List<String> warnings) {}

    // ---- Scheduled field actions ----

    public record ActionPlanRequest(@NotNull @Pattern(regexp = "WEEDING|IRRIGATION|PESTICIDE|FERTILIZER|LAND_PREPARATION|OTHER") String actionType,
                                    @NotBlank @Size(max = 300) String description, @NotNull LocalDate dueOn) {}
    public record CompleteActionRequest(@NotNull LocalDate completedOn, LocalDate nextDue) {}
    public record ActionPlanItem(long id, long cycleId, String cycleName, String cropName, String actionType,
                                 String description, LocalDate dueOn, LocalDate completedOn, Long nextItemId) {}
    public record CropActionDue(long cycleId, String cycleName, String cropName, String actionType,
                                String description, LocalDate dueOn) {}

    // ---- Activities and harvests (recording is in the operations module) ----

    public record ActivityPatch(LocalDate activityDate, @Size(max = 500) String notes, @PositiveOrZero @Max(10000) Integer workers,
                                @PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal hours) {}

    /** {@code memoCost}: Phase 1 typed-in cost, not in the accounts (D7). */
    public record Activity(long id, long cycleId, String cycleName, String activityType, LocalDate activityDate, String notes, Integer workers,
                           BigDecimal hours, String inputName, BigDecimal quantity, String quantityUnit, BigDecimal memoCost,
                           boolean legacyMemo, String status, Long stockDocumentId, Long billDocumentId, Instant createdAt) {}

    /** {@code memoSaleValue}: Phase 1 typed-in value, not revenue. Legacy harvests are history only (F4). */
    public record Harvest(long id, long cycleId, String cycleName, LocalDate harvestDate, BigDecimal quantity, String quantityUnit,
                          Long itemId, BigDecimal baseQuantity, Long lotId, String lotNo, String grade, BigDecimal moisturePct,
                          BigDecimal memoSaleValue, boolean legacy, String status, Long documentId, String notes) {}

    // ---- Observations and weather ----

    public record ObservationRequest(@NotNull LocalDate observedOn,
                                     @NotNull @Pattern(regexp = "PEST|DISEASE|NUTRIENT|WEED|WATER_STRESS|OTHER") String kind,
                                     @NotNull @Pattern(regexp = "LOW|MEDIUM|HIGH") String severity, @Size(max = 120) String fieldPart,
                                     @Size(max = 1000) String notes, @Size(max = 500) String actionTaken, Long activityId) {}

    public record Observation(long id, long cycleId, LocalDate observedOn, String kind, String severity, String fieldPart, String notes,
                              String actionTaken, Long activityId, Instant createdAt) {}

    public record WeatherRequest(@PositiveOrZero @Digits(integer = 4, fraction = 1) BigDecimal rainfallMm, @Size(max = 500) String notes) {}

    public record WeatherNote(long id, long farmId, LocalDate date, BigDecimal rainfallMm, String notes) {}

    public record Weather(long farmId, LocalDate from, LocalDate to, BigDecimal totalRainfallMm, List<WeatherNote> days) {}
}
