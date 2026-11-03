package com.smartfarm360.inventory;

import com.smartfarm360.inventory.StockDtos.*;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Stock reports (stock movement design, sections 8–9). All figures come from movements and the on-hand projection. */
@Service
public class StockReportService {
    static final int EXPIRY_WARNING_DAYS = 30;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final JdbcClient jdbc;
    private final ItemService items;

    public StockReportService(JdbcClient jdbc, ItemService items) {
        this.jdbc = jdbc;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public OnHand onHand(TenantContext ctx, Long locationId) {
        if (locationId != null) items.location(ctx.tenantId(), locationId);
        Map<Long, List<LocationQuantity>> byItem = jdbc.sql("""
                        select b.item_id, b.location_id, l.name, b.lot_id, lot.lot_no, lot.expiry_date, b.quantity
                        from sf_stock_balances b join sf_stock_locations l on l.id = b.location_id and l.tenant_id = b.tenant_id
                        left join sf_stock_lots lot on lot.id = b.lot_id and lot.tenant_id = b.tenant_id
                        where b.tenant_id = :t and b.quantity > 0 and (cast(:loc as bigint) is null or b.location_id = :loc)
                        order by l.is_default desc, lower(l.name), lot.expiry_date nulls last, lot.lot_no""")
                .param("t", ctx.tenantId()).param("loc", locationId)
                .query((rs, i) -> Map.entry(rs.getLong(1), new LocationQuantity(rs.getLong(2), rs.getString(3), (Long) rs.getObject(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getBigDecimal(7))))
                .list().stream().collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        List<OnHandRow> rows = new ArrayList<>();
        BigDecimal total = ZERO;
        for (Item item : items.items(ctx, false)) {
            List<LocationQuantity> locations = byItem.getOrDefault(item.id(), List.of());
            if (locationId != null && locations.isEmpty()) continue;
            rows.add(new OnHandRow(item, locations));
            total = total.add(item.value());
        }
        return new OnHand(LocalDate.now(), rows, total);
    }

    /** Quantity and value of each item on a past date, recomputed from the movements dated up to then. */
    @Transactional(readOnly = true)
    public Valuation valuation(TenantContext ctx, LocalDate asOf) {
        List<ValuationRow> rows = jdbc.sql("""
                        select i.id, i.name, i.base_unit_code, i.custom_unit_name, coalesce(sum(m.quantity), 0), coalesce(sum(m.value), 0)
                        from sf_stock_items i left join sf_stock_movements m on m.item_id = i.id and m.tenant_id = i.tenant_id and m.movement_date <= :d
                        where i.tenant_id = :t group by i.id, i.name, i.base_unit_code, i.custom_unit_name
                        having coalesce(sum(m.quantity), 0) <> 0 or coalesce(sum(m.value), 0) <> 0 order by lower(i.name)""")
                .param("t", ctx.tenantId()).param("d", asOf)
                .query((rs, i) -> new ValuationRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6).setScale(2)))
                .list();
        return new Valuation(asOf, rows, rows.stream().map(ValuationRow::value).reduce(ZERO, BigDecimal::add));
    }

    /** Movements of one item, newest first, with the item's running quantity and value after each. */
    @Transactional(readOnly = true)
    public PageResponse<MovementRow> movements(TenantContext ctx, long itemId, int page, int size) {
        PageResponse.check(page, size);
        items.itemRow(ctx.tenantId(), itemId, false);
        long total = jdbc.sql("select count(*) from sf_stock_movements where tenant_id = :t and item_id = :i")
                .param("t", ctx.tenantId()).param("i", itemId).query(Long.class).single();
        List<MovementRow> rows = jdbc.sql("""
                        select * from (
                          select m.id, m.movement_date, m.document_id, d.doc_type, d.number, m.movement_type, l.name as location, lot.lot_no,
                                 m.quantity, m.value, m.unit_cost,
                                 sum(m.quantity) over (order by m.id) as bal_qty, sum(m.value) over (order by m.id) as bal_value,
                                 co.name as cost_object, m.reverses_movement_id is not null as reversal
                          from sf_stock_movements m
                          join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                          join sf_stock_locations l on l.id = m.location_id and l.tenant_id = m.tenant_id
                          left join sf_stock_lots lot on lot.id = m.lot_id and lot.tenant_id = m.tenant_id
                          left join sf_cost_objects co on co.id = m.cost_object_id and co.tenant_id = m.tenant_id
                          where m.tenant_id = :t and m.item_id = :i) x
                        order by id desc limit :limit offset :offset""")
                .param("t", ctx.tenantId()).param("i", itemId).param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> new MovementRow(rs.getLong("id"), rs.getObject("movement_date", LocalDate.class), rs.getLong("document_id"),
                        rs.getString("doc_type"), rs.getString("number"), rs.getString("movement_type"), rs.getString("location"), rs.getString("lot_no"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("value"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("bal_qty"),
                        rs.getBigDecimal("bal_value"), rs.getString("cost_object"), rs.getBoolean("reversal")))
                .list();
        return PageResponse.of(rows, page, size, total);
    }

    /** Stock (1200) in the ledger against the value of stock on hand; any difference is a defect. */
    @Transactional(readOnly = true)
    public Reconciliation reconciliation(TenantContext ctx) {
        BigDecimal ledger = jdbc.sql("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = :t and account_code = '1200'")
                .param("t", ctx.tenantId()).query(BigDecimal.class).single().setScale(2);
        BigDecimal stock = jdbc.sql("select coalesce(sum(on_hand_value), 0) from sf_stock_items where tenant_id = :t")
                .param("t", ctx.tenantId()).query(BigDecimal.class).single().setScale(2);
        return new Reconciliation(ledger, stock, ledger.subtract(stock));
    }

    @Transactional(readOnly = true)
    public Alerts alerts(TenantContext ctx) {
        List<Item> low = items.items(ctx, false).stream().filter(Item::lowStock).toList();
        LocalDate today = LocalDate.now();
        List<ExpiringLot> lots = jdbc.sql("""
                        select lot.id, lot.item_id, i.name, lot.lot_no, lot.expiry_date, sum(b.quantity)
                        from sf_stock_lots lot join sf_stock_items i on i.id = lot.item_id and i.tenant_id = lot.tenant_id
                        join sf_stock_balances b on b.lot_id = lot.id and b.tenant_id = lot.tenant_id
                        where lot.tenant_id = :t and lot.expiry_date is not null and lot.expiry_date <= :until and i.active
                        group by lot.id, lot.item_id, i.name, lot.lot_no, lot.expiry_date having sum(b.quantity) > 0
                        order by lot.expiry_date, lower(i.name)""")
                .param("t", ctx.tenantId()).param("until", today.plusDays(EXPIRY_WARNING_DAYS))
                .query((rs, i) -> {
                    LocalDate expiry = rs.getObject(5, LocalDate.class);
                    return new ExpiringLot(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), expiry, rs.getBigDecimal(6), expiry.isBefore(today));
                }).list();
        return new Alerts(low, lots, EXPIRY_WARNING_DAYS);
    }

    // ---- CSV exports (E9) ------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public String onHandCsv(TenantContext ctx) {
        StringBuilder csv = new StringBuilder("item,kind,unit,quantity,unit_cost,value,reorder_level,low_stock\r\n");
        for (OnHandRow row : onHand(ctx, null).items()) {
            Item i = row.item();
            csv.append(Csv.row(i.name(), i.kind(), unit(i.baseUnit(), i.customUnitName()), plain(i.quantity()), plain(i.unitCost()),
                    i.value().toPlainString(), plain(i.reorderLevel()), String.valueOf(i.lowStock())));
        }
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public String valuationCsv(TenantContext ctx, LocalDate asOf) {
        StringBuilder csv = new StringBuilder("as_of,item,unit,quantity,value\r\n");
        for (ValuationRow r : valuation(ctx, asOf).items()) {
            csv.append(Csv.row(asOf.toString(), r.name(), unit(r.baseUnit(), r.customUnitName()), plain(r.quantity()), r.value().toPlainString()));
        }
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public String movementsCsv(TenantContext ctx, long itemId) {
        StringBuilder csv = new StringBuilder("date,record,record_type,movement,location,lot,quantity,value,unit_cost,balance_quantity,balance_value,activity,reversal\r\n");
        int page = 0;
        PageResponse<MovementRow> p;
        List<MovementRow> all = new ArrayList<>();
        do {
            p = movements(ctx, itemId, page++, PageResponse.MAX_SIZE);
            all.addAll(p.items());
        } while (page < p.totalPages());
        Collections.reverse(all);
        for (MovementRow m : all) {
            csv.append(Csv.row(m.date().toString(), m.documentNumber(), m.documentType(), m.movementType(), m.locationName(), m.lotNo(), plain(m.quantity()),
                    m.value().toPlainString(), plain(m.unitCost()), plain(m.balanceQuantity()), m.balanceValue().toPlainString(), m.costObjectName(),
                    String.valueOf(m.reversal())));
        }
        return csv.toString();
    }

    private static String unit(String code, String custom) { return custom != null ? custom : code; }

    private static String plain(BigDecimal v) {
        if (v == null) return "";
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0, RoundingMode.UNNECESSARY) : s).toPlainString();
    }

}
