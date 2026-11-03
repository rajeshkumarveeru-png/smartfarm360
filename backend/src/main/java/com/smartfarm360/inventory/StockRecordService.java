package com.smartfarm360.inventory;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.DocumentService.StockRecord;
import com.smartfarm360.finance.DocumentService.StockRecordLine;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.JournalPart;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.inventory.StockDtos.*;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Stock records other than bills and sales (stock movement design, section 6): use on an activity (S3), transfer
 * (S4), wastage (S5), stock count (S6), farm produce (S7) and returns both ways (S8, S9). Each is a finance record:
 * numbered, dated, lock-date checked, idempotent, audited and reversible.
 */
@Service
public class StockRecordService {
    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final MasterDataService master;
    private final ItemService items;
    private final StockLedger ledger;

    public StockRecordService(JdbcClient jdbc, DocumentService documents, MasterDataService master, ItemService items, StockLedger ledger) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.master = master;
        this.items = items;
        this.ledger = ledger;
    }

    /** A request line checked and converted to the item's base unit. */
    private record Resolved(ItemService.ItemRow item, BigDecimal quantity, String unit, BigDecimal baseQuantity, String lotNo, LocalDate lotExpiry) {}

    private List<Resolved> resolve(TenantContext ctx, List<ItemQuantity> lines) {
        List<Resolved> out = new ArrayList<>();
        for (ItemQuantity l : lines) out.add(resolve(ctx, l.itemId(), l.quantity(), l.unit(), l.lotNo(), l.lotExpiry()));
        return out;
    }

    private Resolved resolve(TenantContext ctx, long itemId, BigDecimal quantity, String unit, String lotNo, LocalDate lotExpiry) {
        ItemService.ItemRow item = items.itemRow(ctx.tenantId(), itemId, false);
        if (!item.active()) throw ApiException.badRequest("STOCK_ITEM_INACTIVE", "This stock item is no longer in use.");
        ItemService.Converted c = items.toBase(item, quantity, trim(unit));
        return new Resolved(item, quantity, c.unitLabel(), c.baseQuantity(), trim(lotNo), lotExpiry);
    }

    private void lock(TenantContext ctx, List<Resolved> lines) {
        ledger.lockItems(ctx.tenantId(), lines.stream().map(r -> r.item().id()).toList());
    }

    // ---- S3 use on an activity -------------------------------------------------------------------------------------

    @Transactional
    public StockRecordResult issue(TenantContext ctx, IssueRequest r, String key) {
        long location = items.activeLocation(ctx.tenantId(), r.locationId());
        if (r.costObjectId() != null) master.requireCostObject(ctx.tenantId(), r.costObjectId());
        List<Resolved> lines = resolve(ctx, r.lines());
        Long defaultCategory = systemCategory("OTHER_INPUTS");
        List<String> warnings = new ArrayList<>();
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.STOCK_ISSUE, r.docDate(), null, r.farmId(), null, null, null, r.notes(),
                null, List.of(), r.assetId()), key, r, (id, lineIds) -> {
            lock(ctx, lines);
            Map<Long, BigDecimal> costByCategory = new LinkedHashMap<>();
            for (Resolved l : lines) {
                StockLedger.Moved m = ledger.issue(ctx.tenantId(), id, null, "ISSUE", l.item().id(), location, ledger.issuingLot(l.item(), l.lotNo()),
                        l.baseQuantity(), r.docDate(), r.costObjectId());
                warn(warnings, m, l);
                Long category = l.item().expenseCategoryId() != null ? l.item().expenseCategoryId() : defaultCategory;
                costByCategory.merge(category, m.value().negate(), BigDecimal::add);
            }
            List<JournalPart> parts = new ArrayList<>();
            costByCategory.forEach((category, cost) -> {
                parts.add(new JournalPart(LedgerAccount.FARM_INPUTS.code(), cost, BigDecimal.ZERO, category, null, null, r.costObjectId(), r.farmId()));
                parts.add(new JournalPart(LedgerAccount.STOCK.code(), BigDecimal.ZERO, cost, null, null, null, null, r.farmId()));
            });
            return parts;
        });
        return new StockRecordResult(doc, warnings);
    }

    // ---- S4 transfer -------------------------------------------------------------------------------------------------

    @Transactional
    public StockRecordResult transfer(TenantContext ctx, TransferRequest r, String key) {
        if (r.fromLocationId().equals(r.toLocationId())) throw ApiException.badRequest("SAME_LOCATION", "Choose two different locations.");
        long from = items.activeLocation(ctx.tenantId(), r.fromLocationId());
        long to = items.activeLocation(ctx.tenantId(), r.toLocationId());
        List<Resolved> lines = resolve(ctx, r.lines());
        List<String> warnings = new ArrayList<>();
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.STOCK_TRANSFER, r.docDate(), null, null, null, null, null, r.notes(),
                null, List.of()), key, r, (id, lineIds) -> {
            lock(ctx, lines);
            for (Resolved l : lines) {
                Long lot = ledger.issuingLot(l.item(), l.lotNo());
                StockLedger.Moved out = ledger.issue(ctx.tenantId(), id, null, "TRANSFER_OUT", l.item().id(), from, lot, l.baseQuantity(), r.docDate(), null);
                ledger.receive(ctx.tenantId(), id, null, "TRANSFER_IN", l.item().id(), to, lot, l.baseQuantity(), out.value().negate(), r.docDate(), null, null);
                warn(warnings, out, l);
            }
            return List.of();
        });
        return new StockRecordResult(doc, warnings);
    }

    // ---- S5 wastage ----------------------------------------------------------------------------------------------------

    @Transactional
    public StockRecordResult wastage(TenantContext ctx, WastageRequest r, String key) {
        long location = items.activeLocation(ctx.tenantId(), r.locationId());
        List<Resolved> lines = resolve(ctx, r.lines());
        List<String> warnings = new ArrayList<>();
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.WASTAGE, r.docDate(), null, null, null, null, null, r.reason(),
                null, List.of()), key, r, (id, lineIds) -> {
            lock(ctx, lines);
            BigDecimal loss = BigDecimal.ZERO;
            for (Resolved l : lines) {
                StockLedger.Moved m = ledger.issue(ctx.tenantId(), id, null, "WASTAGE", l.item().id(), location, ledger.issuingLot(l.item(), l.lotNo()),
                        l.baseQuantity(), r.docDate(), null);
                warn(warnings, m, l);
                loss = loss.add(m.value().negate());
            }
            return List.of(JournalPart.debit(LedgerAccount.LOSSES.code(), loss, null, null, null),
                    JournalPart.credit(LedgerAccount.STOCK.code(), loss, null, null, null));
        });
        return new StockRecordResult(doc, warnings);
    }

    // ---- S6 stock count ------------------------------------------------------------------------------------------------

    @Transactional
    public StockRecordResult count(TenantContext ctx, CountRequest r, String key) {
        long location = items.activeLocation(ctx.tenantId(), r.locationId());
        List<Resolved> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CountLine l : r.lines()) {
            if (!seen.add(l.itemId() + "|" + Objects.toString(trim(l.lotNo()), ""))) {
                throw ApiException.badRequest("DUPLICATE_LINE", "Each item (and lot) can be counted only once per record.");
            }
            ItemService.ItemRow item = items.itemRow(ctx.tenantId(), l.itemId(), false);
            BigDecimal counted = l.countedQuantity().signum() == 0 ? BigDecimal.ZERO.setScale(4)
                    : items.toBase(item, l.countedQuantity(), trim(l.unit())).baseQuantity();
            lines.add(new Resolved(item, l.countedQuantity(), trim(l.unit()), counted, trim(l.lotNo()), null));
        }
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.STOCK_COUNT, r.docDate(), null, null, null, null, null, r.notes(),
                null, List.of()), key, r, (id, lineIds) -> {
            lock(ctx, lines);
            BigDecimal loss = BigDecimal.ZERO, gain = BigDecimal.ZERO;
            for (Resolved l : lines) {
                Long lot = l.item().trackLots() ? (l.lotNo() == null ? null : findLot(l.item(), l.lotNo())) : null;
                if (l.item().trackLots() && l.lotNo() == null) throw ApiException.badRequest("LOT_REQUIRED", "Enter the lot or batch number.");
                if (!l.item().trackLots() && l.lotNo() != null) throw ApiException.badRequest("LOT_NOT_TRACKED", "This item does not track lots.");
                BigDecimal recorded = ledger.balance(ctx.tenantId(), l.item().id(), location, lot);
                BigDecimal difference = l.baseQuantity().subtract(recorded);
                if (difference.signum() < 0) {
                    StockLedger.Moved m = ledger.issue(ctx.tenantId(), id, null, "COUNT_LOSS", l.item().id(), location, lot, difference.negate(),
                            r.docDate(), null);
                    loss = loss.add(m.value().negate());
                } else if (difference.signum() > 0) {
                    if (lot == null && l.item().trackLots()) lot = ledger.receivingLot(l.item(), l.lotNo(), null, r.docDate(), null);
                    BigDecimal value = ledger.gainValue(ctx.tenantId(), l.item().id(), difference);
                    ledger.receive(ctx.tenantId(), id, null, "COUNT_GAIN", l.item().id(), location, lot, difference, value, r.docDate(), null, null);
                    gain = gain.add(value);
                }
            }
            List<JournalPart> parts = new ArrayList<>();
            parts.add(JournalPart.debit(LedgerAccount.LOSSES.code(), loss, null, null, null));
            parts.add(JournalPart.credit(LedgerAccount.STOCK.code(), loss, null, null, null));
            parts.add(JournalPart.debit(LedgerAccount.STOCK.code(), gain, null, null, null));
            parts.add(JournalPart.credit(LedgerAccount.LOSSES.code(), gain, null, null, null));
            return parts;
        });
        return new StockRecordResult(doc, List.of());
    }

    private Long findLot(ItemService.ItemRow item, String lotNo) {
        return jdbc.sql("select id from sf_stock_lots where tenant_id = :t and item_id = :i and lot_no = :n")
                .param("t", item.tenantId()).param("i", item.id()).param("n", lotNo).query(Long.class).optional().orElse(null);
    }

    // ---- S7 farm produce -----------------------------------------------------------------------------------------------

    @Transactional
    public StockRecordResult produce(TenantContext ctx, ProduceRequest r, String key) {
        long location = items.activeLocation(ctx.tenantId(), r.locationId());
        if (r.costObjectId() != null) master.requireCostObject(ctx.tenantId(), r.costObjectId());
        List<Resolved> lines = resolve(ctx, r.lines());
        for (Resolved l : lines) {
            if (!"PRODUCE".equals(l.item().kind())) {
                throw ApiException.badRequest("NOT_FARM_PRODUCE", "Only farm-produce items can be put into stock this way; record a bill for bought stock.");
            }
        }
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.PRODUCE_RECEIPT, r.docDate(), null, r.farmId(), null, null, null,
                r.notes(), null, List.of()), key, r, (id, lineIds) -> {
            lock(ctx, lines);
            for (Resolved l : lines) {
                ledger.receive(ctx.tenantId(), id, null, "PRODUCE", l.item().id(), location, ledger.receivingLot(l.item(), l.lotNo(), l.lotExpiry(), r.docDate(), r.costObjectId()),
                        l.baseQuantity(), BigDecimal.ZERO, r.docDate(), r.costObjectId(), null);
            }
            return List.of();
        });
        return new StockRecordResult(doc, List.of());
    }

    // ---- S8 return to supplier -----------------------------------------------------------------------------------------

    @Transactional
    public StockRecordResult purchaseReturn(TenantContext ctx, PurchaseReturnRequest r, String key) {
        long location = items.activeLocation(ctx.tenantId(), r.locationId());
        if (r.partyId() == null && r.financialAccountId() == null) {
            throw ApiException.badRequest("PARTY_OR_ACCOUNT_REQUIRED", "Choose the supplier, or the cash/bank account the refund came into.");
        }
        if (r.originalDocumentId() != null) requireOriginal(ctx, r.originalDocumentId(), "BILL", r.partyId());
        List<Resolved> lines = new ArrayList<>();
        List<StockRecordLine> recordLines = new ArrayList<>();
        BigDecimal credit = BigDecimal.ZERO;
        for (ReturnLine l : r.lines()) {
            Resolved x = resolve(ctx, l.itemId(), l.quantity(), l.unit(), l.lotNo(), null);
            lines.add(x);
            BigDecimal amount = l.amount().setScale(2, RoundingMode.HALF_UP);
            credit = credit.add(amount);
            recordLines.add(new StockRecordLine(null, LedgerAccount.STOCK.code(), x.item().name(), x.quantity(), x.unit(),
                    amount.divide(x.quantity(), 4, RoundingMode.HALF_UP), amount, null, x.item().id(), location, x.lotNo(), null, x.baseQuantity()));
        }
        Long defaultCategory = systemCategory("OTHER_INPUTS");
        BigDecimal total = credit;
        List<String> warnings = new ArrayList<>();
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.PURCHASE_RETURN, r.docDate(), r.partyId(), null,
                r.partyId() == null ? r.financialAccountId() : null, r.originalDocumentId(), total, r.notes(), r.referenceNo(), recordLines),
                key, r, (id, lineIds) -> {
                    lock(ctx, lines);
                    List<JournalPart> parts = new ArrayList<>();
                    if (r.partyId() != null) parts.add(JournalPart.debit(LedgerAccount.PAYABLES.code(), total, null, r.partyId(), null));
                    else parts.add(JournalPart.cash(r.financialAccountId(), total, BigDecimal.ZERO));
                    for (int i = 0; i < lines.size(); i++) {
                        Resolved l = lines.get(i);
                        StockLedger.Moved m = ledger.issue(ctx.tenantId(), id, lineIds.get(i), "SUPPLIER_RETURN", l.item().id(), location,
                                ledger.issuingLot(l.item(), l.lotNo()), l.baseQuantity(), r.docDate(), null);
                        warn(warnings, m, l);
                        BigDecimal value = m.value().negate();
                        parts.add(JournalPart.credit(LedgerAccount.STOCK.code(), value, null, null, null));
                        // E10: the credit agreed differs from what the stock was carried at; the difference adjusts farm inputs
                        BigDecimal difference = recordLines.get(i).amount().subtract(value);
                        Long category = l.item().expenseCategoryId() != null ? l.item().expenseCategoryId() : defaultCategory;
                        if (difference.signum() > 0) parts.add(JournalPart.credit(LedgerAccount.FARM_INPUTS.code(), difference, category, null, null));
                        if (difference.signum() < 0) parts.add(JournalPart.debit(LedgerAccount.FARM_INPUTS.code(), difference.negate(), category, null, null));
                    }
                    return parts;
                });
        return new StockRecordResult(doc, warnings);
    }

    // ---- S9 return from a customer -------------------------------------------------------------------------------------

    private record SoldLine(long itemId, Long categoryId, String accountCode, Long costObjectId, Long farmId, long locationId, Long lotId) {}

    @Transactional
    public StockRecordResult saleReturn(TenantContext ctx, SaleReturnRequest r, String key) {
        Map<String, Object> sale = requireOriginal(ctx, r.originalDocumentId(), "SALE", null);
        Long partyId = (Long) sale.get("party_id");
        if (partyId == null && r.financialAccountId() == null) {
            throw ApiException.badRequest("FINANCIAL_ACCOUNT_REQUIRED", "Choose the cash or bank account the refund was paid from.");
        }
        List<Resolved> lines = new ArrayList<>();
        List<SoldLine> sold = new ArrayList<>();
        List<StockRecordLine> recordLines = new ArrayList<>();
        BigDecimal refund = BigDecimal.ZERO;
        for (ReturnLine l : r.lines()) {
            Resolved x = resolve(ctx, l.itemId(), l.quantity(), l.unit(), l.lotNo(), null);
            SoldLine s = soldLine(ctx, r.originalDocumentId(), x.item().id());
            long location = r.locationId() != null ? items.activeLocation(ctx.tenantId(), r.locationId()) : s.locationId();
            String lotNo = x.lotNo() != null ? x.lotNo() : s.lotId() == null ? null
                    : jdbc.sql("select lot_no from sf_stock_lots where id = :id").param("id", s.lotId()).query(String.class).single();
            Resolved withLot = new Resolved(x.item(), x.quantity(), x.unit(), x.baseQuantity(), lotNo, null);
            lines.add(withLot);
            sold.add(new SoldLine(s.itemId(), s.categoryId(), s.accountCode(), s.costObjectId(), s.farmId(), location, s.lotId()));
            BigDecimal amount = l.amount().setScale(2, RoundingMode.HALF_UP);
            refund = refund.add(amount);
            recordLines.add(new StockRecordLine(s.categoryId(), s.accountCode(), x.item().name(), x.quantity(), x.unit(),
                    amount.divide(x.quantity(), 4, RoundingMode.HALF_UP), amount, s.costObjectId(), x.item().id(), location, lotNo, null, x.baseQuantity()));
        }
        BigDecimal total = refund;
        Document doc = documents.recordStock(ctx, new StockRecord(DocType.SALE_RETURN, r.docDate(), partyId, (Long) sale.get("farm_id"),
                partyId == null ? r.financialAccountId() : null, r.originalDocumentId(), total, r.notes(), r.referenceNo(), recordLines),
                key, r, (id, lineIds) -> {
                    lock(ctx, lines);
                    List<JournalPart> parts = new ArrayList<>();
                    if (partyId != null) parts.add(JournalPart.credit(LedgerAccount.RECEIVABLES.code(), total, null, partyId, null));
                    else parts.add(JournalPart.cash(r.financialAccountId(), BigDecimal.ZERO, total));
                    for (int i = 0; i < lines.size(); i++) {
                        Resolved l = lines.get(i);
                        SoldLine s = sold.get(i);
                        StockRecordLine rl = recordLines.get(i);
                        parts.add(new JournalPart(s.accountCode(), rl.amount(), BigDecimal.ZERO, s.categoryId(), null, null, s.costObjectId(), s.farmId()));
                        BigDecimal value = returnValue(ctx.tenantId(), r.originalDocumentId(), l.item().id(), l.baseQuantity());
                        Long lot = l.item().trackLots() ? ledger.receivingLot(l.item(), l.lotNo(), null, r.docDate(), null) : null;
                        ledger.receive(ctx.tenantId(), id, lineIds.get(i), "CUSTOMER_RETURN", l.item().id(), rl.locationId(), lot, l.baseQuantity(), value,
                                r.docDate(), s.costObjectId(), null);
                        parts.add(new JournalPart(LedgerAccount.STOCK.code(), value, BigDecimal.ZERO, null, null, null, null, s.farmId()));
                        parts.add(new JournalPart(LedgerAccount.COST_OF_GOODS_SOLD.code(), BigDecimal.ZERO, value, null, null, null, s.costObjectId(), s.farmId()));
                    }
                    return parts;
                });
        return new StockRecordResult(doc, List.of());
    }

    /** The original sale's line and movement for an item; the item must have been sold from stock on that sale. */
    private SoldLine soldLine(TenantContext ctx, long saleId, long itemId) {
        return jdbc.sql("""
                        select l.item_id, l.category_id, l.account_code, l.cost_object_id, coalesce(l.farm_id, d.farm_id), m.location_id, m.lot_id
                        from sf_finance_document_lines l join sf_finance_documents d on d.id = l.document_id
                        join sf_stock_movements m on m.document_line_id = l.id and m.reverses_movement_id is null
                        where l.document_id = :d and l.tenant_id = :t and l.item_id = :i order by l.line_no limit 1""")
                .param("d", saleId).param("t", ctx.tenantId()).param("i", itemId)
                .query((rs, n) -> new SoldLine(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), (Long) rs.getObject(4), (Long) rs.getObject(5),
                        rs.getLong(6), (Long) rs.getObject(7)))
                .optional().orElseThrow(() -> ApiException.badRequest("ITEM_NOT_ON_SALE", "This item was not sold from stock on that sale."));
    }

    /**
     * Value of returned stock: the cost it left with on the sale, in proportion; the last units returned take exactly
     * what is left. Refuses returning more than was sold, net of earlier returns.
     */
    private BigDecimal returnValue(long tenantId, long saleId, long itemId, BigDecimal quantity) {
        Map<String, Object> s = jdbc.sql("""
                        select coalesce(-sum(m.quantity), 0) as qty, coalesce(-sum(m.value), 0) as value from sf_stock_movements m
                        where m.tenant_id = :t and m.document_id = :d and m.item_id = :i and m.movement_type = 'SALE'""")
                .param("t", tenantId).param("d", saleId).param("i", itemId).query().singleRow();
        Map<String, Object> back = jdbc.sql("""
                        select coalesce(sum(m.quantity), 0) as qty, coalesce(sum(m.value), 0) as value from sf_stock_movements m
                        join sf_finance_documents d on d.id = m.document_id
                        where m.tenant_id = :t and d.original_document_id = :d and m.item_id = :i and m.movement_type = 'CUSTOMER_RETURN'""")
                .param("t", tenantId).param("d", saleId).param("i", itemId).query().singleRow();
        BigDecimal soldQty = (BigDecimal) s.get("qty"), soldValue = (BigDecimal) s.get("value");
        BigDecimal leftQty = soldQty.subtract((BigDecimal) back.get("qty")), leftValue = soldValue.subtract((BigDecimal) back.get("value"));
        if (quantity.compareTo(leftQty) > 0) {
            throw ApiException.badRequest("RETURN_EXCEEDS_SALE", "Only " + leftQty.stripTrailingZeros().toPlainString() + " of this item is left to return.");
        }
        if (quantity.compareTo(leftQty) == 0) return leftValue;
        return quantity.multiply(soldValue).divide(soldQty, 2, RoundingMode.HALF_UP).min(leftValue);
    }

    private Map<String, Object> requireOriginal(TenantContext ctx, long documentId, String type, Long partyId) {
        Map<String, Object> d = jdbc.sql("select doc_type, status, party_id, farm_id from sf_finance_documents where id = :id and tenant_id = :t")
                .param("id", documentId).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Record not found."));
        if (!type.equals(d.get("doc_type")) || !"POSTED".equals(d.get("status"))) {
            throw ApiException.badRequest("ORIGINAL_NOT_RETURNABLE", "Returns must refer to a posted " + type.toLowerCase() + ".");
        }
        if (partyId != null && !partyId.equals(d.get("party_id"))) {
            throw ApiException.badRequest("ALLOCATION_PARTY_MISMATCH", "Both records must be for the same party.");
        }
        return d;
    }

    private Long systemCategory(String code) {
        return jdbc.sql("select id from sf_finance_categories where tenant_id is null and code = :c").param("c", code).query(Long.class).single();
    }

    private static void warn(List<String> warnings, StockLedger.Moved m, Resolved l) {
        if (m.expiredLot()) warnings.add("LOT_EXPIRED:" + l.item().name() + ":" + l.lotNo());
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
