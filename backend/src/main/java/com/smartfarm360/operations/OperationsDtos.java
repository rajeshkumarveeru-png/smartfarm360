package com.smartfarm360.operations;

import com.smartfarm360.crop.CropDtos.Activity;
import com.smartfarm360.crop.CropDtos.Harvest;
import com.smartfarm360.finance.ReportService.AmountRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Field operations API records (crop management design, sections 4, 5 and 7). */
public final class OperationsDtos {
    private OperationsDtos() {}

    /** Stock used by the activity (S3). {@code lotNo} is required for items that track lots. */
    public record Input(@NotNull Long itemId, @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity,
                        @Size(max = 30) String unit, @Size(max = 60) String lotNo) {}

    /** Labour or machine hire paid for the activity (F3): a bill for the cycle, owed to {@code partyId} or paid from {@code financialAccountId}. */
    public record PaidWork(@NotNull Long categoryId, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                           Long partyId, Long financialAccountId, @Size(max = 240) String description) {}

    public record ActivityRequest(@NotNull Long cycleId,
                                  @NotNull @Pattern(regexp = "LAND_PREPARATION|SOWING|TRANSPLANTING|FERTILIZER|PESTICIDE|IRRIGATION|WEEDING|HARVESTING|OTHER") String activityType,
                                  @NotNull LocalDate activityDate, @Size(max = 500) String notes, @PositiveOrZero @Max(10000) Integer workers,
                                  @PositiveOrZero @Digits(integer = 6, fraction = 2) BigDecimal hours, Long locationId,
                                  @Size(max = 50) List<@Valid Input> inputs, @Valid PaidWork paidWork) {}

    /** {@code warnings}: e.g. LOT_EXPIRED when an expired lot was used (allowed, stock design E6). */
    public record ActivityResult(Activity activity, List<String> warnings) {}

    /** Harvest into stock (S7) as a new lot of the crop's produce item. {@code unit}: any unit of that item (quintal, kg, bag). */
    public record HarvestRequest(@NotNull Long cycleId, @NotNull LocalDate harvestDate,
                                 @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity, @Size(max = 30) String unit,
                                 Long locationId, @Size(max = 40) String grade, @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal moisturePct,
                                 @Size(max = 500) String notes) {}

    public record ReverseRequest(LocalDate reversalDate) {}

    public record ProduceItemRequest(@NotNull Long itemId) {}

    /** {@code item} is null until the first harvest creates it or one is chosen. */
    public record ProduceItemView(com.smartfarm360.inventory.StockDtos.Item item) {}

    // ---- Reports ----

    /** Harvested quantity of one produce item, in its base unit, and how much of it is still in stock. */
    public record HarvestTotal(long itemId, String itemName, String baseUnit, BigDecimal quantity, BigDecimal unsold) {}

    public record Plan(LocalDate plannedSowingDate, LocalDate actualSowingDate, LocalDate expectedHarvestDate, LocalDate firstHarvestDate,
                       BigDecimal expectedYield, BigDecimal actualYield) {}

    /** Phase 1 figures shown separately, not in the accounts (D7, F4). */
    public record Memo(BigDecimal activityCost, BigDecimal harvestValue, int harvestCount) {}

    /**
     * A cycle's figures (section 7). Money is actual, from posted records assigned to the cycle. {@code estimateValue}
     * (unsold produce × the price given) is an estimate and not in the accounts (F7).
     */
    public record CycleReport(long cycleId, String cycleName, String cropName, String status, LocalDate from, LocalDate to, String basis, boolean actual,
                              List<AmountRow> revenue, BigDecimal revenueTotal, BigDecimal otherIncome, List<AmountRow> directCosts,
                              BigDecimal directCostTotal, List<AmountRow> allocatedOverhead, BigDecimal allocatedTotal, BigDecimal interest,
                              BigDecimal losses, BigDecimal totalCost, BigDecimal profit, BigDecimal areaAcres, BigDecimal costPerAcre,
                              List<HarvestTotal> harvested, BigDecimal yieldPerAcre, BigDecimal costPerUnit, String costUnit,
                              BigDecimal unsold, BigDecimal estimatePrice, BigDecimal estimateValue, Plan plan, Memo memo) {}

    /** One cycle of a crop in the season comparison (F8). */
    public record ComparisonRow(long cycleId, String cycleName, String seasonName, String status, BigDecimal areaAcres, LocalDate plannedSowingDate,
                                LocalDate actualSowingDate, LocalDate expectedHarvestDate, LocalDate firstHarvestDate, BigDecimal expectedYield,
                                BigDecimal harvested, String unit, BigDecimal yieldPerAcre, BigDecimal revenue, BigDecimal totalCost,
                                BigDecimal costPerAcre, BigDecimal profit) {}

    public record Comparison(long cropId, String cropName, List<ComparisonRow> cycles) {}

    public record HarvestResult(Harvest harvest, List<String> warnings) {}
}
