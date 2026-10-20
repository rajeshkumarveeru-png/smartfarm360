package com.smartfarm360.inventory;

import com.smartfarm360.finance.FinanceDtos.Category;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.inventory.StockDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Stock items with their units, locations and lots (stock movement design, sections 3–4). */
@Service
public class ItemService {
    private final JdbcClient jdbc;
    private final MasterDataService master;
    private final AuditService audit;

    public ItemService(JdbcClient jdbc, MasterDataService master, AuditService audit) {
        this.jdbc = jdbc;
        this.master = master;
        this.audit = audit;
    }

    // ---- Units ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Unit> units() {
        return jdbc.sql("select code, dimension, factor from sf_units order by sort_order")
                .query((rs, i) -> new Unit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).stripTrailingZeros())).list();
    }

    private Unit unit(String code) {
        return jdbc.sql("select code, dimension, factor from sf_units where code = :c").param("c", code)
                .query((rs, i) -> new Unit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3))).optional()
                .orElseThrow(() -> ApiException.badRequest("UNIT_NOT_FOUND", "Unknown unit."));
    }

    /** A quantity entered in {@code unit}, converted to the item's base unit (4 decimals). */
    record Converted(BigDecimal baseQuantity, String unitLabel) {}

    Converted toBase(ItemRow item, BigDecimal quantity, String unit) {
        BigDecimal factor;
        String label;
        if (unit == null || unit.isBlank() || unit.equals(item.baseUnit()) || unit.equalsIgnoreCase(item.customUnitName())) {
            factor = BigDecimal.ONE;
            label = item.customUnitName() != null ? item.customUnitName() : item.baseUnit();
        } else {
            Optional<BigDecimal> pack = jdbc.sql("select factor from sf_item_units where item_id = :i and tenant_id = :t and lower(name) = lower(:n)")
                    .param("i", item.id()).param("t", item.tenantId()).param("n", unit.trim()).query(BigDecimal.class).optional();
            if (pack.isPresent()) {
                factor = pack.get();
                label = unit.trim();
            } else {
                Unit entered = jdbc.sql("select code, dimension, factor from sf_units where code = :c").param("c", unit)
                        .query((rs, i) -> new Unit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3))).optional()
                        .orElseThrow(() -> ApiException.badRequest("UNIT_NOT_ALLOWED", "This unit cannot be used for this item."));
                Unit base = unit(item.baseUnit());
                if (item.customUnitName() != null || !entered.dimension().equals(base.dimension())) {
                    throw ApiException.badRequest("UNIT_NOT_ALLOWED", "This unit cannot be used for this item.");
                }
                factor = entered.factor().divide(base.factor(), 10, RoundingMode.HALF_UP);
                label = entered.code();
            }
        }
        BigDecimal base = quantity.multiply(factor).setScale(4, RoundingMode.HALF_UP);
        if (base.signum() <= 0) throw ApiException.badRequest("QUANTITY_TOO_SMALL", "The quantity is too small.");
        return new Converted(base, label);
    }

    // ---- Items ---------------------------------------------------------------------------------------------------

    record ItemRow(long id, long tenantId, String name, String kind, String baseUnit, String customUnitName, Long expenseCategoryId,
                   Long saleCategoryId, boolean trackLots, BigDecimal quantity, BigDecimal value, boolean active) {}

    ItemRow itemRow(long tenantId, long id, boolean lock) {
        return jdbc.sql("""
                        select id, tenant_id, name, kind, base_unit_code, custom_unit_name, expense_category_id, sale_category_id, track_lots,
                               on_hand_quantity, on_hand_value, active from sf_stock_items where id = :id and tenant_id = :t""" + (lock ? " for update" : ""))
                .param("id", id).param("t", tenantId)
                .query((rs, i) -> new ItemRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        (Long) rs.getObject(7), (Long) rs.getObject(8), rs.getBoolean(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBoolean(12)))
                .optional().orElseThrow(() -> ApiException.notFound("STOCK_ITEM_NOT_FOUND", "Stock item not found."));
    }

    private static final String ITEM_SELECT = """
            select i.id, i.name, i.kind, i.base_unit_code, i.custom_unit_name, u.dimension, i.expense_category_id, i.sale_category_id,
                   i.reorder_level, i.track_lots, i.on_hand_quantity, i.on_hand_value, i.active, i.needs_review, i.notes
            from sf_stock_items i join sf_units u on u.code = i.base_unit_code""";

    @Transactional(readOnly = true)
    public List<Item> items(TenantContext ctx, boolean includeInactive) {
        List<Item> items = jdbc.sql(ITEM_SELECT + " where i.tenant_id = :t and (i.active or :all) order by lower(i.name)")
                .param("t", ctx.tenantId()).param("all", includeInactive).query((rs, i) -> item(rs, List.of())).list();
        Map<Long, List<PackUnit>> packs = new HashMap<>();
        jdbc.sql("select item_id, id, name, factor from sf_item_units where tenant_id = :t order by id").param("t", ctx.tenantId())
                .query((rs, i) -> Map.entry(rs.getLong(1), new PackUnit(rs.getLong(2), rs.getString(3), rs.getBigDecimal(4).stripTrailingZeros())))
                .list().forEach(e -> packs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return items.stream().map(i -> withPacks(i, packs.getOrDefault(i.id(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public Item item(TenantContext ctx, long id) {
        Item item = jdbc.sql(ITEM_SELECT + " where i.id = :id and i.tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> item(rs, List.of())).optional()
                .orElseThrow(() -> ApiException.notFound("STOCK_ITEM_NOT_FOUND", "Stock item not found."));
        return withPacks(item, packUnits(ctx.tenantId(), id));
    }

    @Transactional
    public Item createItem(TenantContext ctx, ItemRequest r) {
        Unit base = unit(r.baseUnit());
        if ("AREA".equals(base.dimension())) throw ApiException.badRequest("UNIT_NOT_ALLOWED", "Area units are for land, not stock.");
        validateCategories(ctx, r.expenseCategoryId(), r.saleCategoryId());
        long id;
        try {
            id = jdbc.sql("""
                            insert into sf_stock_items (tenant_id, name, kind, base_unit_code, expense_category_id, sale_category_id, reorder_level,
                              track_lots, notes)
                            values (:t, :name, :kind, :unit, :exp, :sale, :reorder, :lots, :notes) returning id""")
                    .param("t", ctx.tenantId()).param("name", r.name().trim()).param("kind", r.kind()).param("unit", base.code())
                    .param("exp", r.expenseCategoryId()).param("sale", r.saleCategoryId())
                    .param("reorder", r.reorderLevel() == null ? BigDecimal.ZERO : r.reorderLevel())
                    .param("lots", Boolean.TRUE.equals(r.trackLots())).param("notes", trim(r.notes()))
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("STOCK_ITEM_EXISTS", "A stock item with this name already exists.");
        }
        if (r.packUnits() != null) for (PackUnitRequest p : r.packUnits()) insertPackUnit(ctx, id, p);
        audit.record(ctx.tenantId(), ctx.userId(), "STOCK_ITEM_CREATED", "STOCK_ITEM", id, Map.of("name", r.name().trim(), "baseUnit", base.code()));
        return item(ctx, id);
    }

    @Transactional
    public Item updateItem(TenantContext ctx, long id, ItemPatch p) {
        itemRow(ctx.tenantId(), id, true);
        validateCategories(ctx, p.expenseCategoryId(), p.saleCategoryId());
        try {
            jdbc.sql("""
                            update sf_stock_items set name = coalesce(:name, name), kind = coalesce(:kind, kind),
                              expense_category_id = coalesce(:exp, expense_category_id), sale_category_id = coalesce(:sale, sale_category_id),
                              reorder_level = coalesce(:reorder, reorder_level), track_lots = coalesce(:lots, track_lots),
                              notes = coalesce(:notes, notes), active = coalesce(:active, active),
                              needs_review = case when :reviewed then false else needs_review end, updated_at = now()
                            where id = :id and tenant_id = :t""")
                    .param("name", p.name() == null ? null : p.name().trim()).param("kind", p.kind()).param("exp", p.expenseCategoryId())
                    .param("sale", p.saleCategoryId()).param("reorder", p.reorderLevel()).param("lots", p.trackLots())
                    .param("notes", trim(p.notes())).param("active", p.active()).param("reviewed", Boolean.TRUE.equals(p.reviewed()))
                    .param("id", id).param("t", ctx.tenantId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("STOCK_ITEM_EXISTS", "A stock item with this name already exists.");
        }
        if (Boolean.TRUE.equals(p.trackLots()) && hasUnlottedStock(ctx.tenantId(), id)) {
            throw ApiException.conflict("LOTS_NEED_EMPTY_STOCK", "Lots can be switched on only when the item has no stock outside lots.");
        }
        if (Boolean.FALSE.equals(p.trackLots()) && hasLottedStock(ctx.tenantId(), id)) {
            throw ApiException.conflict("LOTS_IN_USE", "Use up or move the stock in lots before switching lots off.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "STOCK_ITEM_UPDATED", "STOCK_ITEM", id, Map.of("fields", changedFields(p)));
        return item(ctx, id);
    }

    @Transactional
    public Item addPackUnit(TenantContext ctx, long itemId, PackUnitRequest r) {
        itemRow(ctx.tenantId(), itemId, true);
        insertPackUnit(ctx, itemId, r);
        audit.record(ctx.tenantId(), ctx.userId(), "STOCK_ITEM_UPDATED", "STOCK_ITEM", itemId, Map.of("packUnit", r.name().trim()));
        return item(ctx, itemId);
    }

    /** Removing a pack unit does not change history: recorded lines keep their quantities and unit names. */
    @Transactional
    public Item removePackUnit(TenantContext ctx, long itemId, long packUnitId) {
        itemRow(ctx.tenantId(), itemId, true);
        int removed = jdbc.sql("delete from sf_item_units where id = :id and item_id = :i and tenant_id = :t")
                .param("id", packUnitId).param("i", itemId).param("t", ctx.tenantId()).update();
        if (removed == 0) throw ApiException.notFound("PACK_UNIT_NOT_FOUND", "Pack unit not found.");
        audit.record(ctx.tenantId(), ctx.userId(), "STOCK_ITEM_UPDATED", "STOCK_ITEM", itemId, Map.of("packUnitRemoved", packUnitId));
        return item(ctx, itemId);
    }

    private void insertPackUnit(TenantContext ctx, long itemId, PackUnitRequest r) {
        String name = r.name().trim();
        boolean clashes = jdbc.sql("select exists (select 1 from sf_units where lower(code) = lower(:n))").param("n", name).query(Boolean.class).single();
        if (clashes) throw ApiException.badRequest("PACK_UNIT_NAME_RESERVED", "This name is a standard unit; choose another name.");
        try {
            jdbc.sql("insert into sf_item_units (tenant_id, item_id, name, factor) values (:t, :i, :n, :f)")
                    .param("t", ctx.tenantId()).param("i", itemId).param("n", name).param("f", r.factor()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PACK_UNIT_EXISTS", "This item already has a pack unit with this name.");
        }
    }

    private List<PackUnit> packUnits(long tenantId, long itemId) {
        return jdbc.sql("select id, name, factor from sf_item_units where item_id = :i and tenant_id = :t order by id")
                .param("i", itemId).param("t", tenantId)
                .query((rs, i) -> new PackUnit(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3).stripTrailingZeros())).list();
    }

    /** Stock used on activities is a farm input (5000); sales of the item go to an income category. */
    private void validateCategories(TenantContext ctx, Long expenseCategoryId, Long saleCategoryId) {
        if (expenseCategoryId != null) {
            Category c = master.category(ctx, expenseCategoryId);
            if (!LedgerAccount.FARM_INPUTS.code().equals(c.accountCode())) {
                throw ApiException.badRequest("CATEGORY_MISMATCH", "Choose a farm-input category for using this item.");
            }
        }
        if (saleCategoryId != null && !"INCOME".equals(master.category(ctx, saleCategoryId).kind())) {
            throw ApiException.badRequest("CATEGORY_MISMATCH", "Choose an income category for selling this item.");
        }
    }

    private boolean hasUnlottedStock(long tenantId, long itemId) {
        return jdbc.sql("select exists (select 1 from sf_stock_balances where tenant_id = :t and item_id = :i and lot_id is null and quantity > 0)")
                .param("t", tenantId).param("i", itemId).query(Boolean.class).single();
    }

    private boolean hasLottedStock(long tenantId, long itemId) {
        return jdbc.sql("select exists (select 1 from sf_stock_balances where tenant_id = :t and item_id = :i and lot_id is not null and quantity > 0)")
                .param("t", tenantId).param("i", itemId).query(Boolean.class).single();
    }

    private static List<String> changedFields(ItemPatch p) {
        List<String> f = new ArrayList<>();
        if (p.name() != null) f.add("name");
        if (p.kind() != null) f.add("kind");
        if (p.expenseCategoryId() != null) f.add("expenseCategory");
        if (p.saleCategoryId() != null) f.add("saleCategory");
        if (p.reorderLevel() != null) f.add("reorderLevel");
        if (p.trackLots() != null) f.add("trackLots");
        if (p.notes() != null) f.add("notes");
        if (p.active() != null) f.add("active");
        if (Boolean.TRUE.equals(p.reviewed())) f.add("reviewed");
        return f;
    }

    static Item item(ResultSet rs, List<PackUnit> packs) throws SQLException {
        BigDecimal qty = rs.getBigDecimal("on_hand_quantity");
        BigDecimal value = rs.getBigDecimal("on_hand_value");
        BigDecimal reorder = rs.getBigDecimal("reorder_level");
        return new Item(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("base_unit_code"), rs.getString("custom_unit_name"),
                rs.getString("dimension"), (Long) rs.getObject("expense_category_id"), (Long) rs.getObject("sale_category_id"), reorder,
                rs.getBoolean("track_lots"), qty, value, unitCost(value, qty), reorder.signum() > 0 && qty.compareTo(reorder) <= 0,
                rs.getBoolean("active"), rs.getBoolean("needs_review"), rs.getString("notes"), packs);
    }

    private static Item withPacks(Item i, List<PackUnit> packs) {
        return new Item(i.id(), i.name(), i.kind(), i.baseUnit(), i.customUnitName(), i.dimension(), i.expenseCategoryId(), i.saleCategoryId(),
                i.reorderLevel(), i.trackLots(), i.quantity(), i.value(), i.unitCost(), i.lowStock(), i.active(), i.needsReview(), i.notes(), packs);
    }

    static BigDecimal unitCost(BigDecimal value, BigDecimal quantity) {
        return quantity.signum() == 0 ? BigDecimal.ZERO.setScale(4) : value.divide(quantity, 4, RoundingMode.HALF_UP);
    }

    // ---- Locations -----------------------------------------------------------------------------------------------

    /** Every tenant has a default location; created on first use for tenants made after the Phase 4 migration. */
    @Transactional
    public long defaultLocation(long tenantId) {
        Optional<Long> existing = jdbc.sql("select id from sf_stock_locations where tenant_id = :t and is_default").param("t", tenantId)
                .query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        jdbc.sql("""
                        insert into sf_stock_locations (tenant_id, name, is_default) values (:t, 'Main store', true)
                        on conflict do nothing""").param("t", tenantId).update();
        return jdbc.sql("select id from sf_stock_locations where tenant_id = :t and is_default").param("t", tenantId).query(Long.class).single();
    }

    @Transactional
    public List<Location> locations(TenantContext ctx) {
        defaultLocation(ctx.tenantId());
        return jdbc.sql("select id, name, farm_id, is_default, active from sf_stock_locations where tenant_id = :t order by is_default desc, lower(name)")
                .param("t", ctx.tenantId()).query((rs, i) -> location(rs)).list();
    }

    @Transactional
    public Location createLocation(TenantContext ctx, LocationRequest r) {
        defaultLocation(ctx.tenantId());
        if (r.farmId() != null) master.requireFarm(ctx.tenantId(), r.farmId());
        long id;
        try {
            id = jdbc.sql("insert into sf_stock_locations (tenant_id, name, farm_id) values (:t, :n, :f) returning id")
                    .param("t", ctx.tenantId()).param("n", r.name().trim()).param("f", r.farmId()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LOCATION_EXISTS", "A location with this name already exists.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "STOCK_LOCATION_CREATED", "STOCK_LOCATION", id, Map.of("name", r.name().trim()));
        return location(ctx.tenantId(), id);
    }

    @Transactional
    public Location updateLocation(TenantContext ctx, long id, LocationPatch p) {
        Location current = location(ctx.tenantId(), id);
        if (p.farmId() != null) master.requireFarm(ctx.tenantId(), p.farmId());
        if (Boolean.FALSE.equals(p.active())) {
            if (current.isDefault()) throw ApiException.conflict("DEFAULT_LOCATION", "Make another location the default first.");
            boolean hasStock = jdbc.sql("select exists (select 1 from sf_stock_balances where tenant_id = :t and location_id = :l and quantity > 0)")
                    .param("t", ctx.tenantId()).param("l", id).query(Boolean.class).single();
            if (hasStock) throw ApiException.conflict("LOCATION_HAS_STOCK", "Move the stock out of this location first.");
        }
        if (Boolean.TRUE.equals(p.makeDefault())) {
            if (!current.active() && !Boolean.TRUE.equals(p.active())) throw ApiException.conflict("LOCATION_INACTIVE", "This location is not in use.");
            jdbc.sql("update sf_stock_locations set is_default = false where tenant_id = :t and is_default").param("t", ctx.tenantId()).update();
        }
        try {
            jdbc.sql("""
                            update sf_stock_locations set name = coalesce(:n, name), farm_id = coalesce(:f, farm_id), active = coalesce(:a, active),
                              is_default = is_default or :d where id = :id and tenant_id = :t""")
                    .param("n", p.name() == null ? null : p.name().trim()).param("f", p.farmId()).param("a", p.active())
                    .param("d", Boolean.TRUE.equals(p.makeDefault())).param("id", id).param("t", ctx.tenantId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LOCATION_EXISTS", "A location with this name already exists.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "STOCK_LOCATION_UPDATED", "STOCK_LOCATION", id,
                Map.of("name", p.name() == null ? current.name() : p.name().trim()));
        return location(ctx.tenantId(), id);
    }

    Location location(long tenantId, long id) {
        return jdbc.sql("select id, name, farm_id, is_default, active from sf_stock_locations where id = :id and tenant_id = :t")
                .param("id", id).param("t", tenantId).query((rs, i) -> location(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));
    }

    /** The given active location, or the default one. */
    long activeLocation(long tenantId, Long locationId) {
        if (locationId == null) return defaultLocation(tenantId);
        Location l = location(tenantId, locationId);
        if (!l.active()) throw ApiException.badRequest("LOCATION_INACTIVE", "This location is not in use.");
        return l.id();
    }

    private static Location location(ResultSet rs) throws SQLException {
        return new Location(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getBoolean(4), rs.getBoolean(5));
    }

    // ---- Lots ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Lot> lots(TenantContext ctx, long itemId) {
        itemRow(ctx.tenantId(), itemId, false);
        LocalDate today = LocalDate.now();
        return jdbc.sql("""
                        select l.id, l.item_id, l.lot_no, l.expiry_date, l.received_on,
                               coalesce((select sum(b.quantity) from sf_stock_balances b where b.lot_id = l.id and b.tenant_id = l.tenant_id), 0)
                        from sf_stock_lots l where l.item_id = :i and l.tenant_id = :t
                        order by l.expiry_date nulls last, l.received_on, l.id""")
                .param("i", itemId).param("t", ctx.tenantId())
                .query((rs, i) -> {
                    LocalDate expiry = rs.getObject(4, LocalDate.class);
                    return new Lot(rs.getLong(1), rs.getLong(2), rs.getString(3), expiry, rs.getObject(5, LocalDate.class), rs.getBigDecimal(6),
                            expiry != null && expiry.isBefore(today));
                }).list();
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
