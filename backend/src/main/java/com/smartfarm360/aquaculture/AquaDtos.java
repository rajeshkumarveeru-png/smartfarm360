package com.smartfarm360.aquaculture;

import com.smartfarm360.finance.FinanceDtos.PaidNow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Ponds, culture cycles, water quality and harvests (aquaculture design, approved 2026-10-21). Money is rupees. */
public final class AquaDtos {
    private AquaDtos() {}

    static final String SPECIES = "ROHU|CATLA|MRIGAL|COMMON_CARP|GRASS_CARP|SILVER_CARP|TILAPIA|PANGASIUS|MURREL|VANNAMEI|TIGER_SHRIMP|SCAMPI|OTHER";
    static final String PARAMETERS = "DISSOLVED_OXYGEN|PH|TEMPERATURE|AMMONIA|TRANSPARENCY|SALINITY";

    // ---- Ponds (K1) ----

    public record PondRequest(@NotBlank @Size(max = 120) String name, @NotNull Long farmId, Long parcelId,
                              @NotNull @Pattern(regexp = "EARTHEN|LINED|TANK|CAGE") String kind,
                              @NotNull @Positive @Digits(integer = 8, fraction = 4) BigDecimal waterArea,
                              @Pattern(regexp = "ACRE|HECTARE|CENT|SQFT") String areaUnit,
                              @Positive @Digits(integer = 4, fraction = 2) BigDecimal depthM, Boolean active) {}

    /** {@code latestReading}: the pond's last water reading, with any parameters outside its limits. */
    public record Pond(long id, long farmId, String farmName, Long parcelId, String name, String kind, BigDecimal waterArea, String areaUnit,
                       BigDecimal areaAcres, BigDecimal depthM, boolean active, Cycle openCycle, Reading latestReading) {}

    // ---- Culture cycles and stocking (K2, K3, K4, K11) ----

    public record CycleRequest(@Size(max = 140) String name, @NotNull LocalDate startedOn, LocalDate plannedHarvest, @Size(max = 300) String notes) {}

    /** {@code status}: PREPARING (nothing stocked), GROWING, HARVESTED (a final harvest) — worked out from the records. */
    public record Cycle(long id, long pondId, String pondName, long costObjectId, String name, String status, LocalDate startedOn,
                        LocalDate stockedOn, LocalDate plannedHarvest, LocalDate harvestedOn, BigDecimal areaAcres, String notes) {}

    /** The seed bill (K4): a cost of the cycle under Fish and shrimp seed. */
    public record SeedBill(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, Long partyId, Long financialAccountId,
                           @Valid PaidNow paidNow, @Size(max = 100) String referenceNo) {}

    public record StockingRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = SPECIES) String species, @Size(max = 60) String otherName,
                                  @NotNull @Positive @Max(100_000_000) Integer count, @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal kg,
                                  @Size(max = 120) String hatchery, @Valid SeedBill bill) {}

    public record Stocking(long id, LocalDate date, String species, String otherName, int count, BigDecimal kg, String hatchery, Long documentId,
                           boolean reversed) {}

    // ---- Daily pond record (K5) ----

    public record FeedLine(@NotNull Long itemId, @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal kg) {}

    public record DeathLine(@NotNull @Pattern(regexp = SPECIES) String species, @Size(max = 60) String otherName,
                            @NotNull @Positive @Max(10_000_000) Integer count, @Size(max = 120) String cause) {}

    public record DayRequest(@Size(max = 10) List<@Valid FeedLine> feeds, @Size(max = 20) List<@Valid DeathLine> deaths, @Size(max = 300) String notes) {}

    public record Day(LocalDate day, List<FeedLine> feeds, List<DeathLine> deaths, String notes, Long feedDocumentId) {}

    // ---- Samples (K6) ----

    /** No species: a mixed sample of all the fish in the pond. */
    public record SampleRequest(@NotNull LocalDate date, @Pattern(regexp = SPECIES) String species, @Size(max = 60) String otherName,
                                @NotNull @Positive @Max(100_000) Integer fishWeighed, @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal kg) {}

    public record Sample(long id, LocalDate date, String species, String otherName, int fishWeighed, BigDecimal kg, BigDecimal averageGrams) {}

    // ---- Water quality (K7) ----

    public record ReadingRequest(@NotNull OffsetDateTime takenAt, @PositiveOrZero @Digits(integer = 4, fraction = 2) BigDecimal dissolvedOxygen,
                                 @DecimalMin("0") @DecimalMax("14") @Digits(integer = 2, fraction = 2) BigDecimal ph,
                                 @Digits(integer = 3, fraction = 2) BigDecimal temperature, @PositiveOrZero @Digits(integer = 4, fraction = 3) BigDecimal ammonia,
                                 @PositiveOrZero @Digits(integer = 5, fraction = 1) BigDecimal transparency,
                                 @PositiveOrZero @Digits(integer = 4, fraction = 2) BigDecimal salinity, @Size(max = 300) String notes) {}

    /** {@code outOfLimits}: the parameters outside the pond's limits (or the farm business's). */
    public record Reading(long id, long pondId, String pondName, OffsetDateTime takenAt, BigDecimal dissolvedOxygen, BigDecimal ph, BigDecimal temperature,
                          BigDecimal ammonia, BigDecimal transparency, BigDecimal salinity, String notes, List<String> outOfLimits) {}

    public record Limit(@NotNull @Pattern(regexp = PARAMETERS) String parameter, @Digits(integer = 5, fraction = 3) BigDecimal lowest,
                        @Digits(integer = 5, fraction = 3) BigDecimal highest) {}

    /** Replaces the limits of a pond, or with {@code pondId} null the farm business's defaults. */
    public record LimitsRequest(Long pondId, @NotNull @Size(max = 6) List<@Valid Limit> limits) {}

    public record Limits(List<Limit> defaults, List<Limit> pond) {}

    // ---- Harvests (K8) ----

    public record HarvestLineRequest(@NotNull @Pattern(regexp = SPECIES) String species, @Size(max = 60) String otherName, @Size(max = 60) String grade,
                                     @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal kg, @Positive @Max(100_000_000) Integer count,
                                     @NotNull @Pattern(regexp = "SOLD|HOME|GIVEN") String use, @Positive @Digits(integer = 10, fraction = 2) BigDecimal rate) {}

    /** A charge the buyer took off the price (commission, ice, transport), recorded as a cost of the cycle to the same buyer. */
    public record Charge(@NotNull @Pattern(regexp = "MARKET_CHARGES|TRANSPORT|OTHER_EXPENSE") String category, @Size(max = 120) String description,
                         @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

    public record HarvestRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "PARTIAL|FINAL") String kind,
                                 @NotEmpty @Size(max = 40) List<@Valid HarvestLineRequest> lines, Long partyId, Long financialAccountId,
                                 @Valid PaidNow paidNow, @Size(max = 100) String referenceNo, @Size(max = 5) List<@Valid Charge> charges,
                                 @Size(max = 300) String notes) {}

    public record HarvestLine(String species, String otherName, String grade, BigDecimal kg, Integer count, String use, BigDecimal rate, BigDecimal amount) {}

    public record Harvest(long id, LocalDate date, String kind, Long saleDocumentId, Long chargesDocumentId, boolean reversed, List<HarvestLine> lines,
                          String notes) {}

    // ---- Figures (K9, K14) ----

    /** Estimates per species (K3, K6): never presented as actual. */
    public record SpeciesEstimate(String species, String otherName, int stocked, BigDecimal stockedKg, int deaths, int harvestedCount, BigDecimal harvestedKg,
                                  int estimatedCount, BigDecimal averageGrams, LocalDate sampledOn, BigDecimal estimatedKg) {}

    /** Figures for a period; money needs FINANCE_READ (null otherwise). Survival and unseen losses only after a final harvest with counts. */
    public record Figures(LocalDate from, LocalDate to, Integer daysOfCulture, int stockedCount, BigDecimal stockedKg, int deaths, Integer harvestedCount,
                          BigDecimal harvestedKg, BigDecimal soldKg, BigDecimal feedKg, BigDecimal survivalPercent, Integer unseenLosses, BigDecimal fcr,
                          BigDecimal yieldPerAcre, BigDecimal revenue, BigDecimal costs, BigDecimal profit, BigDecimal costPerKg, BigDecimal profitPerAcre,
                          BigDecimal averagePrice, BigDecimal estimatedKg, BigDecimal estimatedFcr, LocalDate estimatedOn) {}

    public record CycleReport(Cycle cycle, List<SpeciesEstimate> species, Figures period, Figures sinceStocking) {}
}
