package com.smartfarm360.finance;

import com.smartfarm360.platform.security.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How finance records reach stock without finance depending on the stock module (ADR 0001): bill, sale and
 * opening-balance lines can name a stock item, and the stock module, which implements this, turns them into movements.
 */
public interface StockHook {
    /** An item line checked against the item: quantity in the item's base unit, location, and the item's default category. */
    record ResolvedItem(long itemId, String itemName, long locationId, BigDecimal baseQuantity, String unitLabel,
                        Long saleCategoryId, boolean trackLots, Long lotCostObjectId) {}

    /** A posted line that names a stock item. */
    record ItemLine(long lineId, long itemId, long locationId, String lotNo, LocalDate lotExpiry, BigDecimal baseQuantity,
                    BigDecimal amount, Long costObjectId, Long farmId) {}

    /** A stock movement as shown on a record. */
    record Movement(long id, long itemId, String itemName, String baseUnit, long locationId, String locationName, String lotNo,
                    String movementType, BigDecimal quantity, BigDecimal value, BigDecimal unitCost, Long costObjectId) {}

    ResolvedItem resolve(TenantContext ctx, DocType type, long itemId, Long locationId, BigDecimal quantity, String unit, String lotNo);

    /** Writes the movements of a posted record's item lines and returns extra journal lines (cost of goods sold). */
    List<JournalPart> post(TenantContext ctx, long documentId, DocType type, LocalDate date, List<ItemLine> lines);

    /**
     * Writes opposite movements for a record being reversed and returns journal lines that correct the mirror entry
     * where stock could not leave at its original value. Refuses when the reversal would make stock negative.
     */
    List<JournalPart> reverse(TenantContext ctx, long documentId, LocalDate date);

    List<Movement> movements(long tenantId, long documentId);
}
