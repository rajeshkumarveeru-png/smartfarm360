package com.smartfarm360.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request and response records for the finance API. Amounts are rupees with at most two decimals. */
public final class FinanceDtos {
    private FinanceDtos() {}

    // ---- Master data ----------------------------------------------------------------------------------------

    public record CategoryRequest(@NotBlank @Size(max = 80) String name,
                                  @NotNull @Pattern(regexp = "EXPENSE|INCOME|ASSET") String kind,
                                  @NotBlank @Size(max = 10) String accountCode) {}

    public record CategoryPatch(@Size(min = 1, max = 80) String name, Boolean active) {}

    /** {@code code} is set for system categories (translated by clients); {@code name} for tenant categories. */
    public record Category(long id, String code, String name, String kind, String accountCode, boolean system, boolean active) {}

    public record FinancialAccountRequest(@NotBlank @Size(max = 80) String name, @NotNull @Pattern(regexp = "CASH|BANK|UPI") String kind) {}

    public record FinancialAccountPatch(@Size(min = 1, max = 80) String name, Boolean active) {}

    public record FinancialAccount(long id, String name, String kind, boolean active, BigDecimal balance) {}

    public record PartyRequest(@NotBlank @Size(max = 140) String name, @Size(max = 30) String phone, @Size(max = 120) String village,
                               @Size(max = 300) String notes, boolean customer, boolean supplier, boolean lender) {}

    public record PartyPatch(@Size(min = 1, max = 140) String name, @Size(max = 30) String phone, @Size(max = 120) String village,
                             @Size(max = 300) String notes, Boolean customer, Boolean supplier, Boolean lender, Boolean active) {}

    /** Balances are positive amounts; an advance is money paid/received before a bill/sale exists. */
    public record Party(long id, String name, String phone, String village, String notes, boolean customer, boolean supplier,
                        boolean lender, boolean active, BigDecimal receivable, BigDecimal payable, boolean worker) {}

    public record Loan(long id, String name, long lenderPartyId, String lenderName, LocalDate startDate, BigDecimal outstanding) {}

    public record CostObject(long id, String kind, String name, Long cropCycleId, Long farmId, boolean active) {}

    public record FinanceSettings(LocalDate lockDate, int fiscalYearStartMonth) {}

    public record FinanceSettingsRequest(LocalDate lockDate, @NotNull @Min(1) @Max(12) Integer fiscalYearStartMonth) {}

    // ---- Documents ------------------------------------------------------------------------------------------

    /**
     * Either {@code amount}, or {@code quantity} and {@code rate} (amount = quantity × rate rounded to the paisa).
     * A line is a category line, or (with {@code itemId}) a stock item line: on a bill it adds stock at the line's
     * cost; on a sale it takes stock out at average cost and the category defaults to the item's sale category.
     * {@code unit} is the item's base unit, a unit of the same kind, or one of the item's pack units.
     */
    public record LineRequest(Long categoryId, @Size(max = 240) String description,
                              @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity, @Size(max = 30) String unit,
                              @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal rate,
                              @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                              Long costObjectId, Long farmId, Long itemId, Long locationId, @Size(max = 60) String lotNo,
                              LocalDate lotExpiry, Long assetId) {
        public LineRequest(Long categoryId, String description, BigDecimal quantity, String unit, BigDecimal rate, BigDecimal amount,
                           Long costObjectId, Long farmId) {
            this(categoryId, description, quantity, unit, rate, amount, costObjectId, farmId, null, null, null, null, null);
        }
    }

    public record PaidNow(@NotNull Long financialAccountId, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

    /**
     * A bill (expense) or sale. With a party, unpaid amounts stay on the party's balance and {@code paidNow} records a
     * payment at the same time. Without a party it is a cash record: {@code financialAccountId} is required and it is
     * fully paid. {@code post=false} saves a draft.
     */
    public record InvoiceRequest(@NotNull LocalDate docDate, LocalDate dueDate, Long partyId, Long farmId, Long financialAccountId,
                                 @NotEmpty @Size(max = 100) List<@Valid LineRequest> lines, @Size(max = 500) String notes,
                                 @Size(max = 100) String referenceNo, Boolean post, @Valid PaidNow paidNow) {}

    public record AllocationRequest(@NotNull Long documentId, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

    /** A payment (we pay a party) or receipt (a party pays us), optionally matched to open bills/sales. */
    public record PaymentRequest(@NotNull LocalDate docDate, @NotNull Long partyId, @NotNull Long financialAccountId,
                                 @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 @Size(max = 100) List<@Valid AllocationRequest> allocations,
                                 @Size(max = 500) String notes, @Size(max = 100) String referenceNo) {}

    public record TransferRequest(@NotNull LocalDate docDate, @NotNull Long fromAccountId, @NotNull Long toAccountId,
                                  @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @Size(max = 500) String notes) {}

    /** IN = owner puts money into the farm; OUT = household/personal spending from farm money (drawings, D2). */
    public record OwnerMoneyRequest(@NotNull LocalDate docDate, @NotNull @Pattern(regexp = "IN|OUT") String direction,
                                    @NotNull Long financialAccountId, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                    @Size(max = 500) String notes) {}

    public record LoanRequest(@NotNull LocalDate docDate, @NotNull Long lenderPartyId, @NotBlank @Size(max = 140) String loanName,
                              @NotNull Long financialAccountId, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                              @Size(max = 500) String notes) {}

    public record LoanRepaymentRequest(@NotNull LocalDate docDate, @NotNull Long loanId, @NotNull Long financialAccountId,
                                       @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal principal,
                                       @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal interest,
                                       @Size(max = 500) String notes) {}

    public record SettlementRequest(@NotNull LocalDate docDate, @NotNull Long partyId,
                                    @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @Size(max = 500) String notes) {}

    public record WriteOffRequest(@NotNull LocalDate docDate, @NotNull Long partyId,
                                  @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, @Size(max = 500) String notes) {}

    /**
     * kind: CASH (financialAccountId), RECEIVABLE / PAYABLE (partyId), LOAN (partyId = lender, loanName), ASSET, or
     * STOCK (itemId, quantity, unit, optional locationId and lot; amount is the stock's value and may be 0 for farm produce).
     */
    public record OpeningLine(@NotNull @Pattern(regexp = "CASH|RECEIVABLE|PAYABLE|LOAN|ASSET|STOCK") String kind, Long financialAccountId,
                              Long partyId, @Size(max = 140) String loanName, @Size(max = 240) String description,
                              @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal amount,
                              Long itemId, @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity, @Size(max = 30) String unit,
                              Long locationId, @Size(max = 60) String lotNo, LocalDate lotExpiry) {
        public OpeningLine(String kind, Long financialAccountId, Long partyId, String loanName, String description, BigDecimal amount) {
            this(kind, financialAccountId, partyId, loanName, description, amount, null, null, null, null, null, null);
        }
    }

    public record OpeningBalanceRequest(@NotNull LocalDate docDate, @NotEmpty @Size(max = 200) List<@Valid OpeningLine> lines,
                                        @Size(max = 500) String notes) {}

    public record DepreciationRequest(@NotNull LocalDate docDate, @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                      Long costObjectId, @Size(max = 500) String notes) {}

    public record ReverseRequest(LocalDate reversalDate) {}

    /** Spreads farm overhead of a period to the crop cycles growing in it (P16, crop design F6). Posted on {@code to}. */
    public record OverheadAllocationRequest(@NotNull LocalDate from, @NotNull LocalDate to, @Size(max = 500) String notes) {}

    /** One activity's share: its weight is area (acres) × days growing in the period. */
    public record AllocationShare(long costObjectId, String name, BigDecimal areaAcres, long days, BigDecimal weight, BigDecimal amount) {}

    /** Overhead of one expense category (and farm, when the cost named one) and how it is spread. Empty shares: nothing to spread it to. */
    public record AllocationGroup(String accountCode, Long categoryId, String categoryCode, String categoryName, Long farmId, BigDecimal amount,
                                  List<AllocationShare> shares) {}

    public record AllocationPreview(LocalDate from, LocalDate to, List<AllocationGroup> groups, BigDecimal allocated, BigDecimal notAllocated) {}

    public record DocumentLine(long id, int lineNo, Long categoryId, String categoryCode, String categoryName, String accountCode,
                               String side, String description, BigDecimal quantity, String unit, BigDecimal rate, BigDecimal amount,
                               Long costObjectId, String costObjectName, Long farmId, Long partyId, Long financialAccountId, Long loanId,
                               Long itemId, String itemName, Long locationId, String lotNo, BigDecimal baseQuantity, Long assetId) {}

    public record Allocation(long id, long sourceDocumentId, String sourceNumber, String sourceType, long targetDocumentId,
                             String targetNumber, String targetType, BigDecimal amount, LocalDate allocationDate) {}

    /** paymentStatus (bills and sales only): UNPAID, PARTLY_PAID or PAID, computed from allocations. */
    public record DocumentSummary(long id, String type, String status, String number, LocalDate docDate, LocalDate dueDate,
                                  Long partyId, String partyName, String financialAccountName, BigDecimal totalAmount,
                                  BigDecimal settledAmount, BigDecimal openAmount, String paymentStatus, boolean needsReview,
                                  Instant createdAt) {}

    public record Document(DocumentSummary summary, Long farmId, Long financialAccountId, Long toFinancialAccountId,
                           String toFinancialAccountName, Long loanId, String loanName, BigDecimal principalAmount,
                           BigDecimal interestAmount, String notes, String referenceNo, LocalDate reversalDate, Instant postedAt,
                           Long originalDocumentId, List<DocumentLine> lines, List<Allocation> allocations,
                           List<StockHook.Movement> movements) {}
}
