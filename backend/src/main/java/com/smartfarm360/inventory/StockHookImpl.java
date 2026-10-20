package com.smartfarm360.inventory;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.JournalPart;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.finance.StockHook;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stock side of bills, sales and opening balances with item lines (stock movement design, S1, S2, S10), and of
 * reversing any record that moved stock (S11).
 */
@Component
class StockHookImpl implements StockHook {
    private final JdbcClient jdbc;
    private final ItemService items;
    private final StockLedger ledger;

    StockHookImpl(JdbcClient jdbc, ItemService items, StockLedger ledger) {
        this.jdbc = jdbc;
        this.items = items;
        this.ledger = ledger;
    }

    @Override
    public ResolvedItem resolve(TenantContext ctx, DocType type, long itemId, Long locationId, BigDecimal quantity, String unit, String lotNo) {
        ItemService.ItemRow item = items.itemRow(ctx.tenantId(), itemId, false);
        if (!item.active()) throw ApiException.badRequest("STOCK_ITEM_INACTIVE", "This stock item is no longer in use.");
        if (item.trackLots() && lotNo == null) throw ApiException.badRequest("LOT_REQUIRED", "Enter the lot or batch number.");
        if (!item.trackLots() && lotNo != null) throw ApiException.badRequest("LOT_NOT_TRACKED", "This item does not track lots.");
        long location = items.activeLocation(ctx.tenantId(), locationId);
        ItemService.Converted converted = items.toBase(item, quantity, unit);
        return new ResolvedItem(item.id(), item.name(), location, converted.baseQuantity(), converted.unitLabel(), item.saleCategoryId(), item.trackLots());
    }

    @Override
    public List<JournalPart> post(TenantContext ctx, long documentId, DocType type, LocalDate date, List<ItemLine> lines) {
        long t = ctx.tenantId();
        ledger.lockItems(t, lines.stream().map(ItemLine::itemId).toList());
        List<JournalPart> parts = new ArrayList<>();
        for (ItemLine l : lines) {
            ItemService.ItemRow item = items.itemRow(t, l.itemId(), false);
            switch (type) {
                case BILL, OPENING_BALANCE -> ledger.receive(t, documentId, l.lineId(), type == DocType.BILL ? "PURCHASE" : "OPENING", item.id(),
                        l.locationId(), ledger.receivingLot(item, l.lotNo(), l.lotExpiry(), date), l.baseQuantity(), l.amount(), date, l.costObjectId(), null);
                case SALE -> {
                    StockLedger.Moved m = ledger.issue(t, documentId, l.lineId(), "SALE", item.id(), l.locationId(), ledger.issuingLot(item, l.lotNo()),
                            l.baseQuantity(), date, l.costObjectId());
                    BigDecimal cost = m.value().negate();
                    if (cost.signum() > 0) {
                        parts.add(new JournalPart(LedgerAccount.COST_OF_GOODS_SOLD.code(), cost, BigDecimal.ZERO, null, null, null, l.costObjectId(), l.farmId()));
                        parts.add(new JournalPart(LedgerAccount.STOCK.code(), BigDecimal.ZERO, cost, null, null, null, null, l.farmId()));
                    }
                }
                default -> throw new IllegalArgumentException(type + " lines cannot name stock items");
            }
        }
        return parts;
    }

    @Override
    public List<JournalPart> reverse(TenantContext ctx, long documentId, LocalDate date) {
        String type = jdbc.sql("select doc_type from sf_finance_documents where id = :id and tenant_id = :t")
                .param("id", documentId).param("t", ctx.tenantId()).query(String.class).single();
        String account = switch (type) {
            case "SALE_RETURN", "SALE" -> LedgerAccount.COST_OF_GOODS_SOLD.code();
            case "STOCK_COUNT", "WASTAGE" -> LedgerAccount.LOSSES.code();
            default -> LedgerAccount.FARM_INPUTS.code();
        };
        return ledger.reverse(ctx.tenantId(), documentId, date, account);
    }

    @Override
    public List<Movement> movements(long tenantId, long documentId) {
        return jdbc.sql("""
                        select m.id, m.item_id, i.name, coalesce(i.custom_unit_name, i.base_unit_code), m.location_id, l.name, lot.lot_no,
                               m.movement_type, m.quantity, m.value, m.unit_cost, m.cost_object_id
                        from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        join sf_stock_locations l on l.id = m.location_id and l.tenant_id = m.tenant_id
                        left join sf_stock_lots lot on lot.id = m.lot_id and lot.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.document_id = :d order by m.id""")
                .param("t", tenantId).param("d", documentId)
                .query((rs, i) -> new Movement(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11), (Long) rs.getObject(12)))
                .list();
    }
}
