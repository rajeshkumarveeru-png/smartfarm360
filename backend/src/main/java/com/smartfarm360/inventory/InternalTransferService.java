package com.smartfarm360.inventory;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.DocumentService.StockRecord;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.JournalPart;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Internal transfers of own produce between activities (organic resources design, L4, L5, L9, L12; posting rule P17):
 * the quantity is used on the target activity like a stock use, and cost moves only by rule — the source's average cost
 * per unit so far, capped at its costs not yet moved. Never revenue; the farm's total cost is unchanged.
 */
@Service
public class InternalTransferService {
    private static final List<String> EXPENSE_CODES = Arrays.stream(LedgerAccount.values()).filter(a -> a.type() == LedgerAccount.Type.EXPENSE)
            .map(LedgerAccount::code).toList();

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final MasterDataService master;
    private final ItemService items;
    private final StockLedger ledger;
    private final AuditService audit;

    public InternalTransferService(JdbcClient jdbc, DocumentService documents, MasterDataService master, ItemService items, StockLedger ledger,
                                   AuditService audit) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.master = master;
        this.items = items;
        this.ledger = ledger;
        this.audit = audit;
    }

    public record TransferRequest(@NotNull LocalDate date, @NotNull Long itemId, @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal quantity,
                                  @Size(max = 30) String unit, Long locationId, @NotNull Long toCostObjectId, Long fromCostObjectId,
                                  @Pattern(regexp = "NIL|AVERAGE_COST") String costMethod, @Size(max = 500) String notes,
                                  @Size(max = 60) String lotNo) {
        public TransferRequest(LocalDate date, Long itemId, BigDecimal quantity, String unit, Long locationId, Long toCostObjectId, Long fromCostObjectId,
                               String costMethod, String notes) {
            this(date, itemId, quantity, unit, locationId, toCostObjectId, fromCostObjectId, costMethod, notes, null);
        }
    }

    /** What a transfer would move: shown before saving (L5). {@code defaultMethod}: the rule for this source (L9). */
    public record Preview(String defaultMethod, String method, BigDecimal baseQuantity, String baseUnit, BigDecimal sourceCosts, BigDecimal sourceProduced,
                          BigDecimal alreadyMovedQuantity, BigDecimal alreadyMovedCost, BigDecimal unitCost, BigDecimal costMoved) {}

    public record Transfer(long documentId, String number, LocalDate date, long itemId, String itemName, BigDecimal baseQuantity, String baseUnit,
                           Long fromCostObjectId, String fromName, String fromKind, long toCostObjectId, String toName, String toKind, String method,
                           BigDecimal unitCost, BigDecimal costMoved, boolean reversed) {}

    private record CostObjectRow(long id, String kind, String name, Long farmId) {}

    private CostObjectRow costObject(long tenantId, long id) {
        return jdbc.sql("select id, kind, name, farm_id from sf_cost_objects where id = :id and tenant_id = :t").param("id", id).param("t", tenantId)
                .query((rs, i) -> new CostObjectRow(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4))).optional()
                .orElseThrow(() -> ApiException.notFound("COST_OBJECT_NOT_FOUND", "Activity not found."));
    }

    /**
     * Organic batch output moves at average cost by default; byproducts and other produce at nil (L9). Own produce moved
     * into a processing batch carries its average cost too, so the crop's cost follows its harvest (processing design, M3).
     */
    static String defaultMethod(String sourceKind, String targetKind) {
        return "ORGANIC_BATCH".equals(sourceKind) || "PROCESSING_BATCH".equals(targetKind) ? "AVERAGE_COST" : "NIL";
    }

    private record Checked(ItemService.ItemRow item, BigDecimal baseQuantity, CostObjectRow target, CostObjectRow source, Preview preview) {}

    private Checked check(TenantContext ctx, TransferRequest r) {
        if (r.date().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        ItemService.ItemRow item = items.itemRow(ctx.tenantId(), r.itemId(), false);
        if (!item.active()) throw ApiException.badRequest("STOCK_ITEM_INACTIVE", "This stock item is no longer in use.");
        if (!"PRODUCE".equals(item.kind())) throw ApiException.badRequest("NOT_FARM_PRODUCE", "Only the farm's own produce can be moved between activities.");
        BigDecimal base = items.toBase(item, r.quantity(), r.unit() == null || r.unit().isBlank() ? null : r.unit().trim()).baseQuantity();
        CostObjectRow target = costObject(ctx.tenantId(), r.toCostObjectId());
        if ("OVERHEAD".equals(target.kind())) throw ApiException.badRequest("TARGET_REQUIRED", "Choose the activity that uses it.");
        if ("ORGANIC_BATCH".equals(target.kind()) && jdbc.sql("select exists (select 1 from sf_organic_batches where tenant_id = :t and cost_object_id = :co and finished_on is not null)")
                .param("t", ctx.tenantId()).param("co", target.id()).query(Boolean.class).single()) {
            throw ApiException.conflict("BATCH_FINISHED", "This batch is finished. Reopen it to record more.");
        }
        if ("PROCESSING_BATCH".equals(target.kind()) && jdbc.sql("select exists (select 1 from sf_processing_batches where tenant_id = :t and cost_object_id = :co and finished_on is not null)")
                .param("t", ctx.tenantId()).param("co", target.id()).query(Boolean.class).single()) {
            throw ApiException.conflict("BATCH_FINISHED", "This batch is finished. Reopen it to record more.");
        }
        CostObjectRow source = r.fromCostObjectId() == null ? null : costObject(ctx.tenantId(), r.fromCostObjectId());
        if (source != null && source.id() == target.id()) throw ApiException.badRequest("SAME_ACTIVITY", "Choose a different activity to move it to.");
        String def = source == null ? "NIL" : defaultMethod(source.kind(), target.kind());
        String method = r.costMethod() == null ? def : r.costMethod();
        if ("AVERAGE_COST".equals(method) && source == null) throw ApiException.badRequest("SOURCE_REQUIRED", "Choose the activity that produced it to move its cost.");
        if (!method.equals(def) && !ctx.has(Permission.FINANCE_MANAGE)) {
            throw ApiException.forbidden("FINANCE_MANAGE_REQUIRED", "Only an owner or accountant can change how cost moves with this produce.");
        }
        BigDecimal produced = BigDecimal.ZERO, movedQty = BigDecimal.ZERO, movedCost = BigDecimal.ZERO, gross = BigDecimal.ZERO, unitCost = null, cost = BigDecimal.ZERO;
        if (source != null) {
            produced = jdbc.sql("""
                            select coalesce(sum(quantity), 0) from sf_stock_movements
                            where tenant_id = :t and item_id = :i and cost_object_id = :co and movement_type = 'PRODUCE' and movement_date <= :d""")
                    .param("t", ctx.tenantId()).param("i", item.id()).param("co", source.id()).param("d", r.date()).query(BigDecimal.class).single();
            Map<String, Object> moved = jdbc.sql("""
                            select coalesce(sum(x.base_quantity) filter (where x.item_id = :i), 0) as qty, coalesce(sum(x.cost_moved), 0) as cost
                            from sf_internal_transfers x join sf_finance_documents d on d.id = x.document_id and d.tenant_id = x.tenant_id
                            where x.tenant_id = :t and x.source_cost_object_id = :co and d.status = 'POSTED'""")
                    .param("t", ctx.tenantId()).param("i", item.id()).param("co", source.id()).query().singleRow();
            movedQty = (BigDecimal) moved.get("qty");
            movedCost = (BigDecimal) moved.get("cost");
            if (base.compareTo(produced.subtract(movedQty)) > 0) {
                throw ApiException.badRequest("SOURCE_QUANTITY_EXCEEDED", source.name() + " has produced " + produced.subtract(movedQty).stripTrailingZeros().toPlainString()
                        + " " + item.baseUnit() + " of this that has not been moved yet.");
            }
            // The source's costs so far, before any of them were moved out (moves out are added back)
            BigDecimal net = jdbc.sql("""
                            select coalesce(sum(l.debit - l.credit), 0) from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                            where l.tenant_id = :t and l.cost_object_id = :co and e.entry_date <= :d and l.account_code in (:codes)""")
                    .param("t", ctx.tenantId()).param("co", source.id()).param("d", r.date()).param("codes", EXPENSE_CODES).query(BigDecimal.class).single();
            gross = net.add(movedCost);
            if ("AVERAGE_COST".equals(method) && produced.signum() > 0) {
                unitCost = gross.max(BigDecimal.ZERO).divide(produced, 6, RoundingMode.HALF_UP);
                cost = unitCost.multiply(base).min(gross.subtract(movedCost).max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return new Checked(item, base, target, source, new Preview(def, method, base, item.baseUnit(), gross.setScale(2, RoundingMode.HALF_UP), produced,
                movedQty, movedCost, unitCost, cost));
    }

    @Transactional(readOnly = true)
    public Preview preview(TenantContext ctx, TransferRequest r) { return check(ctx, r).preview(); }

    @Transactional
    public Transfer transfer(TenantContext ctx, TransferRequest r, String key) {
        Optional<Document> replay = documents.replayed(ctx, key, DocType.INTERNAL_TRANSFER, r);
        if (replay.isPresent()) return transfer(ctx, replay.get().summary().id());
        Checked c = check(ctx, r);
        long location = items.activeLocation(ctx.tenantId(), r.locationId());
        BigDecimal cost = c.preview().costMoved();
        Long farm = c.target().farmId();
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.INTERNAL_TRANSFER, r.date(), null, farm, null, null, null,
                r.notes() == null || r.notes().isBlank() ? c.item().name() + " to " + c.target().name() : r.notes().trim(), null, List.of()), key, r, (id, lineIds) -> {
            ledger.lockItems(ctx.tenantId(), List.of(c.item().id()));
            StockLedger.Moved m = ledger.issue(ctx.tenantId(), id, null, "ISSUE", c.item().id(), location, ledger.issuingLot(c.item(), r.lotNo() == null || r.lotNo().isBlank() ? null : r.lotNo().trim()), c.baseQuantity(),
                    r.date(), c.target().id());
            List<JournalPart> parts = new ArrayList<>();
            BigDecimal value = m.value().negate();
            if (value.signum() != 0) {
                parts.add(new JournalPart(LedgerAccount.FARM_INPUTS.code(), value, BigDecimal.ZERO, c.item().expenseCategoryId(), null, null, c.target().id(), farm));
                parts.add(new JournalPart(LedgerAccount.STOCK.code(), BigDecimal.ZERO, value, null, null, null, null, farm));
            }
            if (cost.signum() > 0) {
                // P17: a net-zero move of cost from the source activity to the target
                parts.add(new JournalPart(LedgerAccount.OWN_FARM_INPUTS.code(), cost, BigDecimal.ZERO, null, null, null, c.target().id(), farm));
                parts.add(new JournalPart(LedgerAccount.OWN_FARM_INPUTS.code(), BigDecimal.ZERO, cost, null, null, null, c.source().id(), c.source().farmId()));
            }
            jdbc.sql("""
                            insert into sf_internal_transfers (tenant_id, document_id, item_id, base_quantity, source_cost_object_id, target_cost_object_id,
                              cost_method, unit_cost, cost_moved)
                            values (:t, :doc, :i, :q, :src, :tgt, :m, :u, :c)""")
                    .param("t", ctx.tenantId()).param("doc", id).param("i", c.item().id()).param("q", c.baseQuantity())
                    .param("src", c.source() == null ? null : c.source().id()).param("tgt", c.target().id())
                    .param("m", c.preview().method())
                    .param("u", c.preview().unitCost()).param("c", cost).update();
            return parts;
        });
        audit.record(ctx.tenantId(), ctx.userId(), "INTERNAL_TRANSFER_RECORDED", "FINANCE_DOCUMENT", doc.summary().id(),
                Map.of("item", c.item().name(), "to", c.target().name(), "costMoved", cost));
        return transfer(ctx, doc.summary().id());
    }

    private static final String SELECT = """
            select d.id, d.number, d.doc_date, x.item_id, i.name, x.base_quantity, i.base_unit_code, x.source_cost_object_id, s.name, s.kind,
                   x.target_cost_object_id, g.name, g.kind, x.cost_method, x.unit_cost, x.cost_moved, d.status <> 'POSTED'
            from sf_internal_transfers x
            join sf_finance_documents d on d.id = x.document_id and d.tenant_id = x.tenant_id
            join sf_stock_items i on i.id = x.item_id and i.tenant_id = x.tenant_id
            left join sf_cost_objects s on s.id = x.source_cost_object_id and s.tenant_id = x.tenant_id
            join sf_cost_objects g on g.id = x.target_cost_object_id and g.tenant_id = x.tenant_id
            where x.tenant_id = :t""";

    private static Transfer row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Transfer(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getLong(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7),
                (Long) rs.getObject(8), rs.getString(9), rs.getString(10), rs.getLong(11), rs.getString(12), rs.getString(13), rs.getString(14),
                rs.getBigDecimal(15), rs.getBigDecimal(16), rs.getBoolean(17));
    }

    Transfer transfer(TenantContext ctx, long documentId) {
        return jdbc.sql(SELECT + " and x.document_id = :d").param("t", ctx.tenantId()).param("d", documentId).query((rs, i) -> row(rs)).single();
    }

    /** Transfers in a period, optionally only those into or out of one activity. */
    @Transactional(readOnly = true)
    public List<Transfer> transfers(TenantContext ctx, LocalDate from, LocalDate to, Long costObjectId) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (costObjectId != null) master.requireCostObject(ctx.tenantId(), costObjectId);
        return jdbc.sql(SELECT + " and d.doc_date between :from and :to and (cast(:co as bigint) is null or x.source_cost_object_id = :co or x.target_cost_object_id = :co)"
                        + " order by d.doc_date desc, d.id desc")
                .param("t", ctx.tenantId()).param("from", from).param("to", to).param("co", costObjectId).query((rs, i) -> row(rs)).list();
    }
}
