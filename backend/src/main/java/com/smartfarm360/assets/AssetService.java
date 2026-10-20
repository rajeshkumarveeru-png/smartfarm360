package com.smartfarm360.assets;

import com.smartfarm360.assets.AssetDtos.*;
import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.DocumentService.ModuleLine;
import com.smartfarm360.finance.DocumentService.ModuleRecord;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.finance.JournalPart;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Asset register, depreciation runs, disposal, use and maintenance (workforce and assets design, section 6). Money
 * moves only through finance records, so the register always agrees with Farm assets (1300) and accumulated
 * depreciation (1310).
 */
@Service
public class AssetService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final MasterDataService master;
    private final CostObjectService costObjects;
    private final AuditService audit;

    public AssetService(JdbcClient jdbc, DocumentService documents, MasterDataService master, CostObjectService costObjects, AuditService audit) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.master = master;
        this.costObjects = costObjects;
        this.audit = audit;
    }

    // ---- Reading ---------------------------------------------------------------------------------------------------

    private static final String ASSET_SELECT = """
            select a.id, a.name, a.kind, a.farm_id, f.name as farm_name, a.serial_no, l.document_id as source_doc, sd.number as source_number, a.cost,
                   a.salvage_value, a.useful_life_months, a.in_use_from, a.depreciation_before,
                   coalesce((select sum(x.amount) from sf_asset_depreciation x join sf_finance_documents xd on xd.id = x.document_id
                             where x.asset_id = a.id and x.tenant_id = a.tenant_id and xd.status = 'POSTED'), 0) as runs,
                   (select max(x.month) from sf_asset_depreciation x join sf_finance_documents xd on xd.id = x.document_id
                     where x.asset_id = a.id and x.tenant_id = a.tenant_id and xd.status = 'POSTED') as last_month,
                   case when dd.status = 'POSTED' then a.disposal_kind else 'IN_USE' end as status,
                   case when dd.status = 'POSTED' then a.disposal_document_id end as disposal_doc, a.cost_object_id, co.name as co_name, a.notes,
                   sd.doc_date as source_date
            from sf_assets a
            join sf_finance_document_lines l on l.id = a.source_line_id and l.tenant_id = a.tenant_id
            join sf_finance_documents sd on sd.id = l.document_id and sd.tenant_id = a.tenant_id
            left join sf_farms f on f.id = a.farm_id and f.tenant_id = a.tenant_id
            left join sf_finance_documents dd on dd.id = a.disposal_document_id and dd.tenant_id = a.tenant_id
            left join sf_cost_objects co on co.id = a.cost_object_id and co.tenant_id = a.tenant_id""";

    @Transactional(readOnly = true)
    public List<Asset> assets(TenantContext ctx, boolean includeDisposed) {
        return jdbc.sql(ASSET_SELECT + " where a.tenant_id = :t order by lower(a.name), a.id").param("t", ctx.tenantId())
                .query((rs, i) -> asset(rs)).list().stream().filter(a -> includeDisposed || "IN_USE".equals(a.status())).toList();
    }

    @Transactional(readOnly = true)
    public Asset asset(TenantContext ctx, long id) {
        return jdbc.sql(ASSET_SELECT + " where a.id = :id and a.tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> asset(rs)).optional().orElseThrow(() -> ApiException.notFound("ASSET_NOT_FOUND", "Asset not found."));
    }

    private static Asset asset(ResultSet rs) throws SQLException {
        BigDecimal cost = rs.getBigDecimal("cost"), salvage = rs.getBigDecimal("salvage_value"), before = rs.getBigDecimal("depreciation_before");
        BigDecimal accumulated = before.add(rs.getBigDecimal("runs")).setScale(2);
        Integer life = (Integer) rs.getObject("useful_life_months");
        String kind = rs.getString("kind");
        BigDecimal monthly = life == null || "BREEDING_ANIMAL".equals(kind) ? null : monthly(cost, salvage, life);
        java.sql.Date last = rs.getDate("last_month");
        return new Asset(rs.getLong("id"), rs.getString("name"), kind, (Long) rs.getObject("farm_id"), rs.getString("farm_name"), rs.getString("serial_no"),
                rs.getLong("source_doc"), rs.getString("source_number"), cost, salvage, life, rs.getObject("in_use_from", LocalDate.class), before,
                accumulated, cost.subtract(accumulated), monthly, last == null ? null : YearMonth.from(last.toLocalDate()).toString(), rs.getString("status"),
                (Long) rs.getObject("disposal_doc"), (Long) rs.getObject("cost_object_id"), rs.getString("co_name"), rs.getString("notes"));
    }

    static BigDecimal monthly(BigDecimal cost, BigDecimal salvage, int life) {
        return cost.subtract(salvage).divide(BigDecimal.valueOf(life), 2, RoundingMode.HALF_UP);
    }

    /** Asset costs in the accounts (posted lines on 1300) not yet in the register. */
    @Transactional(readOnly = true)
    public List<UnregisteredLine> unregistered(TenantContext ctx) {
        return jdbc.sql("""
                        select l.id, d.id, d.doc_type, d.number, d.doc_date, l.description, l.category_id, c.code, c.name, l.amount
                        from sf_finance_document_lines l join sf_finance_documents d on d.id = l.document_id and d.tenant_id = l.tenant_id
                        left join sf_finance_categories c on c.id = l.category_id
                        where l.tenant_id = :t and l.account_code = '1300' and d.status = 'POSTED' and d.doc_type in ('BILL', 'OPENING_BALANCE')
                          and not exists (select 1 from sf_assets a where a.source_line_id = l.id)
                        order by d.doc_date, l.id""")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new UnregisteredLine(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class),
                        rs.getString(6), (Long) rs.getObject(7), rs.getString(8), rs.getString(9), rs.getBigDecimal(10)))
                .list();
    }

    // ---- Registering (G8) --------------------------------------------------------------------------------------------

    @Transactional
    public Asset register(TenantContext ctx, RegisterRequest r) {
        Map<String, Object> line = jdbc.sql("""
                        select l.amount, l.account_code, d.status, d.doc_date, d.doc_type from sf_finance_document_lines l
                        join sf_finance_documents d on d.id = l.document_id and d.tenant_id = l.tenant_id where l.id = :id and l.tenant_id = :t""")
                .param("id", r.sourceLineId()).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("ASSET_LINE_NOT_FOUND", "That asset cost was not found."));
        if (!"1300".equals(line.get("account_code")) || !"POSTED".equals(line.get("status"))) {
            throw ApiException.badRequest("NOT_AN_ASSET_COST", "Only posted farm-asset costs can be registered.");
        }
        return insert(ctx, r.sourceLineId(), (BigDecimal) line.get("amount"), ((java.sql.Date) line.get("doc_date")).toLocalDate(), r.asset());
    }

    /** Buys an asset on a bill and registers it in the same transaction. */
    @Transactional
    public Asset purchase(TenantContext ctx, PurchaseRequest r, String key) {
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a purchase needs permission to record money.");
        String code = switch (r.asset().kind()) { case "BREEDING_ANIMAL" -> "BREEDING_ANIMALS"; case "STRUCTURE" -> "BUILDINGS"; default -> "EQUIPMENT"; };
        Long category = jdbc.sql("select id from sf_finance_categories where tenant_id is null and code = :c").param("c", code).query(Long.class).single();
        Document bill = documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(r.docDate(), null, r.partyId(), r.asset().farmId(),
                r.partyId() == null ? r.financialAccountId() : null,
                List.of(new LineRequest(category, r.asset().name().trim(), null, null, null, r.amount(), null, r.asset().farmId())),
                r.asset().notes(), r.referenceNo(), true, r.paidNow()), key);
        long lineId = bill.lines().get(0).id();
        Optional<Long> existing = jdbc.sql("select id from sf_assets where source_line_id = :l and tenant_id = :t").param("l", lineId).param("t", ctx.tenantId())
                .query(Long.class).optional();
        if (existing.isPresent()) return asset(ctx, existing.get());   // a retry with the same key
        return insert(ctx, lineId, bill.lines().get(0).amount(), r.docDate(), r.asset());
    }

    private Asset insert(TenantContext ctx, long lineId, BigDecimal cost, LocalDate sourceDate, AssetDetails d) {
        if (jdbc.sql("select exists (select 1 from sf_assets where source_line_id = :l)").param("l", lineId).query(Boolean.class).single()) {
            throw ApiException.conflict("ASSET_ALREADY_REGISTERED", "This asset cost is already in the register.");
        }
        BigDecimal salvage = d.salvageValue() == null ? ZERO : d.salvageValue(), before = d.depreciationBefore() == null ? ZERO : d.depreciationBefore();
        boolean animal = "BREEDING_ANIMAL".equals(d.kind());
        if (!animal && d.usefulLifeMonths() == null) throw ApiException.badRequest("USEFUL_LIFE_REQUIRED", "Enter the asset's useful life in months.");
        if (salvage.add(before).compareTo(cost) > 0) throw ApiException.badRequest("SALVAGE_TOO_HIGH", "Salvage value and earlier depreciation exceed the cost.");
        if (d.farmId() != null) master.requireFarm(ctx.tenantId(), d.farmId());
        if (d.costObjectId() != null) master.requireCostObject(ctx.tenantId(), d.costObjectId());
        long id = jdbc.sql("""
                        insert into sf_assets (tenant_id, name, kind, farm_id, serial_no, source_line_id, cost, salvage_value, useful_life_months, in_use_from,
                          depreciation_before, cost_object_id, notes, created_by)
                        values (:t, :n, :k, :f, :s, :l, :c, :sv, :life, :use, :before, :co, :notes, :u) returning id""")
                .param("t", ctx.tenantId()).param("n", d.name().trim()).param("k", d.kind()).param("f", d.farmId()).param("s", trim(d.serialNo()))
                .param("l", lineId).param("c", cost).param("sv", salvage).param("life", animal ? null : d.usefulLifeMonths())
                .param("use", d.inUseFrom() != null ? d.inUseFrom() : sourceDate).param("before", before).param("co", d.costObjectId())
                .param("notes", trim(d.notes())).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "ASSET_REGISTERED", "ASSET", id, Map.of("name", d.name().trim(), "cost", cost));
        return asset(ctx, id);
    }

    @Transactional
    public Asset update(TenantContext ctx, long id, AssetPatch p) {
        Asset a = asset(ctx, id);
        boolean depreciated = hasPostedDepreciation(ctx.tenantId(), id);
        if (depreciated && (p.usefulLifeMonths() != null || p.salvageValue() != null || p.inUseFrom() != null || p.depreciationBefore() != null)) {
            throw ApiException.conflict("ASSET_ALREADY_DEPRECIATED", "Depreciation has been posted; reverse those runs to change life, salvage or dates.");
        }
        BigDecimal salvage = p.salvageValue() != null ? p.salvageValue() : a.salvageValue();
        BigDecimal before = p.depreciationBefore() != null ? p.depreciationBefore() : a.depreciationBefore();
        if (salvage.add(before).compareTo(a.cost()) > 0) throw ApiException.badRequest("SALVAGE_TOO_HIGH", "Salvage value and earlier depreciation exceed the cost.");
        if (p.farmId() != null) master.requireFarm(ctx.tenantId(), p.farmId());
        if (p.costObjectId() != null) master.requireCostObject(ctx.tenantId(), p.costObjectId());
        jdbc.sql("""
                        update sf_assets set name = coalesce(:n, name), serial_no = coalesce(:s, serial_no), farm_id = coalesce(:f, farm_id),
                          cost_object_id = coalesce(:co, cost_object_id), notes = coalesce(:notes, notes),
                          useful_life_months = case when kind = 'BREEDING_ANIMAL' then null else coalesce(:life, useful_life_months) end,
                          salvage_value = :sv, in_use_from = coalesce(:use, in_use_from), depreciation_before = :before
                        where id = :id and tenant_id = :t""")
                .param("n", p.name() == null ? null : p.name().trim()).param("s", trim(p.serialNo())).param("f", p.farmId()).param("co", p.costObjectId())
                .param("notes", trim(p.notes())).param("life", p.usefulLifeMonths()).param("sv", salvage).param("use", p.inUseFrom()).param("before", before)
                .param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ASSET_UPDATED", "ASSET", id, Map.of());
        return asset(ctx, id);
    }

    /** Removes a wrongly registered asset that no record refers to yet (its cost stays in the accounts as unregistered). */
    @Transactional
    public void remove(TenantContext ctx, long id) {
        Asset a = asset(ctx, id);
        boolean used = jdbc.sql("""
                        select exists (select 1 from sf_asset_depreciation where asset_id = :id) or exists (select 1 from sf_finance_document_lines where asset_id = :id)
                            or exists (select 1 from sf_finance_documents where asset_id = :id) or exists (select 1 from sf_assets where id = :id and disposal_document_id is not null)""")
                .param("id", id).query(Boolean.class).single();
        if (used) throw ApiException.conflict("ASSET_HAS_RECORDS", "Records refer to this asset, so it stays in the register.");
        for (String table : List.of("sf_maintenance_logs", "sf_maintenance_plans", "sf_asset_usage")) {
            jdbc.sql("delete from " + table + " where asset_id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        }
        jdbc.sql("delete from sf_assets where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ASSET_REMOVED", "ASSET", id, Map.of("name", a.name()));
    }

    // ---- Depreciation runs (G9, G10) --------------------------------------------------------------------------------

    private record Candidate(long id, String name, BigDecimal cost, BigDecimal salvage, int life, LocalDate inUseFrom, LocalDate sourceDate,
                             BigDecimal accumulated, int monthsTaken, Long farmId, Long costObjectId, LocalDate disposedOn) {
        /** Depreciation starts the month after the later of the in-use date and the record that brought the asset in. */
        YearMonth startMonth() {
            YearMonth a = YearMonth.from(inUseFrom), b = YearMonth.from(sourceDate);
            return (a.isAfter(b) ? a : b).plusMonths(1);
        }

        BigDecimal remaining() { return cost.subtract(salvage).subtract(accumulated); }

        boolean eligible(YearMonth m) {
            return !m.isBefore(startMonth()) && (disposedOn == null || disposedOn.isAfter(m.atEndOfMonth())) && remaining().signum() > 0;
        }

        /** The last month takes the remainder exactly. */
        BigDecimal amount() {
            BigDecimal monthly = monthly(cost, salvage, life);
            return monthsTaken + 1 >= life ? remaining() : monthly.min(remaining());
        }
    }

    private List<Candidate> candidates(long tenantId) {
        return jdbc.sql("""
                        select a.id, a.name, a.cost, a.salvage_value, a.useful_life_months, a.in_use_from, sd.doc_date,
                               a.depreciation_before + coalesce((select sum(x.amount) from sf_asset_depreciation x join sf_finance_documents xd on xd.id = x.document_id
                                   where x.asset_id = a.id and xd.status = 'POSTED'), 0),
                               (select count(*) from sf_asset_depreciation x join sf_finance_documents xd on xd.id = x.document_id
                                   where x.asset_id = a.id and xd.status = 'POSTED'),
                               a.farm_id, a.cost_object_id, case when dd.status = 'POSTED' then dd.doc_date end
                        from sf_assets a
                        join sf_finance_document_lines l on l.id = a.source_line_id join sf_finance_documents sd on sd.id = l.document_id
                        left join sf_finance_documents dd on dd.id = a.disposal_document_id
                        where a.tenant_id = :t and a.kind <> 'BREEDING_ANIMAL' and a.useful_life_months is not null order by a.id""")
                .param("t", tenantId)
                .query((rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getInt(5),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getBigDecimal(8), rs.getInt(9), (Long) rs.getObject(10),
                        (Long) rs.getObject(11), rs.getObject(12, LocalDate.class)))
                .list();
    }

    private Optional<YearMonth> lastRun(long tenantId) {
        return jdbc.sql("""
                        select max(x.month) from sf_asset_depreciation x join sf_finance_documents d on d.id = x.document_id
                        where x.tenant_id = :t and d.status = 'POSTED'""")
                .param("t", tenantId).query(LocalDate.class).optional().map(YearMonth::from);
    }

    /** Checks the month can be run: not already run, not in the future, and every earlier month with something to depreciate run first. */
    private List<Candidate> due(long tenantId, YearMonth m) {
        if (m.isAfter(YearMonth.now())) throw ApiException.badRequest("FUTURE_MONTH", "Depreciation can only be run for this month or earlier.");
        List<Candidate> all = candidates(tenantId);
        Optional<YearMonth> last = lastRun(tenantId);
        if (last.isPresent() && !m.isAfter(last.get())) {
            throw ApiException.conflict(m.equals(last.get()) ? "DEPRECIATION_RUN_EXISTS" : "DEPRECIATION_OUT_OF_ORDER",
                    "Depreciation is already run up to " + last.get() + ".");
        }
        YearMonth start = last.map(x -> x.plusMonths(1)).orElse(all.stream().map(Candidate::startMonth).min(Comparator.naturalOrder()).orElse(m));
        for (YearMonth x = start; x.isBefore(m); x = x.plusMonths(1)) {
            YearMonth month = x;
            if (all.stream().anyMatch(c -> c.eligible(month))) {
                throw ApiException.conflict("DEPRECIATION_OUT_OF_ORDER", "Run depreciation for " + month + " first.");
            }
        }
        return all.stream().filter(c -> c.eligible(m)).toList();
    }

    @Transactional(readOnly = true)
    public RunPreview previewRun(TenantContext ctx, String month) {
        YearMonth m = YearMonth.parse(month);
        List<RunLine> lines = due(ctx.tenantId(), m).stream()
                .map(c -> new RunLine(c.id(), c.name(), c.amount(), c.cost().subtract(c.accumulated()).subtract(c.amount()))).toList();
        return new RunPreview(m.toString(), lines, lines.stream().map(RunLine::amount).reduce(ZERO, BigDecimal::add));
    }

    /** Posts one depreciation record for the month: Dr 5800 per asset (overhead or the asset's activity) / Cr 1310. */
    @Transactional
    public Document run(TenantContext ctx, RunRequest r, String key) {
        Optional<Document> replay = documents.replayed(ctx, key, DocType.DEPRECIATION, r);
        if (replay.isPresent()) return replay.get();
        jdbc.sql("select id from sf_tenants where id = :t for update").param("t", ctx.tenantId()).query(Long.class).single();
        YearMonth m = YearMonth.parse(r.month());
        List<Candidate> due = due(ctx.tenantId(), m);
        if (due.isEmpty()) throw ApiException.badRequest("NOTHING_TO_DEPRECIATE", "No asset needs depreciation for " + m + ".");
        BigDecimal total = due.stream().map(Candidate::amount).reduce(ZERO, BigDecimal::add);
        Long category = null;
        List<ModuleLine> lines = due.stream().map(c -> new ModuleLine(category, LedgerAccount.DEPRECIATION.code(), "DEBIT", c.name() + " · " + m, null, null,
                c.amount(), c.costObjectId() != null ? c.costObjectId() : costObjects.overhead(ctx.tenantId()), c.farmId(), null, c.id())).toList();
        List<JournalPart> parts = new ArrayList<>();
        for (Candidate c : due) {
            parts.add(new JournalPart(LedgerAccount.DEPRECIATION.code(), c.amount(), BigDecimal.ZERO, null, null, null, c.costObjectId(), c.farmId()));
        }
        parts.add(JournalPart.credit(LedgerAccount.ACCUMULATED_DEPRECIATION.code(), total, null, null, null));
        Document doc = documents.recordModule(ctx, new ModuleRecord(DocType.DEPRECIATION, m.atEndOfMonth(), null, null, null, null, null, null, total,
                "Depreciation for " + m, null, lines), key, r, (id, lineIds) -> {
            for (Candidate c : due) {
                jdbc.sql("insert into sf_asset_depreciation (tenant_id, asset_id, document_id, month, amount) values (:t, :a, :d, :m, :amt)")
                        .param("t", ctx.tenantId()).param("a", c.id()).param("d", id).param("m", m.atDay(1)).param("amt", c.amount()).update();
            }
            return parts;
        });
        return doc;
    }

    @Transactional(readOnly = true)
    public List<RunSummary> runs(TenantContext ctx) {
        return jdbc.sql("""
                        select x.month, d.id, d.number, d.status, d.total_amount from sf_finance_documents d
                        join (select distinct document_id, month from sf_asset_depreciation where tenant_id = :t) x on x.document_id = d.id
                        where d.tenant_id = :t order by x.month desc, d.id desc""")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new RunSummary(YearMonth.from(rs.getObject(1, LocalDate.class)).toString(), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5))).list();
    }

    /** Past (posted) and expected months for one asset until it reaches its salvage value. */
    @Transactional(readOnly = true)
    public List<ScheduleLine> schedule(TenantContext ctx, long id) {
        Asset a = asset(ctx, id);
        List<ScheduleLine> out = new ArrayList<>();
        BigDecimal book = a.cost().subtract(a.depreciationBefore());
        for (Map<String, Object> r : jdbc.sql("""
                        select x.month, x.amount from sf_asset_depreciation x join sf_finance_documents d on d.id = x.document_id
                        where x.asset_id = :id and x.tenant_id = :t and d.status = 'POSTED' order by x.month""")
                .param("id", id).param("t", ctx.tenantId()).query().listOfRows()) {
            book = book.subtract((BigDecimal) r.get("amount"));
            out.add(new ScheduleLine(YearMonth.from(((java.sql.Date) r.get("month")).toLocalDate()).toString(), (BigDecimal) r.get("amount"), book, true));
        }
        if (a.monthlyDepreciation() == null || !"IN_USE".equals(a.status())) return out;
        Candidate c = candidates(ctx.tenantId()).stream().filter(x -> x.id() == id).findFirst().orElse(null);
        if (c == null) return out;
        YearMonth next = a.lastDepreciatedMonth() != null ? YearMonth.parse(a.lastDepreciatedMonth()).plusMonths(1) : c.startMonth();
        BigDecimal accumulated = c.accumulated();
        int taken = c.monthsTaken();
        for (int i = 0; i < 1200; i++) {
            Candidate step = new Candidate(c.id(), c.name(), c.cost(), c.salvage(), c.life(), c.inUseFrom(), c.sourceDate(), accumulated, taken, c.farmId(),
                    c.costObjectId(), null);
            if (step.remaining().signum() <= 0) break;
            BigDecimal amount = step.amount();
            accumulated = accumulated.add(amount);
            taken++;
            out.add(new ScheduleLine(next.toString(), amount, c.cost().subtract(accumulated), false));
            next = next.plusMonths(1);
        }
        return out;
    }

    // ---- Disposal (G11) --------------------------------------------------------------------------------------------

    @Transactional
    public Asset dispose(TenantContext ctx, long id, DisposalRequest r, String key) {
        Optional<Document> replay = documents.replayed(ctx, key, DocType.ASSET_DISPOSAL, r);
        if (replay.isPresent()) return asset(ctx, id);
        jdbc.sql("select id from sf_assets where id = :id and tenant_id = :t for update").param("id", id).param("t", ctx.tenantId()).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("ASSET_NOT_FOUND", "Asset not found."));
        Asset a = asset(ctx, id);
        if (!"IN_USE".equals(a.status())) throw ApiException.conflict("ASSET_NOT_IN_USE", "This asset is already sold or scrapped.");
        if (r.date().isBefore(a.inUseFrom())) throw ApiException.badRequest("INVALID_DISPOSAL_DATE", "The asset cannot leave before it came into use.");
        if (a.lastDepreciatedMonth() != null && !YearMonth.parse(a.lastDepreciatedMonth()).isBefore(YearMonth.from(r.date()))) {
            throw ApiException.conflict("DEPRECIATION_AFTER_DISPOSAL", "Depreciation is posted for " + a.lastDepreciatedMonth()
                    + "; reverse the runs from " + YearMonth.from(r.date()) + " on first.");
        }
        BigDecimal proceeds = r.proceeds() == null ? ZERO : r.proceeds().setScale(2, RoundingMode.HALF_UP);
        boolean sold = "SOLD".equals(r.kind());
        if (sold && proceeds.signum() == 0) throw ApiException.badRequest("PROCEEDS_REQUIRED", "Enter what the asset was sold for.");
        if (!sold && proceeds.signum() != 0) throw ApiException.badRequest("SCRAP_HAS_NO_PROCEEDS", "Scrap sold for money is a sale.");
        if (sold && r.partyId() == null && r.financialAccountId() == null) {
            throw ApiException.badRequest("PARTY_OR_ACCOUNT_REQUIRED", "Choose the buyer, or the cash/bank account the money came into.");
        }
        BigDecimal gain = proceeds.add(a.accumulated()).subtract(a.cost());
        List<JournalPart> parts = new ArrayList<>();
        if (sold && r.partyId() != null) parts.add(JournalPart.debit(LedgerAccount.RECEIVABLES.code(), proceeds, null, r.partyId(), null));
        else if (sold) parts.add(JournalPart.cash(r.financialAccountId(), proceeds, BigDecimal.ZERO));
        if (a.accumulated().signum() > 0) parts.add(JournalPart.debit(LedgerAccount.ACCUMULATED_DEPRECIATION.code(), a.accumulated(), null, null, null));
        parts.add(JournalPart.credit(LedgerAccount.FARM_ASSETS.code(), a.cost(), null, null, null));
        if (gain.signum() > 0) parts.add(JournalPart.credit(LedgerAccount.ASSET_SALE_GAIN.code(), gain, null, null, null));
        if (gain.signum() < 0) parts.add(JournalPart.debit(LedgerAccount.LOSSES.code(), gain.negate(), null, null, null));
        List<ModuleLine> lines = List.of(new ModuleLine(null, LedgerAccount.FARM_ASSETS.code(), "CREDIT", (sold ? "Sold · " : "Scrapped · ") + a.name(),
                null, null, a.cost(), null, a.farmId(), r.partyId(), a.id()));
        Document doc = documents.recordModule(ctx, new ModuleRecord(DocType.ASSET_DISPOSAL, r.date(), sold ? r.partyId() : null, a.farmId(),
                sold && r.partyId() == null ? r.financialAccountId() : null, null, null, a.id(), a.cost(), r.notes(), null, lines), key, r, (docId, ids) -> parts);
        jdbc.sql("update sf_assets set disposal_document_id = :d, disposal_kind = :k where id = :id and tenant_id = :t")
                .param("d", doc.summary().id()).param("k", r.kind()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ASSET_DISPOSED", "ASSET", id, Map.of("kind", r.kind(), "proceeds", proceeds, "gain", gain));
        return asset(ctx, id);
    }

    // ---- Use and maintenance (G13) ---------------------------------------------------------------------------------

    @Transactional
    public Usage addUsage(TenantContext ctx, long assetId, UsageRequest r) {
        asset(ctx, assetId);
        if (r.hours() == null && r.km() == null && r.acres() == null) throw ApiException.badRequest("USAGE_EMPTY", "Enter hours, kilometres or acres.");
        if (r.date().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        if (r.costObjectId() != null) master.requireCostObject(ctx.tenantId(), r.costObjectId());
        long id = jdbc.sql("""
                        insert into sf_asset_usage (tenant_id, asset_id, use_date, hours, km, acres, cost_object_id, notes, created_by)
                        values (:t, :a, :d, :h, :km, :ac, :co, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("a", assetId).param("d", r.date()).param("h", r.hours()).param("km", r.km()).param("ac", r.acres())
                .param("co", r.costObjectId()).param("n", trim(r.notes())).param("u", ctx.userId()).query(Long.class).single();
        return usage(ctx, assetId).stream().filter(u -> u.id() == id).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Usage> usage(TenantContext ctx, long assetId) {
        asset(ctx, assetId);
        return jdbc.sql("""
                        select u.id, u.use_date, u.hours, u.km, u.acres, u.cost_object_id, co.name, u.notes from sf_asset_usage u
                        left join sf_cost_objects co on co.id = u.cost_object_id and co.tenant_id = u.tenant_id
                        where u.asset_id = :a and u.tenant_id = :t order by u.use_date desc, u.id desc limit 500""")
                .param("a", assetId).param("t", ctx.tenantId())
                .query((rs, i) -> new Usage(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        (Long) rs.getObject(6), rs.getString(7), rs.getString(8))).list();
    }

    @Transactional
    public void deleteUsage(TenantContext ctx, long usageId) {
        int n = jdbc.sql("delete from sf_asset_usage where id = :id and tenant_id = :t").param("id", usageId).param("t", ctx.tenantId()).update();
        if (n == 0) throw ApiException.notFound("USAGE_NOT_FOUND", "Use record not found.");
    }

    private BigDecimal totalHours(long tenantId, long assetId) {
        return jdbc.sql("select coalesce(sum(hours), 0) from sf_asset_usage where asset_id = :a and tenant_id = :t")
                .param("a", assetId).param("t", tenantId).query(BigDecimal.class).single();
    }

    @Transactional
    public Plan addPlan(TenantContext ctx, long assetId, PlanRequest r) {
        asset(ctx, assetId);
        if (r.everyDays() == null && r.everyHours() == null) throw ApiException.badRequest("PLAN_INTERVAL_REQUIRED", "Enter every how many days or hours.");
        long id = jdbc.sql("""
                        insert into sf_maintenance_plans (tenant_id, asset_id, name, every_days, every_hours, last_done_on, last_done_hours)
                        values (:t, :a, :n, :d, :h, :ld, :lh) returning id""")
                .param("t", ctx.tenantId()).param("a", assetId).param("n", r.name().trim()).param("d", r.everyDays()).param("h", r.everyHours())
                .param("ld", r.lastDoneOn()).param("lh", r.lastDoneHours()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "MAINTENANCE_PLAN_SAVED", "ASSET", assetId, Map.of("plan", r.name().trim()));
        return plans(ctx, assetId).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public Plan updatePlan(TenantContext ctx, long planId, PlanRequest r) {
        Long assetId = jdbc.sql("select asset_id from sf_maintenance_plans where id = :id and tenant_id = :t").param("id", planId).param("t", ctx.tenantId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "Maintenance plan not found."));
        if (r.everyDays() == null && r.everyHours() == null) throw ApiException.badRequest("PLAN_INTERVAL_REQUIRED", "Enter every how many days or hours.");
        jdbc.sql("""
                        update sf_maintenance_plans set name = :n, every_days = :d, every_hours = :h, last_done_on = coalesce(:ld, last_done_on),
                          last_done_hours = coalesce(:lh, last_done_hours), active = coalesce(:a, active) where id = :id and tenant_id = :t""")
                .param("n", r.name().trim()).param("d", r.everyDays()).param("h", r.everyHours()).param("ld", r.lastDoneOn()).param("lh", r.lastDoneHours())
                .param("a", r.active()).param("id", planId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "MAINTENANCE_PLAN_SAVED", "ASSET", assetId, Map.of("plan", r.name().trim()));
        return plans(ctx, assetId).stream().filter(p -> p.id() == planId).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Plan> plans(TenantContext ctx, Long assetId) {
        if (assetId != null) asset(ctx, assetId);
        LocalDate today = LocalDate.now();
        return jdbc.sql("""
                        select p.id, p.asset_id, a.name, p.name, p.every_days, p.every_hours, p.last_done_on, p.last_done_hours, p.active, a.in_use_from,
                               coalesce((select sum(u.hours) from sf_asset_usage u where u.asset_id = a.id and u.tenant_id = a.tenant_id), 0)
                        from sf_maintenance_plans p join sf_assets a on a.id = p.asset_id and a.tenant_id = p.tenant_id
                        left join sf_finance_documents dd on dd.id = a.disposal_document_id
                        where p.tenant_id = :t and (cast(:a as bigint) is null or p.asset_id = :a) and (cast(:a as bigint) is not null or (p.active and (dd.id is null or dd.status <> 'POSTED')))
                        order by p.asset_id, p.id""")
                .param("t", ctx.tenantId()).param("a", assetId)
                .query((rs, i) -> {
                    Integer days = (Integer) rs.getObject(5);
                    BigDecimal every = rs.getBigDecimal(6), lastHours = rs.getBigDecimal(8), total = rs.getBigDecimal(11);
                    LocalDate lastOn = rs.getObject(7, LocalDate.class);
                    LocalDate next = days == null ? null : (lastOn != null ? lastOn : rs.getObject(10, LocalDate.class)).plusDays(days);
                    BigDecimal hoursLeft = every == null ? null : every.subtract(total.subtract(lastHours == null ? BigDecimal.ZERO : lastHours));
                    String status = (next != null && next.isBefore(today)) || (hoursLeft != null && hoursLeft.signum() < 0) ? "OVERDUE"
                            : (next != null && !next.isAfter(today.plusDays(7))) || (hoursLeft != null && hoursLeft.compareTo(every.multiply(new BigDecimal("0.1"))) <= 0)
                            ? "DUE" : "OK";
                    return new Plan(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), days, every, lastOn, lastHours, next, hoursLeft, status,
                            rs.getBoolean(9));
                }).list();
    }

    @Transactional
    public Maintenance addMaintenance(TenantContext ctx, long assetId, MaintenanceRequest r) {
        asset(ctx, assetId);
        if (r.doneOn().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        if (r.planId() != null && !jdbc.sql("select exists (select 1 from sf_maintenance_plans where id = :p and asset_id = :a and tenant_id = :t)")
                .param("p", r.planId()).param("a", assetId).param("t", ctx.tenantId()).query(Boolean.class).single()) {
            throw ApiException.badRequest("PLAN_NOT_FOUND", "Choose a maintenance plan of this asset.");
        }
        if (r.documentId() != null) documents.document(ctx, r.documentId());
        BigDecimal reading = r.hoursReading() != null ? r.hoursReading() : totalHours(ctx.tenantId(), assetId);
        long id = jdbc.sql("""
                        insert into sf_maintenance_logs (tenant_id, asset_id, plan_id, done_on, hours_reading, description, document_id, created_by)
                        values (:t, :a, :p, :d, :h, :desc, :doc, :u) returning id""")
                .param("t", ctx.tenantId()).param("a", assetId).param("p", r.planId()).param("d", r.doneOn()).param("h", reading)
                .param("desc", r.description().trim()).param("doc", r.documentId()).param("u", ctx.userId()).query(Long.class).single();
        if (r.planId() != null) {
            jdbc.sql("""
                            update sf_maintenance_plans set last_done_on = greatest(coalesce(last_done_on, :d), :d),
                              last_done_hours = case when last_done_on is null or :d >= last_done_on then :h else last_done_hours end
                            where id = :p and tenant_id = :t""")
                    .param("d", r.doneOn()).param("h", reading).param("p", r.planId()).param("t", ctx.tenantId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "MAINTENANCE_DONE", "ASSET", assetId, Map.of("date", r.doneOn().toString()));
        return maintenance(ctx, assetId).stream().filter(m -> m.id() == id).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Maintenance> maintenance(TenantContext ctx, long assetId) {
        asset(ctx, assetId);
        return jdbc.sql("""
                        select m.id, m.done_on, m.plan_id, p.name, m.hours_reading, m.description, m.document_id, d.number from sf_maintenance_logs m
                        left join sf_maintenance_plans p on p.id = m.plan_id and p.tenant_id = m.tenant_id
                        left join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        where m.asset_id = :a and m.tenant_id = :t order by m.done_on desc, m.id desc""")
                .param("a", assetId).param("t", ctx.tenantId())
                .query((rs, i) -> new Maintenance(rs.getLong(1), rs.getObject(2, LocalDate.class), (Long) rs.getObject(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getString(6), (Long) rs.getObject(7), rs.getString(8))).list();
    }

    // ---- Reports ---------------------------------------------------------------------------------------------------

    /** Depreciation and running costs tagged to the asset (bill lines and stock used, G12) for a period, per hour of use. */
    @Transactional(readOnly = true)
    public Costs costs(TenantContext ctx, long assetId, LocalDate from, LocalDate to) {
        asset(ctx, assetId);
        BigDecimal depreciation = jdbc.sql("""
                        select coalesce(sum(x.amount), 0) from sf_asset_depreciation x join sf_finance_documents d on d.id = x.document_id
                        where x.asset_id = :a and x.tenant_id = :t and d.status = 'POSTED' and x.month between :from and :to""")
                .param("a", assetId).param("t", ctx.tenantId()).param("from", YearMonth.from(from).atDay(1)).param("to", to)
                .query(BigDecimal.class).single().setScale(2);
        Map<String, CostRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        select x.account_code, x.category_id, c.code, c.name, sum(x.amount) from (
                          select l.account_code, l.category_id, l.amount from sf_finance_document_lines l join sf_finance_documents d on d.id = l.document_id
                          where l.tenant_id = :t and l.asset_id = :a and d.status = 'POSTED' and d.doc_type = 'BILL' and d.doc_date between :from and :to
                          union all
                          select jl.account_code, jl.category_id, jl.debit - jl.credit from sf_journal_lines jl join sf_journal_entries e on e.id = jl.entry_id
                          join sf_finance_documents d on d.id = e.document_id
                          where jl.tenant_id = :t and d.asset_id = :a and d.status = 'POSTED' and d.doc_type = 'STOCK_ISSUE' and e.kind = 'POSTING'
                            and jl.account_code like '5%' and d.doc_date between :from and :to) x
                        left join sf_finance_categories c on c.id = x.category_id
                        group by x.account_code, x.category_id, c.code, c.name order by x.account_code""")
                .param("t", ctx.tenantId()).param("a", assetId).param("from", from).param("to", to)
                .query((rs, i) -> new CostRow(rs.getString(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5).setScale(2)))
                .list().forEach(r -> rows.put(r.accountCode() + "|" + r.categoryId(), r));
        BigDecimal running = rows.values().stream().map(CostRow::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal hours = jdbc.sql("select coalesce(sum(hours), 0) from sf_asset_usage where asset_id = :a and tenant_id = :t and use_date between :from and :to")
                .param("a", assetId).param("t", ctx.tenantId()).param("from", from).param("to", to).query(BigDecimal.class).single();
        BigDecimal perHour = hours.signum() == 0 ? null : depreciation.add(running).divide(hours, 2, RoundingMode.HALF_UP);
        return new Costs(assetId, from, to, depreciation, new ArrayList<>(rows.values()), running, hours, perHour);
    }

    @Transactional(readOnly = true)
    public Reconciliation reconciliation(TenantContext ctx) {
        long t = ctx.tenantId();
        List<Asset> inUse = assets(ctx, false);
        BigDecimal registerCost = inUse.stream().map(Asset::cost).reduce(ZERO, BigDecimal::add);
        BigDecimal registerAcc = inUse.stream().map(Asset::accumulated).reduce(ZERO, BigDecimal::add);
        BigDecimal ledgerCost = jdbc.sql("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = :t and account_code = '1300'")
                .param("t", t).query(BigDecimal.class).single().setScale(2);
        BigDecimal ledgerAcc = jdbc.sql("select coalesce(sum(credit - debit), 0) from sf_journal_lines where tenant_id = :t and account_code = '1310'")
                .param("t", t).query(BigDecimal.class).single().setScale(2);
        BigDecimal unregistered = unregistered(ctx).stream().map(UnregisteredLine::amount).reduce(ZERO, BigDecimal::add);
        // Depreciation recorded by hand (Phase 3 records, not from a run)
        BigDecimal manual = jdbc.sql("""
                        select coalesce(sum(l.credit - l.debit), 0) from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        join sf_finance_documents d on d.id = e.document_id
                        where l.tenant_id = :t and l.account_code = '1310' and d.doc_type = 'DEPRECIATION'
                          and not exists (select 1 from sf_asset_depreciation x where x.document_id = d.id)""")
                .param("t", t).query(BigDecimal.class).single().setScale(2);
        BigDecimal allBefore = jdbc.sql("select coalesce(sum(depreciation_before), 0) from sf_assets where tenant_id = :t").param("t", t)
                .query(BigDecimal.class).single().setScale(2);
        BigDecimal unexplained = ledgerCost.subtract(registerCost).subtract(unregistered)
                .subtract(ledgerAcc.subtract(registerAcc).subtract(manual).add(allBefore));
        return new Reconciliation(registerCost, registerAcc, ledgerCost, ledgerAcc, unregistered, manual, unexplained);
    }

    @Transactional(readOnly = true)
    public String registerCsv(TenantContext ctx) {
        StringBuilder csv = new StringBuilder("asset,kind,farm,serial,bought,cost,salvage,life_months,accumulated,book_value,status\r\n");
        for (Asset a : assets(ctx, true)) {
            csv.append(com.smartfarm360.inventory.Csv.row(a.name(), a.kind(), a.farmName(), a.serialNo(), a.inUseFrom().toString(), a.cost().toPlainString(),
                    a.salvageValue().toPlainString(), a.usefulLifeMonths() == null ? "" : a.usefulLifeMonths().toString(), a.accumulated().toPlainString(),
                    a.bookValue().toPlainString(), a.status()));
        }
        return csv.toString();
    }

    boolean hasPostedDepreciation(long tenantId, long assetId) {
        return jdbc.sql("""
                        select exists (select 1 from sf_asset_depreciation x join sf_finance_documents d on d.id = x.document_id
                          where x.asset_id = :a and x.tenant_id = :t and d.status = 'POSTED')""")
                .param("a", assetId).param("t", tenantId).query(Boolean.class).single();
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
