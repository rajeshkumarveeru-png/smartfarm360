package com.smartfarm360.inventory;

import com.smartfarm360.finance.FinanceDtos.Document;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request and response records for the stock API (stock movement design, approved 2026-10-20). Quantities are
 * entered in any of an item's units and kept in its base unit; values are rupees.
 */
public final class StockDtos {
    private StockDtos() {}

    // ---- Units, items, locations, lots ----------------------------------------------------------------------

    public record Unit(String code, String dimension, BigDecimal factor) {}

    public record PackUnitRequest(@NotBlank @Size(max = 30) String name, @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal factor) {}

    public record PackUnit(long id, String name, BigDecimal factor) {}

    /** kind: INPUT (bought and used), PRODUCE (grown or made on the farm, carried at nil value), RESALE (bought to sell). */
    public record ItemRequest(@NotBlank @Size(max = 140) String name, @NotNull @Pattern(regexp = "INPUT|PRODUCE|RESALE") String kind,
                              @NotBlank @Size(max = 20) String baseUnit, Long expenseCategoryId, Long saleCategoryId,
                              @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal reorderLevel, Boolean trackLots,
                              @Size(max = 300) String notes, @Size(max = 10) List<@Valid PackUnitRequest> packUnits) {}

    /** The base unit cannot change once an item has movements. */
    public record ItemPatch(@Size(min = 1, max = 140) String name, @Pattern(regexp = "INPUT|PRODUCE|RESALE") String kind, Long expenseCategoryId,
                            Long saleCategoryId, @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal reorderLevel,
                            Boolean trackLots, @Size(max = 300) String notes, Boolean active, Boolean reviewed) {}

    /**
     * {@code baseUnit} is a unit code (translated by clients) unless {@code customUnitName} is set (a unit the farmer
     * named, kept from Phase 1). {@code unitCost} is value ÷ quantity to 4 decimals.
     */
    public record Item(long id, String name, String kind, String baseUnit, String customUnitName, String dimension,
                       Long expenseCategoryId, Long saleCategoryId, BigDecimal reorderLevel, boolean trackLots, BigDecimal quantity,
                       BigDecimal value, BigDecimal unitCost, boolean lowStock, boolean active, boolean needsReview, String notes,
                       List<PackUnit> packUnits) {}

    public record LocationRequest(@NotBlank @Size(max = 80) String name, Long farmId) {}

    public record LocationPatch(@Size(min = 1, max = 80) String name, Long farmId, Boolean active, Boolean makeDefault) {}

    public record Location(long id, String name, Long farmId, boolean isDefault, boolean active) {}

    public record Lot(long id, long itemId, String lotNo, LocalDate expiryDate, LocalDate receivedOn, BigDecimal quantity, boolean expired) {}

    // ---- Stock records ----------------------------------------------------------------------------------------

    /** One item on a stock record. {@code lotNo} is required for items that track lots. */
    public record ItemQuantity(@NotNull Long itemId, @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity,
                               @Size(max = 30) String unit, @Size(max = 60) String lotNo, LocalDate lotExpiry) {}

    /** Using stock on an activity (a crop cycle) or on the farm in general (no cost object). */
    public record IssueRequest(@NotNull LocalDate docDate, Long locationId, Long costObjectId, Long farmId,
                               @NotEmpty @Size(max = 100) List<@Valid ItemQuantity> lines, @Size(max = 500) String notes, Long assetId) {
        public IssueRequest(LocalDate docDate, Long locationId, Long costObjectId, Long farmId, List<ItemQuantity> lines, String notes) {
            this(docDate, locationId, costObjectId, farmId, lines, notes, null);
        }
    }

    public record TransferRequest(@NotNull LocalDate docDate, @NotNull Long fromLocationId, @NotNull Long toLocationId,
                                  @NotEmpty @Size(max = 100) List<@Valid ItemQuantity> lines, @Size(max = 500) String notes) {}

    public record WastageRequest(@NotNull LocalDate docDate, Long locationId, @NotBlank @Size(max = 500) String reason,
                                 @NotEmpty @Size(max = 100) List<@Valid ItemQuantity> lines) {}

    /** {@code countedQuantity} may be 0. */
    public record CountLine(@NotNull Long itemId, @NotNull @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal countedQuantity,
                            @Size(max = 30) String unit, @Size(max = 60) String lotNo) {}

    public record CountRequest(@NotNull LocalDate docDate, Long locationId, @NotEmpty @Size(max = 200) List<@Valid CountLine> lines,
                               @Size(max = 500) String notes) {}

    /** Farm produce into stock (harvest, milk, eggs, compost) at nil value, optionally from an activity. */
    public record ProduceRequest(@NotNull LocalDate docDate, Long locationId, Long costObjectId, Long farmId,
                                 @NotEmpty @Size(max = 100) List<@Valid ItemQuantity> lines, @Size(max = 500) String notes) {}

    /** {@code amount}: the credit or refund agreed for the line (0 when nothing is given back). */
    public record ReturnLine(@NotNull Long itemId, @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity,
                             @Size(max = 30) String unit, @Size(max = 60) String lotNo,
                             @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

    /**
     * Stock sent back to a supplier. With a party the credit reduces what we owe; without one, {@code financialAccountId}
     * receives the refund. {@code originalDocumentId}: the bill it came on, matched first.
     */
    public record PurchaseReturnRequest(@NotNull LocalDate docDate, Long partyId, Long financialAccountId, Long originalDocumentId,
                                        Long locationId, @NotEmpty @Size(max = 100) List<@Valid ReturnLine> lines,
                                        @Size(max = 500) String notes, @Size(max = 100) String referenceNo) {}

    /**
     * Stock a customer brings back from a sale, which comes back at the cost it left with. The sale's party is
     * credited; a cash sale is refunded from {@code financialAccountId}.
     */
    public record SaleReturnRequest(@NotNull LocalDate docDate, @NotNull Long originalDocumentId, Long financialAccountId, Long locationId,
                                    @NotEmpty @Size(max = 100) List<@Valid ReturnLine> lines, @Size(max = 500) String notes,
                                    @Size(max = 100) String referenceNo) {}

    /** A posted stock record. {@code warnings}: e.g. LOT_EXPIRED when an expired lot was used (allowed, E6). */
    public record StockRecordResult(Document document, List<String> warnings) {}

    // ---- Reports ----------------------------------------------------------------------------------------------

    public record LocationQuantity(long locationId, String locationName, Long lotId, String lotNo, LocalDate expiryDate, BigDecimal quantity) {}

    public record OnHandRow(Item item, List<LocationQuantity> locations) {}

    public record OnHand(LocalDate asOf, List<OnHandRow> items, BigDecimal totalValue) {}

    public record ValuationRow(long itemId, String name, String baseUnit, String customUnitName, BigDecimal quantity, BigDecimal value) {}

    public record Valuation(LocalDate asOf, List<ValuationRow> items, BigDecimal totalValue) {}

    public record MovementRow(long id, LocalDate date, long documentId, String documentType, String documentNumber, String movementType,
                              String locationName, String lotNo, BigDecimal quantity, BigDecimal value, BigDecimal unitCost,
                              BigDecimal balanceQuantity, BigDecimal balanceValue, String costObjectName, boolean reversal) {}

    /** {@code difference} must be 0: Stock (1200) in the ledger equals the value of stock on hand. */
    public record Reconciliation(BigDecimal ledgerBalance, BigDecimal stockValue, BigDecimal difference) {}

    public record Alerts(List<Item> lowStock, List<ExpiringLot> expiringLots, int expiryWarningDays) {}

    public record ExpiringLot(long lotId, long itemId, String itemName, String lotNo, LocalDate expiryDate, BigDecimal quantity, boolean expired) {}
}
