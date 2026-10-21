package com.smartfarm360.processing;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Processing batches, their steps, output and the lot trace (processing and trading design, approved 2026-10-21). Money is rupees. */
public final class ProcessingDtos {
    private ProcessingDtos() {}

    public record BatchRequest(@NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]*") String code, @NotBlank @Size(max = 140) String name,
                               @NotNull @Pattern(regexp = "GRADING|DRYING|MILLING|PRESSING|PACKING|OTHER") String kind, @NotNull Long farmId,
                               @NotNull Long mainItemId, @NotNull LocalDate startedOn, LocalDate expectedEnd, @Size(max = 300) String notes) {}

    /** {@code status}: RUNNING (no output), OUTPUT (some output taken), FINISHED — worked out from the records. */
    public record Batch(long id, long costObjectId, String code, String name, String kind, long farmId, String farmName, long mainItemId, String mainItemName,
                        LocalDate startedOn, LocalDate expectedEnd, LocalDate finishedOn, String status, String notes) {}

    public record FinishRequest(@NotNull LocalDate date) {}

    public record StepRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "CLEANING|DRYING|GRADING|MILLING|PRESSING|PACKING|OTHER") String kind,
                              @Positive @Digits(integer = 9, fraction = 3) BigDecimal kgIn, @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal kgOut,
                              @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal moistureBefore,
                              @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal moistureAfter,
                              @Size(max = 60) String grade, @Size(max = 300) String notes) {}

    /** {@code lossKg}: kg in − kg out of the step, when both are known. */
    public record Step(long id, LocalDate date, String kind, BigDecimal kgIn, BigDecimal kgOut, BigDecimal lossKg, BigDecimal moistureBefore,
                       BigDecimal moistureAfter, String grade, String notes) {}

    /** Output (M5): MAIN and BYPRODUCT need a farm-produce item; WASTE needs a description and is in kg. {@code finish} ends the batch. */
    public record OutputRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "MAIN|BYPRODUCT|WASTE") String role, Long itemId,
                                @Size(max = 120) String description, @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal quantity,
                                @Size(max = 30) String unit, @Size(max = 60) String grade, Long locationId, Boolean finish) {}

    public record Output(long id, LocalDate date, String role, Long itemId, String itemName, String description, BigDecimal quantity, String unit,
                         BigDecimal kg, String grade, String lotNo, Long documentId, String documentNumber, boolean reversed) {}

    /** One output's share of the input weight (M7). */
    public record OutputYield(String role, Long itemId, String name, BigDecimal kg, BigDecimal yieldPercent) {}

    /** Figures for a period (M6, M7); money needs FINANCE_READ (null otherwise). */
    public record Figures(LocalDate from, LocalDate to, Integer days, BigDecimal inputKg, List<OutputYield> outputs, BigDecimal outputKg,
                          BigDecimal lossKg, BigDecimal lossPercent, BigDecimal mainKg, BigDecimal costs, BigDecimal revenue, BigDecimal profit,
                          BigDecimal byproductSales, BigDecimal mainCostPerKg, BigDecimal rawCostPerKg) {}

    public record BatchReport(Batch batch, Figures period, Figures whole) {}

    // ---- Lot trace (M8) ----

    /** Where a lot came from: produced by an activity, bought on a bill, or made by a processing batch. */
    public record Origin(String kind, LocalDate date, String documentNumber, Long costObjectId, String activityName, String activityKind, String partyName,
                         String batchCode) {}

    public record Sale(LocalDate date, String documentNumber, String partyName, BigDecimal quantity) {}

    public record LotUse(String batchCode, String batchName, Long batchId, BigDecimal quantity, List<TracedLot> outputs) {}

    /** A lot with its origin, its inputs (for batch output), the batches that used it and the sales from it. */
    public record TracedLot(long lotId, String lotNo, long itemId, String itemName, String baseUnit, Origin origin, List<TracedLot> inputs,
                            List<LotUse> usedIn, List<Sale> sales) {}
}
