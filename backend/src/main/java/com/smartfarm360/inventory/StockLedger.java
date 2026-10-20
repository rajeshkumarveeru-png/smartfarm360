package com.smartfarm360.inventory;

import com.smartfarm360.finance.JournalPart;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Writes stock movements and keeps the on-hand projections in step, in the caller's transaction (ADR 0005).
 * Callers lock the items first ({@link #lockItems}); every quantity and value change then happens under that lock.
 * Valuation is the moving weighted average per item across locations, in posting order (E2, E3).
 */
@Component
class StockLedger {
    private final JdbcClient jdbc;

    StockLedger(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** What a movement took or added: signed quantity and value, and whether an expired lot was used. */
    record Moved(long movementId, BigDecimal quantity, BigDecimal value, boolean expiredLot) {}

    /** Locks the items in id order so concurrent records cannot deadlock or interleave their averages. */
    void lockItems(long tenantId, Collection<Long> itemIds) {
        for (Long id : new TreeSet<>(itemIds)) {
            jdbc.sql("select id from sf_stock_items where id = :id and tenant_id = :t for update").param("id", id).param("t", tenantId)
                    .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("STOCK_ITEM_NOT_FOUND", "Stock item not found."));
        }
    }

    /** Stock coming in at a known value (purchase, opening, farm produce at 0, a return at its original cost). */
    Moved receive(long tenantId, long documentId, Long lineId, String type, long itemId, long locationId, Long lotId, BigDecimal quantity,
                  BigDecimal value, LocalDate date, Long costObjectId, Long reverses) {
        return write(tenantId, documentId, lineId, type, itemId, locationId, lotId, quantity, value.setScale(2, RoundingMode.HALF_UP),
                date, costObjectId, reverses);
    }

    /** Stock going out at the item's average cost; the last unit takes the remaining value exactly. */
    Moved issue(long tenantId, long documentId, Long lineId, String type, long itemId, long locationId, Long lotId, BigDecimal quantity,
                LocalDate date, Long costObjectId) {
        requireAvailable(tenantId, itemId, locationId, lotId, quantity);
        return write(tenantId, documentId, lineId, type, itemId, locationId, lotId, quantity.negate(), averageValue(tenantId, itemId, quantity).negate(),
                date, costObjectId, null);
    }

    /** Stock going out at a given value, capped so the item never holds value without quantity or negative value. */
    Moved issueAtValue(long tenantId, long documentId, Long lineId, String type, long itemId, long locationId, Long lotId, BigDecimal quantity,
                       BigDecimal value, LocalDate date, Long costObjectId, Long reverses) {
        requireAvailable(tenantId, itemId, locationId, lotId, quantity);
        Totals t = totals(tenantId, itemId);
        BigDecimal taken = quantity.compareTo(t.quantity()) == 0 ? t.value() : value.min(t.value());
        return write(tenantId, documentId, lineId, type, itemId, locationId, lotId, quantity.negate(), taken.negate(), date, costObjectId, reverses);
    }

    /** Value of {@code quantity} at the current average: round(q × value ÷ quantity, 2), or everything for the last units. */
    BigDecimal averageValue(long tenantId, long itemId, BigDecimal quantity) {
        Totals t = totals(tenantId, itemId);
        if (quantity.compareTo(t.quantity()) >= 0) return t.value();
        if (t.quantity().signum() == 0) return BigDecimal.ZERO.setScale(2);
        return quantity.multiply(t.value()).divide(t.quantity(), 2, RoundingMode.HALF_UP);
    }

    /** Value for stock found in a count: the current average, or the last known cost when none is on hand. */
    BigDecimal gainValue(long tenantId, long itemId, BigDecimal quantity) {
        Totals t = totals(tenantId, itemId);
        if (t.quantity().signum() > 0) return quantity.multiply(t.value()).divide(t.quantity(), 2, RoundingMode.HALF_UP);
        BigDecimal lastCost = jdbc.sql("""
                        select unit_cost from sf_stock_movements where tenant_id = :t and item_id = :i and quantity > 0 and unit_cost > 0
                        order by id desc limit 1""")
                .param("t", tenantId).param("i", itemId).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        return quantity.multiply(lastCost).setScale(2, RoundingMode.HALF_UP);
    }

    record Totals(BigDecimal quantity, BigDecimal value) {}

    Totals totals(long tenantId, long itemId) {
        return jdbc.sql("select on_hand_quantity, on_hand_value from sf_stock_items where id = :i and tenant_id = :t")
                .param("i", itemId).param("t", tenantId).query((rs, n) -> new Totals(rs.getBigDecimal(1), rs.getBigDecimal(2))).single();
    }

    BigDecimal balance(long tenantId, long itemId, long locationId, Long lotId) {
        return jdbc.sql("""
                        select quantity from sf_stock_balances where tenant_id = :t and item_id = :i and location_id = :l
                          and coalesce(lot_id, 0) = coalesce(cast(:lot as bigint), 0) for update""")
                .param("t", tenantId).param("i", itemId).param("l", locationId).param("lot", lotId).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private void requireAvailable(long tenantId, long itemId, long locationId, Long lotId, BigDecimal quantity) {
        BigDecimal available = balance(tenantId, itemId, locationId, lotId);
        if (available.compareTo(quantity) < 0) {
            throw ApiException.conflict("INSUFFICIENT_STOCK", "Only " + available.stripTrailingZeros().toPlainString()
                    + " is in stock here; stock cannot go below zero.");
        }
    }

    private Moved write(long tenantId, long documentId, Long lineId, String type, long itemId, long locationId, Long lotId, BigDecimal quantity,
                        BigDecimal value, LocalDate date, Long costObjectId, Long reverses) {
        BigDecimal unitCost = value.abs().divide(quantity.abs(), 4, RoundingMode.HALF_UP);
        long id = jdbc.sql("""
                        insert into sf_stock_movements (tenant_id, document_id, document_line_id, item_id, location_id, lot_id, movement_type,
                          quantity, value, unit_cost, movement_date, cost_object_id, reverses_movement_id)
                        values (:t, :d, :line, :i, :l, :lot, :type, :q, :v, :c, :date, :co, :rev) returning id""")
                .param("t", tenantId).param("d", documentId).param("line", lineId).param("i", itemId).param("l", locationId).param("lot", lotId)
                .param("type", type).param("q", quantity).param("v", value).param("c", unitCost).param("date", date).param("co", costObjectId)
                .param("rev", reverses).query(Long.class).single();
        jdbc.sql("""
                        update sf_stock_items set on_hand_quantity = on_hand_quantity + :q, on_hand_value = on_hand_value + :v, updated_at = now()
                        where id = :i and tenant_id = :t""")
                .param("q", quantity).param("v", value).param("i", itemId).param("t", tenantId).update();
        if (quantity.signum() > 0) {
            jdbc.sql("""
                            insert into sf_stock_balances (tenant_id, item_id, location_id, lot_id, quantity) values (:t, :i, :l, :lot, :q)
                            on conflict (tenant_id, item_id, location_id, coalesce(lot_id, 0))
                            do update set quantity = sf_stock_balances.quantity + excluded.quantity, updated_at = now()""")
                    .param("t", tenantId).param("i", itemId).param("l", locationId).param("lot", lotId).param("q", quantity).update();
        } else {
            // Stock going out was checked against this (locked) row, so it exists
            int updated = jdbc.sql("""
                            update sf_stock_balances set quantity = quantity + :q, updated_at = now()
                            where tenant_id = :t and item_id = :i and location_id = :l and coalesce(lot_id, 0) = coalesce(cast(:lot as bigint), 0)""")
                    .param("t", tenantId).param("i", itemId).param("l", locationId).param("lot", lotId).param("q", quantity).update();
            if (updated != 1) throw new IllegalStateException("No stock balance for item " + itemId + " at location " + locationId);
        }
        boolean expired = lotId != null && jdbc.sql("select coalesce(expiry_date < :d, false) from sf_stock_lots where id = :id")
                .param("d", date).param("id", lotId).query(Boolean.class).single();
        return new Moved(id, quantity, value, expired);
    }

    // ---- Lots ----------------------------------------------------------------------------------------------------

    /** The lot for an incoming movement, created on first receipt. A lot's expiry is set once and then kept. */
    Long receivingLot(ItemService.ItemRow item, String lotNo, LocalDate expiry, LocalDate date) {
        if (!item.trackLots()) {
            if (lotNo != null) throw ApiException.badRequest("LOT_NOT_TRACKED", "This item does not track lots.");
            return null;
        }
        if (lotNo == null) throw ApiException.badRequest("LOT_REQUIRED", "Enter the lot or batch number.");
        jdbc.sql("""
                        insert into sf_stock_lots (tenant_id, item_id, lot_no, expiry_date, received_on) values (:t, :i, :n, :e, :d)
                        on conflict (tenant_id, item_id, lot_no) do update set expiry_date = coalesce(sf_stock_lots.expiry_date, excluded.expiry_date)""")
                .param("t", item.tenantId()).param("i", item.id()).param("n", lotNo).param("e", expiry).param("d", date).update();
        return existingLot(item, lotNo);
    }

    /** The lot for an outgoing movement; it must exist. */
    Long issuingLot(ItemService.ItemRow item, String lotNo) {
        if (!item.trackLots()) {
            if (lotNo != null) throw ApiException.badRequest("LOT_NOT_TRACKED", "This item does not track lots.");
            return null;
        }
        if (lotNo == null) throw ApiException.badRequest("LOT_REQUIRED", "Choose the lot or batch to take stock from.");
        return existingLot(item, lotNo);
    }

    private Long existingLot(ItemService.ItemRow item, String lotNo) {
        return jdbc.sql("select id from sf_stock_lots where tenant_id = :t and item_id = :i and lot_no = :n")
                .param("t", item.tenantId()).param("i", item.id()).param("n", lotNo).query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("LOT_NOT_FOUND", "There is no lot " + lotNo + " for this item."));
    }

    // ---- Reversal ------------------------------------------------------------------------------------------------

    private record Original(long id, Long lineId, String type, long itemId, long locationId, Long lotId, BigDecimal quantity, BigDecimal value,
                            Long costObjectId) {}

    /**
     * Writes the opposite of every movement of a record (stock coming back first, then stock going out). Stock that
     * leaves at a value other than it came in with (the average moved since) is corrected against {@code account}.
     */
    List<JournalPart> reverse(long tenantId, long documentId, LocalDate date, String account) {
        List<Original> originals = jdbc.sql("""
                        select m.id, m.document_line_id, m.movement_type, m.item_id, m.location_id, m.lot_id, m.quantity, m.value, m.cost_object_id
                        from sf_stock_movements m where m.tenant_id = :t and m.document_id = :d and m.reverses_movement_id is null
                          and not exists (select 1 from sf_stock_movements r where r.reverses_movement_id = m.id)
                        order by m.id""")
                .param("t", tenantId).param("d", documentId)
                .query((rs, i) -> new Original(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                        (Long) rs.getObject(6), rs.getBigDecimal(7), rs.getBigDecimal(8), (Long) rs.getObject(9)))
                .list();
        if (originals.isEmpty()) return List.of();
        lockItems(tenantId, originals.stream().map(Original::itemId).toList());
        List<JournalPart> corrections = new ArrayList<>();
        List<Original> ordered = new ArrayList<>(originals);
        ordered.sort(Comparator.comparing((Original o) -> o.quantity().signum()).thenComparing(Original::id));
        for (Original o : ordered) {
            if (o.quantity().signum() < 0) {
                receive(tenantId, documentId, o.lineId(), o.type(), o.itemId(), o.locationId(), o.lotId(), o.quantity().negate(), o.value().negate(),
                        date, o.costObjectId(), o.id());
            } else {
                BigDecimal available = balance(tenantId, o.itemId(), o.locationId(), o.lotId());
                if (available.compareTo(o.quantity()) < 0) {
                    throw ApiException.conflict("STOCK_ALREADY_USED", "Some of this stock has already been used, sold or moved, so the record "
                            + "cannot be reversed. Record a return or a stock count instead.");
                }
                Moved m = issueAtValue(tenantId, documentId, o.lineId(), o.type(), o.itemId(), o.locationId(), o.lotId(), o.quantity(), o.value(),
                        date, o.costObjectId(), o.id());
                BigDecimal difference = m.value().negate().subtract(o.value());   // taken - original
                if (difference.signum() > 0) {
                    corrections.add(JournalPart.debit(account, difference, null, null, o.costObjectId()));
                    corrections.add(JournalPart.credit(LedgerAccount.STOCK.code(), difference, null, null, null));
                } else if (difference.signum() < 0) {
                    corrections.add(JournalPart.debit(LedgerAccount.STOCK.code(), difference.negate(), null, null, null));
                    corrections.add(JournalPart.credit(account, difference.negate(), null, null, o.costObjectId()));
                }
            }
        }
        return corrections;
    }
}
