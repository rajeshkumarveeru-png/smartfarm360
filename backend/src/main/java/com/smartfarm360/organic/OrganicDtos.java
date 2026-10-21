package com.smartfarm360.organic;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Compost, vermicompost and liquid manure batches (organic resources design, approved 2026-10-21). Money is rupees. */
public final class OrganicDtos {
    private OrganicDtos() {}

    public record BatchRequest(@NotBlank @Size(max = 140) String name, @NotNull @Pattern(regexp = "COMPOST|VERMICOMPOST|LIQUID|OTHER") String kind,
                               @NotNull Long farmId, @NotNull LocalDate startedOn, LocalDate readyBy, @Size(max = 300) String notes) {}

    /** {@code status}: MAKING (nothing taken out), READY (some output), FINISHED — worked out from the records. */
    public record Batch(long id, long costObjectId, String name, String kind, long farmId, String farmName, LocalDate startedOn, LocalDate readyBy,
                        LocalDate finishedOn, String status, String notes) {}

    public record FinishRequest(@NotNull LocalDate date) {}

    public record LogRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "TURNED|WATERED|ADDED|CHECKED|WORMS|OTHER") String kind,
                             @Positive @Digits(integer = 9, fraction = 3) BigDecimal kg, @Digits(integer = 3, fraction = 1) BigDecimal temperature,
                             @Pattern(regexp = "DRY|RIGHT|WET") String moisture, @Size(max = 300) String notes) {}

    public record Log(long id, LocalDate date, String kind, BigDecimal kg, BigDecimal temperature, String moisture, String notes) {}

    /** Output taken out of the batch into stock as farm produce (L7); {@code finish} marks the batch finished on that date. */
    public record OutputRequest(@NotNull LocalDate date, @NotNull Long itemId, @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity,
                                @Size(max = 30) String unit, Long locationId, Boolean finish) {}

    public record Output(long documentId, String number, LocalDate date, long itemId, String itemName, BigDecimal quantity, String unit, boolean reversed) {}

    /** Figures for a period (L8); money needs FINANCE_READ (null otherwise). */
    public record Figures(LocalDate from, LocalDate to, Integer days, BigDecimal inputKg, BigDecimal output, String outputUnit, BigDecimal yieldPercent,
                          BigDecimal costs, BigDecimal movedOut, BigDecimal revenue, BigDecimal profit, BigDecimal costPerUnit, BigDecimal inStock) {}

    public record BatchReport(Batch batch, Figures period, Figures whole) {}
}
