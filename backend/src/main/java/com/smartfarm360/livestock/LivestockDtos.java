package com.smartfarm360.livestock;

import com.smartfarm360.finance.FinanceDtos.PaidNow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Livestock and dairy API records (livestock design, approved 2026-10-21). Money is rupees; milk is litres. */
public final class LivestockDtos {
    private LivestockDtos() {}

    public static final String SPECIES = "COW|BUFFALO|GOAT|SHEEP";
    public static final String GROUP_SPECIES = "CATTLE|CHICKEN|GOAT|SHEEP";
    public static final String PURPOSES = "MILKING|DRY|HEIFER|CALF|BULL|OTHER|LAYER|BROILER|COUNTRY|BREEDING_HERD|FATTENING|MIXED";
    public static final String SESSIONS = "MORNING|EVENING";
    public static final String HEALTH_KINDS = "TREATMENT|VACCINATION|DEWORMING|HEALTH_OTHER";

    // ---- Groups (I2) ----

    /** {@code species}: CATTLE (default) for dairy groups; CHICKEN, GOAT or SHEEP for counted batches and herds (Phase 9, J1). */
    public record GroupRequest(@NotBlank @Size(max = 140) String name, @NotNull @Pattern(regexp = PURPOSES) String purpose, Long farmId,
                               @Size(max = 300) String notes, @Pattern(regexp = GROUP_SPECIES) String species) {
        public GroupRequest(String name, String purpose, Long farmId, String notes) { this(name, purpose, farmId, notes, null); }
    }

    public record GroupPatch(@Size(min = 1, max = 140) String name, @Pattern(regexp = PURPOSES) String purpose, Long farmId, @Size(max = 300) String notes,
                             Boolean active) {}

    /** {@code costObjectId}: what costs, revenue, labour and stock use are assigned to. */
    /**
     * {@code animals}: individual animals in the group, or the head count for counted batches and herds (J1, J7).
     * {@code closed}: closed by hand ({@code closedOn}), or a poultry batch with no birds left (J2, J14).
     */
    public record Group(long id, long costObjectId, String name, String purpose, Long farmId, String notes, boolean active, int animals,
                        String species, LocalDate placedOn, Integer ageAtPlacementDays, String strain, LocalDate closedOn, boolean closed) {
        public boolean counted() { return !"CATTLE".equals(species); }
    }

    // ---- Animals (I1, I3) ----

    /**
     * Buys the animal on a bill and registers it as a farm asset in the same step (G8, I3); {@code usefulLifeMonths} and
     * {@code cullValue} set its depreciation (I4).
     */
    public record Purchase(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, Long partyId, Long financialAccountId,
                           @Valid PaidNow paidNow, @Positive @Max(240) Integer usefulLifeMonths,
                           @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal cullValue, @Size(max = 100) String referenceNo) {}

    /**
     * {@code origin}: BORN (nil value), BOUGHT (with {@code purchase}, or an {@code assetId} already in the register) or
     * RECEIVED (a gift or transfer, nil value). {@code arrivedOn}: birth date for BORN, purchase date for BOUGHT.
     */
    public record AnimalRequest(@NotBlank @Size(max = 40) String tag, @Size(max = 80) String name, @NotNull @Pattern(regexp = SPECIES) String species,
                                @Size(max = 80) String breed, @NotNull @Pattern(regexp = "FEMALE|MALE") String sex, LocalDate birthDate,
                                Boolean birthDateEstimated, @NotNull @Pattern(regexp = "BORN|BOUGHT|RECEIVED") String origin,
                                @NotNull LocalDate arrivedOn, Long damId, @Size(max = 120) String sire, Long farmId, @NotNull Long groupId,
                                @Valid Purchase purchase, Long assetId, @Size(max = 500) String notes) {}

    public record AnimalPatch(@Size(min = 1, max = 40) String tag, @Size(max = 80) String name, @Size(max = 80) String breed, LocalDate birthDate,
                              Boolean birthDateEstimated, @Size(max = 120) String sire, Long farmId, @Size(max = 500) String notes) {}

    public record MoveRequest(@NotNull Long groupId, @NotNull LocalDate date) {}

    /**
     * Breeding state worked out from events (I10, I11). {@code repro}: OPEN, INSEMINATED or PREGNANT (females only);
     * {@code withheldUntil}: milk must not be sold up to this date (I9).
     */
    public record Breeding(String repro, LocalDate lastInsemination, LocalDate checkDueFrom, LocalDate expectedCalving, LocalDate dryOffDue,
                           Integer lactation, LocalDate lastCalving, Long daysInMilk, boolean inMilk, LocalDate withheldUntil) {}

    /** {@code bookValue}: for registered animals, shown only with FINANCE_READ. */
    public record Animal(long id, String tag, String name, String species, String breed, String sex, LocalDate birthDate, boolean birthDateEstimated,
                         String origin, LocalDate arrivedOn, Long damId, String damTag, String sire, Long farmId, Long groupId, String groupName,
                         Long assetId, BigDecimal bookValue, String status, LocalDate exitDate, String exitCause, Long exitDocumentId, String notes,
                         Breeding breeding) {}

    public record GroupMove(long groupId, String groupName, LocalDate from) {}

    public record Lactation(int number, LocalDate calvingDate, LocalDate dryOffDate, BigDecimal recordedLitres) {}

    /** The animal card (section 8): one animal's whole history. */
    public record AnimalCard(Animal animal, List<GroupMove> moves, List<Event> events, List<Lactation> lactations, List<Animal> calves,
                             List<DayYield> recentYields) {}

    public record DayYield(LocalDate date, String session, BigDecimal litres) {}

    // ---- Leaving the herd (I14) ----

    /**
     * SOLD and CULLED need {@code price} and a buyer ({@code partyId}) or the account the money came into; DIED has none.
     * Registered animals are disposed of in the asset register; home-bred ones are sold as Livestock sales.
     */
    public record ExitRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "SOLD|DIED|CULLED") String kind, @Size(max = 300) String cause,
                              @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal price, Long partyId, Long financialAccountId) {}

    public record InsuranceRequest(@NotNull LocalDate date, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                   @NotNull Long financialAccountId, @Size(max = 300) String notes) {}

    // ---- Milk (I5–I8) ----

    public record YieldEntry(@NotNull Long animalId, @NotNull @PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal litres) {}

    /**
     * {@code litres}: the group total; with {@code yields} it is their sum. No litres and no yields clears the session.
     * {@code baseVersion}: the session's version the user started from (mobile offline design, N7); null skips the check.
     */
    public record SessionEntry(@NotNull @Pattern(regexp = SESSIONS) String session, @NotNull Long groupId,
                               @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal litres,
                               @DecimalMin("0") @DecimalMax("15") @Digits(integer = 2, fraction = 2) BigDecimal fat,
                               @DecimalMin("0") @DecimalMax("15") @Digits(integer = 2, fraction = 2) BigDecimal snf,
                               @Size(max = 300) List<@Valid YieldEntry> yields, @Size(max = 300) String notes, @Size(max = 20) String baseVersion) {
        public SessionEntry(String session, Long groupId, BigDecimal litres, BigDecimal fat, BigDecimal snf, List<YieldEntry> yields, String notes) {
            this(session, groupId, litres, fat, snf, yields, notes, null);
        }
    }

    public record MilkDayRequest(@NotEmpty @Size(max = 40) List<@Valid SessionEntry> sessions) {}

    /** {@code version}: changes whenever this session's figures change (N7). */
    public record Session(long id, String session, long groupId, String groupName, BigDecimal litres, BigDecimal fat, BigDecimal snf,
                          List<YieldEntry> yields, String notes, String version) {}

    /** {@code withheld}: milking animals whose milk must not be sold today (I9). */
    public record WithheldAnimal(long animalId, String tag, String name, LocalDate until) {}

    public record MilkDay(LocalDate date, List<Session> sessions, BigDecimal total, List<WithheldAnimal> withheld, BigDecimal inStock) {}

    /** Where the milk went (I6): home use (overhead), fed to a group (calves), discarded with a reason. */
    public record MilkUseRequest(@NotNull LocalDate date, @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal homeUse,
                                 @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal fedLitres, Long fedGroupId,
                                 @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal discarded, @Size(max = 300) String discardReason) {}

    /** A dairy society's bill for a period (I8), or a cash sale; assigned to {@code groupId}. */
    public record MilkSaleRequest(@NotNull LocalDate date, Long partyId, Long financialAccountId,
                                  @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal litres,
                                  @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @NotNull Long groupId,
                                  LocalDate periodFrom, LocalDate periodTo, @Valid PaidNow paidNow, @Size(max = 100) String referenceNo) {}

    public record RecordRef(long documentId, String number, String type, BigDecimal amount) {}

    // ---- Health and breeding (I9–I11) ----

    /** Medicine from stock (a stock use to the group) and/or a vet bill tagged to the group. */
    public record Medicine(@NotNull Long itemId, @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity, @Size(max = 30) String unit) {}

    public record VetBill(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, Long partyId, Long financialAccountId) {}

    public record HealthRequest(Long animalId, Long groupId, @NotNull @Pattern(regexp = HEALTH_KINDS) String kind, @NotNull LocalDate date,
                                @NotBlank @Size(max = 300) String description, @Size(max = 120) String doneBy,
                                @PositiveOrZero @Max(120) Integer withdrawalDays, LocalDate nextDue, @Valid Medicine medicine, @Valid VetBill bill,
                                Long planItemId) {
        public HealthRequest(Long animalId, Long groupId, String kind, LocalDate date, String description, String doneBy, Integer withdrawalDays,
                             LocalDate nextDue, Medicine medicine, VetBill bill) {
            this(animalId, groupId, kind, date, description, doneBy, withdrawalDays, nextDue, medicine, bill, null);
        }
    }

    /**
     * Breeding events. {@code kind}: HEAT, INSEMINATION (service AI or NATURAL, with {@code bull}), PREGNANCY_CHECK
     * ({@code result} POSITIVE or NEGATIVE), ABORTION or DRY_OFF. Dates worked out from an insemination can be overridden.
     */
    public record BreedingRequest(@NotNull @Pattern(regexp = "HEAT|INSEMINATION|PREGNANCY_CHECK|ABORTION|DRY_OFF") String kind, @NotNull LocalDate date,
                                  @Pattern(regexp = "AI|NATURAL") String service, @Size(max = 120) String bull, @Size(max = 120) String doneBy,
                                  @Pattern(regexp = "POSITIVE|NEGATIVE") String result, LocalDate checkDueFrom, LocalDate expectedCalving,
                                  LocalDate dryOffDue, @Size(max = 300) String notes, @Valid VetBill bill, Long groupId) {}

    public record CalfEntry(@NotBlank @Size(max = 40) String tag, @Size(max = 80) String name, @NotNull @Pattern(regexp = "FEMALE|MALE") String sex) {}

    /** {@code calves}: the live calves; {@code calfGroupId} defaults to the Calves group, {@code milkingGroupId} to the milking group. */
    public record CalvingRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "LIVE|STILLBORN") String result,
                                 @Size(max = 4) List<@Valid CalfEntry> calves, Long calfGroupId, Long milkingGroupId, @Size(max = 300) String notes) {}

    public record Event(long id, Long animalId, String animalTag, Long groupId, String groupName, String kind, LocalDate date, String description,
                        String result, String service, String bull, String doneBy, LocalDate withdrawalUntil, LocalDate nextDue,
                        LocalDate checkDueFrom, LocalDate expectedCalving, LocalDate dryOffDue, Long documentId, Long stockDocumentId) {}

    // ---- Reports (section 8) ----

    public record MilkRow(LocalDate date, long groupId, String groupName, BigDecimal morning, BigDecimal evening, BigDecimal total,
                          BigDecimal fat, BigDecimal snf) {}

    /** Litres produced by group and day, and where the tenant's milk went in the period (from stock). */
    public record MilkReport(LocalDate from, LocalDate to, List<MilkRow> days, BigDecimal produced, BigDecimal sold, BigDecimal homeUse,
                             BigDecimal fedToAnimals, BigDecimal discarded, BigDecimal otherOut, BigDecimal fat, BigDecimal snf,
                             List<AnimalYield> animals) {}

    public record AnimalYield(long animalId, String tag, String name, BigDecimal litres, int days, BigDecimal perDay) {}

    public record CostLine(String accountCode, Long categoryId, String categoryCode, String categoryName, BigDecimal amount) {}

    /** I13: a group's own revenue and costs, litres, cost per litre produced and average price per litre sold. */
    public record HerdRow(long groupId, String groupName, String purpose, BigDecimal revenue, List<CostLine> costs, BigDecimal costTotal,
                          BigDecimal profit, BigDecimal litresProduced, BigDecimal litresSold, BigDecimal costPerLitre, BigDecimal pricePerLitre) {}

    public record HerdReport(LocalDate from, LocalDate to, boolean actual, List<HerdRow> groups) {}

    public record DueItem(String kind, long animalId, String tag, String name, LocalDate due) {}

    /** Due pregnancy checks, dry-offs and calvings, and cows open 90+ days after calving. */
    public record BreedingCalendar(LocalDate from, LocalDate to, List<DueItem> due, List<DueItem> openLong, List<CalvingInterval> intervals) {}

    public record CalvingInterval(long animalId, String tag, LocalDate previous, LocalDate latest, long days) {}

    public record HealthReport(LocalDate from, LocalDate to, List<Event> events, List<DueItem> nextDue, List<WithheldAnimal> withheld) {}
}
