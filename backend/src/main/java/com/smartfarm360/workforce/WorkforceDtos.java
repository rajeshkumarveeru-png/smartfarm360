package com.smartfarm360.workforce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Workforce API records (workforce and assets design, approved 2026-10-20, sections 3–5). Money is rupees. */
public final class WorkforceDtos {
    private WorkforceDtos() {}

    public static final String PAY_TYPES = "DAILY|MONTHLY|PIECE";
    public static final String ATTENDANCE = "PRESENT|HALF_DAY|ABSENT|PAID_LEAVE|UNPAID_LEAVE";

    // ---- Workers ----

    /** {@code rate}: per day (daily), per month (monthly) or per {@code pieceUnit} (piece rate). */
    public record WorkerRequest(@NotBlank @Size(max = 140) String name, @Size(max = 30) String phone, @Size(max = 120) String village,
                                @NotNull @Pattern(regexp = PAY_TYPES) String payType,
                                @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal rate, @Size(max = 30) String pieceUnit,
                                @NotNull LocalDate joinedOn, @Size(max = 120) String usualWork, @Size(max = 500) String notes) {}

    /** {@code leftOn}: the last day worked; refused while the worker is owed wages or owes an advance. */
    public record WorkerPatch(@Size(min = 1, max = 140) String name, @Size(max = 30) String phone, @Size(max = 120) String village,
                              @Size(max = 120) String usualWork, @Size(max = 500) String notes, LocalDate leftOn, Boolean rejoin) {}

    public record RateRequest(@NotNull LocalDate effectiveFrom, @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal rate,
                              @Size(max = 30) String pieceUnit) {}

    public record Rate(LocalDate effectiveFrom, BigDecimal rate, String pieceUnit) {}

    /** {@code owed}: wages earned and not yet paid (2100); {@code advance}: advances not yet recovered (1150). */
    public record Worker(long id, long partyId, String name, String phone, String village, String payType, BigDecimal rate, String pieceUnit,
                         LocalDate joinedOn, LocalDate leftOn, boolean active, String usualWork, String notes, BigDecimal owed,
                         BigDecimal advance, List<Rate> rates) {}

    // ---- Attendance and piece work ----

    /** {@code costObjectId}: the crop cycle (cost object) worked on; empty means farm overhead (G3). */
    public record AttendanceEntry(@NotNull Long workerId, @Pattern(regexp = ATTENDANCE) String status,
                                  @PositiveOrZero @Max(24) @Digits(integer = 2, fraction = 2) BigDecimal hours, Long costObjectId,
                                  @Size(max = 300) String notes) {}

    /** A day's muster roll. An entry with no status clears that worker's day. */
    public record MusterRequest(@NotEmpty @Size(max = 500) List<@Valid AttendanceEntry> entries) {}

    /**
     * {@code locked}: used by a posted pay run; reverse the pay run to change it (principle 4). {@code paidByBill}: the worker is
     * also on a posted labour bill for this day, so marking them present would pay the day twice (G7).
     */
    public record AttendanceRow(long workerId, String workerName, String payType, String status, BigDecimal hours, Long costObjectId,
                                String costObjectName, String notes, boolean locked, boolean paidByBill) {}

    public record Muster(LocalDate date, List<AttendanceRow> workers) {}

    public record MonthRow(long workerId, String workerName, String payType, BigDecimal present, BigDecimal halfDays, BigDecimal absent,
                           BigDecimal paidLeave, BigDecimal unpaidLeave, BigDecimal days) {}

    public record MonthSheet(String month, List<MonthRow> workers) {}

    public record PieceWorkRequest(@NotNull Long workerId, @NotNull LocalDate workDate,
                                   @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity, @Size(max = 30) String unit,
                                   @PositiveOrZero @Digits(integer = 8, fraction = 4) BigDecimal rate, Long costObjectId,
                                   @Size(max = 300) String notes) {}

    public record PieceWork(long id, long workerId, String workerName, LocalDate workDate, BigDecimal quantity, String unit, BigDecimal rate,
                            BigDecimal amount, Long costObjectId, String costObjectName, String notes, boolean locked) {}

    // ---- Advances, pay runs, payments ----

    public record AdvanceRequest(@NotNull Long workerId, @NotNull LocalDate date, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 @NotNull Long financialAccountId, @Size(max = 500) String notes) {}

    /** Overtime, bonus, food allowance: an amount with a reason (G5). */
    public record Extra(@NotNull Long workerId, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                        @NotBlank @Size(max = 120) String reason, Long costObjectId) {}

    /**
     * A pay run for a period (G4). {@code workerIds} empty means every worker who can be paid for the period: daily and
     * piece workers always, monthly workers only for whole calendar months.
     */
    public record PayRunRequest(@NotNull LocalDate from, @NotNull LocalDate to, LocalDate date, @Size(max = 500) List<Long> workerIds,
                                @Size(max = 200) List<@Valid Extra> extras, @Size(max = 500) String notes) {}

    public record CostShare(Long costObjectId, String costObjectName, BigDecimal days, BigDecimal amount) {}

    /** {@code notes}: e.g. NO_RATE, PARTIAL_MONTH (a monthly worker in a period that is not whole months). */
    public record PayRunWorker(long workerId, String name, String payType, BigDecimal days, BigDecimal timePay, BigDecimal piecePay,
                               BigDecimal extraPay, BigDecimal gross, List<CostShare> costs, BigDecimal advance, BigDecimal owedBefore,
                               List<String> notes) {}

    public record PayRunPreview(LocalDate from, LocalDate to, List<PayRunWorker> workers, BigDecimal total, List<String> skipped) {}

    /** {@code cash}: paid now; {@code advanceRecovered}: deducted from the worker's advance (W3). */
    public record Payment(@NotNull Long workerId, @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal cash,
                          @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal advanceRecovered) {}

    public record WagePaymentRequest(@NotNull LocalDate date, Long financialAccountId, @NotEmpty @Size(max = 200) List<@Valid Payment> payments,
                                     @Size(max = 500) String notes) {}

    public record RecordRef(long documentId, String number, long workerId, BigDecimal amount) {}

    // ---- Statements and reports ----

    /** {@code wages}: earned (pay run); {@code paid}: settled by payment; {@code balance}: owed minus advance after the line. */
    public record StatementLine(long documentId, String documentType, String number, LocalDate date, BigDecimal wages, BigDecimal paid,
                                BigDecimal advanceGiven, BigDecimal advanceRecovered, BigDecimal balance, boolean reversal) {}

    public record Statement(long workerId, String name, LocalDate from, LocalDate to, BigDecimal opening, List<StatementLine> lines, BigDecimal closing,
                            BigDecimal owed, BigDecimal advance) {}

    public record LabourRow(Long costObjectId, String name, String kind, BigDecimal days, BigDecimal cost) {}

    public record LabourByActivity(LocalDate from, LocalDate to, List<LabourRow> rows, BigDecimal totalCost) {}

    /** Wages owed and advances by worker against the ledger (2100, 1150); differences must be zero. */
    public record Reconciliation(BigDecimal workersOwed, BigDecimal ledgerWagesPayable, BigDecimal workersAdvance, BigDecimal ledgerAdvances) {}
}
