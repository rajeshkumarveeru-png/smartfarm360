package com.smartfarm360.livestock;

import com.smartfarm360.finance.FinanceDtos.PaidNow;
import com.smartfarm360.livestock.LivestockDtos.Group;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Poultry batches and goat and sheep herds (poultry, goats and sheep design, approved 2026-10-21). Money is rupees. */
public final class FlockDtos {
    private FlockDtos() {}

    /** A bill for birds or animals bought, a cost of the group (J3). */
    public record Bill(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, Long partyId, Long financialAccountId,
                       @Valid PaidNow paidNow, @Size(max = 100) String referenceNo) {}

    /**
     * Starts a batch or herd (J2): the group, its placement and the first head count. With {@code bill} the heads are
     * bought; without, received (a gift, a transfer or animals already on the farm).
     */
    public record PlacementRequest(@NotBlank @Size(max = 140) String name, @NotNull @Pattern(regexp = "CHICKEN|GOAT|SHEEP") String species,
                                   @NotNull @Pattern(regexp = "LAYER|BROILER|COUNTRY|BREEDING_HERD|FATTENING|MIXED") String purpose, Long farmId,
                                   @NotNull LocalDate placedOn, @PositiveOrZero @Max(3650) Integer ageAtPlacementDays, @Size(max = 80) String strain,
                                   @Positive @Max(1_000_000) Integer heads, @Valid Bill bill, @Size(max = 300) String notes,
                                   @Size(max = 20) List<@Valid VarietyLine> lines) {
        /** One variety, as before varieties (V7): {@code species}, {@code strain} and {@code heads}. */
        public PlacementRequest(String name, String species, String purpose, Long farmId, LocalDate placedOn, Integer ageAtPlacementDays, String strain,
                                Integer heads, Bill bill, String notes) {
            this(name, species, purpose, farmId, placedOn, ageAtPlacementDays, strain, heads, bill, notes, null);
        }
    }

    /**
     * One variety of a batch or herd (bin and batch varieties design, V1-V3): an existing line ({@code lineId}) or a new one
     * ({@code species} and {@code variety}, such as Sonali, Kadaknath or Peruvidai), its heads and, when bought, its total purchase line amount.
     */
    public record VarietyLine(Long lineId, @Pattern(regexp = "CHICKEN|DUCK|QUAIL|TURKEY|GOAT|SHEEP") String species, @Size(max = 120) String variety,
                              @NotNull @Positive @Max(1_000_000) Integer heads, @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

    /** A variety in a batch or herd, with its heads today. */
    public record FlockLine(long id, String species, String variety, int heads) {}

    /**
     * A head movement (J7). {@code kind}: BORN, DIED, CULLED, RECEIVED, MOVED (with {@code toGroupId}) or COUNT (with
     * {@code countedHeads}: the difference from the recorded count is recorded with {@code cause}).
     */
    /**
     * {@code lineId}: the variety it happened to, needed when the batch has more than one. Birds received of a new variety
     * name it with {@code species} and {@code variety}.
     */
    public record MovementRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "BORN|DIED|CULLED|RECEIVED|MOVED|COUNT") String kind,
                                  @Positive @Max(1_000_000) Integer heads, Long toGroupId, @PositiveOrZero @Max(1_000_000) Integer countedHeads,
                                  @Size(max = 300) String cause, @Positive @Digits(integer = 9, fraction = 3) BigDecimal kg, Long lineId,
                                  @Pattern(regexp = "CHICKEN|DUCK|QUAIL|TURKEY|GOAT|SHEEP") String species, @Size(max = 120) String variety) {
        public MovementRequest(LocalDate date, String kind, Integer heads, Long toGroupId, Integer countedHeads, String cause, BigDecimal kg) {
            this(date, kind, heads, toGroupId, countedHeads, cause, kg, null, null, null);
        }
    }

    public record Movement(long id, LocalDate date, String kind, int heads, BigDecimal kg, String cause, Long documentId, Long batchDayId,
                           Long pairGroupId, String pairGroupName, boolean reversed, Long lineId, String lineSpecies, String lineVariety) {}

    /** Heads bought with one bill: one variety ({@code heads}, the bill's amount) or several {@code lines}, each priced. */
    public record PurchaseRequest(@NotNull LocalDate date, @Positive @Max(1_000_000) Integer heads, @NotNull @Valid Bill bill,
                                  @Size(max = 20) List<@Valid VarietyLine> lines) {
        public PurchaseRequest(LocalDate date, Integer heads, Bill bill) { this(date, heads, bill, null); }
    }

    /** Adds a missing/under-recorded purchase cost to an existing placement without changing its head count. */
    public record PurchaseCostAdjustmentRequest(@NotNull LocalDate date, @NotNull @Valid Bill bill, @Size(max = 300) String notes) {}

    /** Birds or animals sold by head and live weight (J6); {@code rateBasis} KG or HEAD. */
    public record SaleRequest(@NotNull LocalDate date, @NotNull @Positive @Max(1_000_000) Integer heads,
                              @Positive @Digits(integer = 9, fraction = 3) BigDecimal kg, @NotNull @Pattern(regexp = "KG|HEAD") String rateBasis,
                              @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal rate, Long partyId, Long financialAccountId,
                              @Valid PaidNow paidNow, @Size(max = 100) String referenceNo, Long lineId) {}

    /** Herd-level kidding or lambing (J10). {@code damId}: an individually recorded doe or ewe. */
    public record BirthRequest(@NotNull LocalDate date, @NotNull @PositiveOrZero @Max(20) Integer alive, @PositiveOrZero @Max(20) Integer dead, Long damId,
                               @Size(max = 300) String notes) {}

    // ---- Daily batch record (J4, J5, J13) ----

    public record BatchDayRequest(@PositiveOrZero @Max(1_000_000) Integer deaths, @Size(max = 120) String deathCause, @PositiveOrZero @Max(1_000_000) Integer culls,
                                  @PositiveOrZero @Max(10_000_000) Integer eggsGood, @PositiveOrZero @Max(10_000_000) Integer eggsCracked,
                                  @PositiveOrZero @Max(10_000_000) Integer eggsBroken, Long feedItemId,
                                  @Positive @Digits(integer = 9, fraction = 3) BigDecimal feedKg, @Positive @Max(100_000) Integer weightBirds,
                                  @Positive @Digits(integer = 7, fraction = 3) BigDecimal weightKg, @Size(max = 300) String notes,
                                  @Size(max = 20) String baseVersion, @Size(max = 20) List<@Valid DayLine> lines) {
        /** {@code baseVersion}: the day's version the user started from (mobile offline design, N7); null skips the check. */
        public BatchDayRequest(Integer deaths, String deathCause, Integer culls, Integer eggsGood, Integer eggsCracked, Integer eggsBroken, Long feedItemId,
                               BigDecimal feedKg, Integer weightBirds, BigDecimal weightKg, String notes) {
            this(deaths, deathCause, culls, eggsGood, eggsCracked, eggsBroken, feedItemId, feedKg, weightBirds, weightKg, notes, null, null);
        }

        public BatchDayRequest(Integer deaths, String deathCause, Integer culls, Integer eggsGood, Integer eggsCracked, Integer eggsBroken, Long feedItemId,
                               BigDecimal feedKg, Integer weightBirds, BigDecimal weightKg, String notes, String baseVersion) {
            this(deaths, deathCause, culls, eggsGood, eggsCracked, eggsBroken, feedItemId, feedKg, weightBirds, weightKg, notes, baseVersion, null);
        }
    }

    /**
     * The day by variety (V5): deaths, culls, eggs laid, and the owner's rate for one egg that day. With lines, the day's
     * deaths and culls are their sums, and good eggs are those laid less the cracked and broken.
     */
    public record DayLine(@NotNull Long lineId, @PositiveOrZero @Max(1_000_000) Integer deaths, @PositiveOrZero @Max(1_000_000) Integer culls,
                          @PositiveOrZero @Max(10_000_000) Integer eggs, @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal eggRate) {}

    /** A variety on a day, with its heads at the end of the day. */
    public record DayLineView(long lineId, String species, String variety, int heads, int deaths, int culls, int eggs, BigDecimal eggRate,
                              Integer lastEggs, LocalDate lastEggsOn, BigDecimal lastEggRate) {}

    /** {@code warnings}: EGGS_WITHHELD while a withdrawal applies to the batch (J9); HIGH_MORTALITY above 1% of the birds. */
    public record BatchDay(LocalDate day, int deaths, String deathCause, int culls, int eggsGood, int eggsCracked, int eggsBroken, Long feedItemId,
                           BigDecimal feedKg, Long feedDocumentId, Integer weightBirds, BigDecimal weightKg, String notes, int headsAtEnd,
                           List<String> warnings, String version, List<DayLineView> lines) {}

    /** A saved day for the day-wise list: its totals and what was recorded for each variety. */
    public record DayRecord(LocalDate day, int deaths, int culls, int eggsGood, int eggsCracked, int eggsBroken, BigDecimal feedKg, String notes, List<DayRecordLine> lines) {}

    public record DayRecordLine(long lineId, String species, String variety, int deaths, int culls, int eggs, BigDecimal eggRate) {}

    // ---- Eggs (J5) ----

    public record EggUseRequest(@NotNull LocalDate date, @PositiveOrZero @Max(10_000_000) Integer homeUse, @PositiveOrZero @Max(10_000_000) Integer damaged,
                                @Size(max = 300) String reason) {}

    /** {@code unit}: piece, dozen or tray (30). */
    public record EggSaleRequest(@NotNull LocalDate date, @NotNull Long groupId, @NotNull @Positive @Digits(integer = 9, fraction = 2) BigDecimal quantity,
                                 @NotNull @Pattern(regexp = "piece|dozen|tray") String unit, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 Long partyId, Long financialAccountId, @Valid PaidNow paidNow, @Size(max = 100) String referenceNo) {}

    // ---- Health plans (J9) ----

    public record PlanItemRequest(@NotNull @Pattern(regexp = LivestockDtos.HEALTH_KINDS) String kind, @NotBlank @Size(max = 300) String description,
                                  @NotNull LocalDate dueOn) {}

    public record PlanItem(long id, String kind, String description, LocalDate dueOn, Long doneEventId) {}

    public record CopyPlanRequest(@NotNull Long fromGroupId) {}

    // ---- Figures (J8, J14) ----

    /**
     * Figures for a period; money needs FINANCE_READ (null otherwise). Percentages have two decimals, hen-day one.
     * {@code eggValue}: eggs laid at the owner's daily rates (an estimate, not in the accounts); {@code profitWithEggs}: the
     * profit counting every egg at those rates instead of what egg sales brought in, so no egg is counted twice.
     */
    public record Figures(LocalDate from, LocalDate to, int openingHeads, int closingHeads, int arrived, int born, int died, int culled, int sold,
                          int movedOut, BigDecimal soldKg, long headDays, int eggsGood, int eggsCracked, int eggsBroken, int eggs, BigDecimal henDayPercent,
                          BigDecimal feedKg, BigDecimal feedPerDozen, BigDecimal mortalityPercent, BigDecimal averageWeight, BigDecimal fcr,
                          BigDecimal revenue, BigDecimal costs, BigDecimal profit, BigDecimal costPerEgg, BigDecimal costPerKg, BigDecimal costPerHeadMonth,
                          BigDecimal eggValue, BigDecimal profitWithEggs, List<VarietyFigures> varieties) {}

    /**
     * One variety's part of the figures. Money is null without finance access: what its birds cost and sold for, from
     * their bill lines, and its eggs at the owner's rates. Feed and other costs belong to the whole batch (V6).
     */
    public record VarietyFigures(long lineId, String species, String variety, int openingHeads, int closingHeads, int arrived, int born, int died, int culled,
                                 int sold, int movedOut, BigDecimal soldKg, long headDays, int eggs, BigDecimal eggRate, BigDecimal eggValue,
                                 BigDecimal boughtAmount, BigDecimal soldAmount) {}

    /** A batch or herd: the period and, for batches, since placement (principle 4). */
    public record FlockReport(Group group, int heads, Figures period, Figures sincePlacement) {}
}
