package com.smartfarm360.assets;

import com.smartfarm360.finance.FinanceDtos.PaidNow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Assets API records (workforce and assets design, approved 2026-10-20, section 6). Money is rupees. */
public final class AssetDtos {
    private AssetDtos() {}

    public static final String KINDS = "EQUIPMENT|VEHICLE|IRRIGATION|STRUCTURE|BREEDING_ANIMAL|OTHER";

    /** Details of an asset; {@code usefulLifeMonths} is required except for breeding and milch animals (never depreciated, G10). */
    public record AssetDetails(@NotBlank @Size(max = 140) String name, @NotNull @Pattern(regexp = KINDS) String kind, Long farmId,
                               @Size(max = 80) String serialNo, @Positive @Max(1200) Integer usefulLifeMonths,
                               @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal salvageValue, LocalDate inUseFrom,
                               @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal depreciationBefore, Long costObjectId,
                               @Size(max = 500) String notes) {}

    /** Buys an asset on a bill (with a party, or paid from {@code financialAccountId}) and registers it in one step (G8). */
    public record PurchaseRequest(@NotNull LocalDate docDate, Long partyId, Long financialAccountId,
                                  @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @Valid PaidNow paidNow,
                                  @Size(max = 100) String referenceNo, @NotNull @Valid AssetDetails asset) {}

    /** Registers an asset cost already in the accounts (a bill or opening-balance line on Farm assets, 1300). */
    public record RegisterRequest(@NotNull Long sourceLineId, @NotNull @Valid AssetDetails asset) {}

    /** Life, salvage, in-use date and earlier depreciation can change only until the first depreciation run is posted. */
    public record AssetPatch(@Size(min = 1, max = 140) String name, @Size(max = 80) String serialNo, Long farmId, Long costObjectId,
                             @Size(max = 500) String notes, @Positive @Max(1200) Integer usefulLifeMonths,
                             @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal salvageValue, LocalDate inUseFrom,
                             @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal depreciationBefore) {}

    /**
     * {@code status}: IN_USE, SOLD or SCRAPPED. {@code accumulated}: depreciation before registration plus posted runs;
     * {@code monthlyDepreciation}: null when the asset is not depreciated.
     */
    public record Asset(long id, String name, String kind, Long farmId, String farmName, String serialNo, long sourceDocumentId, String sourceNumber,
                        BigDecimal cost, BigDecimal salvageValue, Integer usefulLifeMonths, LocalDate inUseFrom, BigDecimal depreciationBefore,
                        BigDecimal accumulated, BigDecimal bookValue, BigDecimal monthlyDepreciation, String lastDepreciatedMonth, String status,
                        Long disposalDocumentId, Long costObjectId, String costObjectName, String notes) {}

    public record UnregisteredLine(long lineId, long documentId, String documentType, String number, LocalDate date, String description,
                                   Long categoryId, String categoryCode, String categoryName, BigDecimal amount) {}

    // ---- Depreciation runs (G9) and disposal (G11) ----

    /** {@code month}: yyyy-MM. */
    public record RunRequest(@NotNull @Pattern(regexp = "\\d{4}-\\d{2}") String month) {}

    public record RunLine(long assetId, String name, BigDecimal amount, BigDecimal bookValueAfter) {}

    public record RunPreview(String month, List<RunLine> assets, BigDecimal total) {}

    public record RunSummary(String month, long documentId, String number, String status, BigDecimal total) {}

    /** SOLD needs proceeds and who paid (a party who owes it, or the account it came into); SCRAPPED has none. */
    public record DisposalRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "SOLD|SCRAPPED") String kind,
                                  @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal proceeds, Long partyId, Long financialAccountId,
                                  @Size(max = 500) String notes) {}

    public record ScheduleLine(String month, BigDecimal amount, BigDecimal bookValueAfter, boolean posted) {}

    // ---- Use and maintenance (G13) ----

    public record UsageRequest(@NotNull LocalDate date, @PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal hours,
                               @PositiveOrZero @Digits(integer = 9, fraction = 1) BigDecimal km, @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal acres,
                               Long costObjectId, @Size(max = 300) String notes) {}

    public record Usage(long id, LocalDate date, BigDecimal hours, BigDecimal km, BigDecimal acres, Long costObjectId, String costObjectName, String notes) {}

    public record PlanRequest(@NotBlank @Size(max = 120) String name, @Positive @Max(3650) Integer everyDays,
                              @Positive @Digits(integer = 6, fraction = 2) BigDecimal everyHours, LocalDate lastDoneOn,
                              @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal lastDoneHours, Boolean active) {}

    /** {@code status}: OK, DUE (within 7 days or 10% of the hours) or OVERDUE. */
    public record Plan(long id, long assetId, String assetName, String name, Integer everyDays, BigDecimal everyHours, LocalDate lastDoneOn,
                       BigDecimal lastDoneHours, LocalDate nextDate, BigDecimal hoursLeft, String status, boolean active) {}

    public record MaintenanceRequest(@NotNull LocalDate doneOn, Long planId, @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal hoursReading,
                                     @NotBlank @Size(max = 500) String description, Long documentId) {}

    public record Maintenance(long id, LocalDate doneOn, Long planId, String planName, BigDecimal hoursReading, String description, Long documentId,
                              String documentNumber) {}

    // ---- Reports ----

    public record CostRow(String accountCode, Long categoryId, String categoryCode, String categoryName, BigDecimal amount) {}

    /** Depreciation and running costs of an asset for a period, and the cost per hour of use when hours are recorded. */
    public record Costs(long assetId, LocalDate from, LocalDate to, BigDecimal depreciation, List<CostRow> runningCosts, BigDecimal runningTotal,
                        BigDecimal hours, BigDecimal costPerHour) {}

    /**
     * One asset in the maintenance and running costs report (dashboard and reports design, section 4). Money fields are
     * null for readers without {@code FINANCE_READ}.
     */
    public record RunningCostRow(long assetId, String name, String kind, String status, BigDecimal depreciation, BigDecimal runningCosts,
                                 BigDecimal totalCost, BigDecimal hours, BigDecimal costPerHour, int maintenanceDone, int plansDue) {}

    public record RunningCostsReport(LocalDate from, LocalDate to, boolean actual, boolean amountsShown, List<RunningCostRow> assets,
                                     BigDecimal depreciation, BigDecimal runningCosts, BigDecimal totalCost) {}

    /** Register against the ledger (1300 cost, 1310 accumulated depreciation). Unexplained difference must be zero. */
    public record Reconciliation(BigDecimal registerCost, BigDecimal registerAccumulated, BigDecimal ledgerCost, BigDecimal ledgerAccumulated,
                                 BigDecimal unregisteredCost, BigDecimal manualDepreciation, BigDecimal unexplained) {}
}
