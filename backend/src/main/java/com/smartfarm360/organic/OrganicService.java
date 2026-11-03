package com.smartfarm360.organic;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.finance.ReportService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.organic.OrganicDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Organic batches (organic resources design): compost pits, vermicompost beds and liquid manures as cost objects (L1),
 * their log (L6), output into stock at nil value (L7) and figures (L8). Inputs arrive through internal transfers, bills
 * and stock uses (L2); this service reads them back for the figures.
 */
@Service
public class OrganicService {
    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final MasterDataService master;
    private final StockRecordService stock;
    private final ReportService reports;
    private final AuditService audit;

    public OrganicService(JdbcClient jdbc, CostObjectService costObjects, MasterDataService master, StockRecordService stock, ReportService reports,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.costObjects = costObjects;
        this.master = master;
        this.stock = stock;
        this.reports = reports;
        this.audit = audit;
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static void notFuture(LocalDate d) {
        if (d.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
    }

    // ---- Batches (L1) -------------------------------------------------------------------------------------------------

    private static final String SELECT = """
            select b.id, b.cost_object_id, co.name, b.kind, b.farm_id, f.name as farm_name, b.started_on, b.ready_by, b.finished_on, b.notes,
              exists (select 1 from sf_stock_movements m where m.tenant_id = b.tenant_id and m.cost_object_id = b.cost_object_id and m.movement_type = 'PRODUCE'
                      group by m.item_id having sum(m.quantity) > 0) as has_output
            from sf_organic_batches b
            join sf_cost_objects co on co.id = b.cost_object_id and co.tenant_id = b.tenant_id
            join sf_farms f on f.id = b.farm_id and f.tenant_id = b.tenant_id
            where b.tenant_id = :t""";

    private static Batch batch(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDate finished = rs.getObject("finished_on", LocalDate.class);
        return new Batch(rs.getLong("id"), rs.getLong("cost_object_id"), rs.getString("name"), rs.getString("kind"), rs.getLong("farm_id"), rs.getString("farm_name"),
                rs.getObject("started_on", LocalDate.class), rs.getObject("ready_by", LocalDate.class), finished,
                finished != null ? "FINISHED" : rs.getBoolean("has_output") ? "READY" : "MAKING", rs.getString("notes"));
    }

    public Batch batch(TenantContext ctx, long id) {
        return jdbc.sql(SELECT + " and b.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> batch(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("ORGANIC_BATCH_NOT_FOUND", "Organic batch not found."));
    }

    public List<Batch> batches(TenantContext ctx) {
        return jdbc.sql(SELECT + " order by b.finished_on is not null, b.started_on desc, b.id desc").param("t", ctx.tenantId()).query((rs, i) -> batch(rs)).list();
    }

    private Batch open(TenantContext ctx, long id) {
        Batch b = batch(ctx, id);
        if (b.finishedOn() != null) throw ApiException.conflict("BATCH_FINISHED", "This batch is finished. Reopen it to record more.");
        return b;
    }

    @Transactional
    public Batch create(TenantContext ctx, BatchRequest r) {
        notFuture(r.startedOn());
        master.requireFarm(ctx.tenantId(), r.farmId());
        if (r.readyBy() != null && r.readyBy().isBefore(r.startedOn())) throw ApiException.badRequest("INVALID_DATE_RANGE", "The ready date is before the start.");
        long co = costObjects.createOrganicBatch(ctx.tenantId(), r.name().trim(), r.farmId(), r.startedOn());
        long id = jdbc.sql("""
                        insert into sf_organic_batches (tenant_id, cost_object_id, kind, farm_id, started_on, ready_by, notes, created_by)
                        values (:t, :co, :k, :f, :s, :r, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("co", co).param("k", r.kind()).param("f", r.farmId()).param("s", r.startedOn()).param("r", r.readyBy())
                .param("n", trim(r.notes())).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_BATCH_STARTED", "ORGANIC_BATCH", id, Map.of("name", r.name().trim(), "kind", r.kind()));
        return batch(ctx, id);
    }

    @Transactional
    public Batch update(TenantContext ctx, long id, BatchRequest r) {
        Batch b = batch(ctx, id);
        master.requireFarm(ctx.tenantId(), r.farmId());
        LocalDate first = jdbc.sql("""
                        select min(d) from (select min(log_date) d from sf_organic_logs where tenant_id = :t and batch_id = :b
                          union all select min(movement_date) from sf_stock_movements where tenant_id = :t and cost_object_id = :co) x""")
                .param("t", ctx.tenantId()).param("b", id).param("co", b.costObjectId()).query(LocalDate.class).optional().orElse(null);
        if (first != null && r.startedOn().isAfter(first)) throw ApiException.badRequest("BEFORE_BATCH_START", "Records exist before this start date.");
        if (r.readyBy() != null && r.readyBy().isBefore(r.startedOn())) throw ApiException.badRequest("INVALID_DATE_RANGE", "The ready date is before the start.");
        jdbc.sql("update sf_organic_batches set kind = :k, farm_id = :f, started_on = :s, ready_by = :r, notes = :n where id = :id and tenant_id = :t")
                .param("k", r.kind()).param("f", r.farmId()).param("s", r.startedOn()).param("r", r.readyBy()).param("n", trim(r.notes()))
                .param("id", id).param("t", ctx.tenantId()).update();
        costObjects.renameOrganicBatch(ctx.tenantId(), b.costObjectId(), r.name().trim());
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_BATCH_SAVED", "ORGANIC_BATCH", id, Map.of("name", r.name().trim()));
        return batch(ctx, id);
    }

    @Transactional
    public Batch finish(TenantContext ctx, long id, FinishRequest r) {
        notFuture(r.date());
        Batch b = open(ctx, id);
        if (r.date().isBefore(b.startedOn())) throw ApiException.badRequest("BEFORE_BATCH_START", "The batch started after this date.");
        jdbc.sql("update sf_organic_batches set finished_on = :d where id = :id and tenant_id = :t").param("d", r.date()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_BATCH_FINISHED", "ORGANIC_BATCH", id, Map.of("date", r.date().toString()));
        return batch(ctx, id);
    }

    @Transactional
    public Batch reopen(TenantContext ctx, long id) {
        Batch b = batch(ctx, id);
        if (b.finishedOn() == null) return b;
        jdbc.sql("update sf_organic_batches set finished_on = null where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_BATCH_REOPENED", "ORGANIC_BATCH", id, Map.of("name", b.name()));
        return batch(ctx, id);
    }

    // ---- Log (L6) -----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Log> logs(TenantContext ctx, long id) {
        batch(ctx, id);
        return jdbc.sql("select id, log_date, kind, kg, temperature, moisture, notes from sf_organic_logs where tenant_id = :t and batch_id = :b order by log_date desc, id desc")
                .param("t", ctx.tenantId()).param("b", id)
                .query((rs, i) -> new Log(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getString(6), rs.getString(7))).list();
    }

    @Transactional
    public Log addLog(TenantContext ctx, long id, LogRequest r) {
        notFuture(r.date());
        Batch b = open(ctx, id);
        if (r.date().isBefore(b.startedOn())) throw ApiException.badRequest("BEFORE_BATCH_START", "The batch started after this date.");
        if ("ADDED".equals(r.kind()) && r.kg() == null && trim(r.notes()) == null) throw ApiException.badRequest("LOG_EMPTY", "Say what was added, or its kg.");
        long logId = jdbc.sql("""
                        insert into sf_organic_logs (tenant_id, batch_id, log_date, kind, kg, temperature, moisture, notes, created_by)
                        values (:t, :b, :d, :k, :kg, :temp, :m, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("b", id).param("d", r.date()).param("k", r.kind()).param("kg", r.kg()).param("temp", r.temperature())
                .param("m", r.moisture()).param("n", trim(r.notes())).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_LOG_RECORDED", "ORGANIC_BATCH", id, Map.of("kind", r.kind(), "date", r.date().toString()));
        return logs(ctx, id).stream().filter(l -> l.id() == logId).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteLog(TenantContext ctx, long logId) {
        Long batchId = jdbc.sql("select batch_id from sf_organic_logs where id = :id and tenant_id = :t").param("id", logId).param("t", ctx.tenantId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("ORGANIC_LOG_NOT_FOUND", "Log entry not found."));
        open(ctx, batchId);
        jdbc.sql("delete from sf_organic_logs where id = :id and tenant_id = :t").param("id", logId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_LOG_DELETED", "ORGANIC_BATCH", batchId, Map.of("log", logId));
    }

    // ---- Output (L7) ---------------------------------------------------------------------------------------------------------

    @Transactional
    public Output output(TenantContext ctx, long id, OutputRequest r, String key) {
        notFuture(r.date());
        Batch b = open(ctx, id);
        if (r.date().isBefore(b.startedOn())) throw ApiException.badRequest("BEFORE_BATCH_START", "The batch started after this date.");
        long doc = stock.produce(ctx, new StockDtos.ProduceRequest(r.date(), r.locationId(), b.costObjectId(), b.farmId(),
                List.of(new StockDtos.ItemQuantity(r.itemId(), r.quantity(), r.unit(), null, null)), "Output, " + b.name()), key).document().summary().id();
        if (Boolean.TRUE.equals(r.finish())) {
            jdbc.sql("update sf_organic_batches set finished_on = :d where id = :id and tenant_id = :t").param("d", r.date()).param("id", id).param("t", ctx.tenantId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "ORGANIC_OUTPUT_RECORDED", "ORGANIC_BATCH", id, Map.of("quantity", r.quantity(), "finished", Boolean.TRUE.equals(r.finish())));
        return outputs(ctx, id).stream().filter(o -> o.documentId() == doc).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Output> outputs(TenantContext ctx, long id) {
        Batch b = batch(ctx, id);
        return jdbc.sql("""
                        select d.id, d.number, d.doc_date, m.item_id, i.name, sum(m.quantity) filter (where m.quantity > 0), i.base_unit_code, d.status <> 'POSTED'
                        from sf_stock_movements m
                        join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'PRODUCE' and d.doc_type = 'PRODUCE_RECEIPT'
                        group by d.id, d.number, d.doc_date, m.item_id, i.name, i.base_unit_code, d.status order by d.doc_date desc, d.id desc""")
                .param("t", ctx.tenantId()).param("co", b.costObjectId())
                .query((rs, i) -> new Output(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getLong(4), rs.getString(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getBoolean(8))).list();
    }

    // ---- Figures (L8) ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BatchReport report(TenantContext ctx, long id, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "The date range is too long.");
        Batch b = batch(ctx, id);
        return new BatchReport(b, figures(ctx, b, from, to), b.startedOn().isAfter(to) ? null : figures(ctx, b, b.startedOn(), to));
    }

    /** Every batch with activity in a period (the organic batches report). */
    @Transactional(readOnly = true)
    public List<BatchReport> all(TenantContext ctx, LocalDate from, LocalDate to) {
        List<BatchReport> out = new ArrayList<>();
        for (Batch b : batches(ctx)) {
            if (b.startedOn().isAfter(to) || (b.finishedOn() != null && b.finishedOn().isBefore(from) && !moneyAfter(ctx, b, from))) continue;
            out.add(report(ctx, b.id(), from, to));
        }
        return out;
    }

    /** Compost is often sold or spread after the batch is finished; such a batch still belongs in the period. */
    private boolean moneyAfter(TenantContext ctx, Batch b, LocalDate from) {
        return jdbc.sql("""
                        select exists (select 1 from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                          where l.tenant_id = :t and l.cost_object_id = :co and e.entry_date >= :from)""")
                .param("t", ctx.tenantId()).param("co", b.costObjectId()).param("from", from).query(Boolean.class).single();
    }

    Figures figures(TenantContext ctx, Batch b, LocalDate from, LocalDate to) {
        long t = ctx.tenantId();
        LocalDate end = b.finishedOn() != null && b.finishedOn().isBefore(to) ? b.finishedOn() : to;
        Integer days = b.startedOn().isAfter(to) ? null : (int) ChronoUnit.DAYS.between(b.startedOn(), end);
        // Input: stock used on the batch (own produce moved in and bought stock) in kg, and log additions in kg
        BigDecimal stockIn = jdbc.sql("""
                        select coalesce(sum(-m.quantity * u.factor), 0) from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        join sf_units u on u.code = i.base_unit_code and u.dimension = 'MASS'
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'ISSUE' and m.movement_date between :from and :to""")
                .param("t", t).param("co", b.costObjectId()).param("from", from).param("to", to).query(BigDecimal.class).single();
        BigDecimal logIn = jdbc.sql("select coalesce(sum(kg), 0) from sf_organic_logs where tenant_id = :t and batch_id = :b and kind = 'ADDED' and log_date between :from and :to")
                .param("t", t).param("b", b.id()).param("from", from).param("to", to).query(BigDecimal.class).single();
        BigDecimal input = stockIn.add(logIn).setScale(3, RoundingMode.HALF_UP);
        // Output: produce into stock from the batch, in kg (or litres for liquids)
        Map<String, Object> out = jdbc.sql("""
                        select coalesce(sum(m.quantity * u.factor) filter (where u.dimension = 'MASS'), 0) as kg,
                          coalesce(sum(m.quantity * u.factor) filter (where u.dimension = 'VOLUME'), 0) as litres,
                          coalesce(sum(m.quantity * u.factor) filter (where u.dimension = 'MASS' and m.movement_date <= :to), 0) as kg_to_date,
                          coalesce(sum(m.quantity * u.factor) filter (where u.dimension = 'VOLUME' and m.movement_date <= :to), 0) as litres_to_date
                        from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        join sf_units u on u.code = i.base_unit_code
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'PRODUCE' and m.movement_date between :from and :to""")
                .param("t", t).param("co", b.costObjectId()).param("from", from).param("to", to).query().singleRow();
        boolean liquid = ((BigDecimal) out.get("litres")).signum() > 0 && ((BigDecimal) out.get("kg")).signum() == 0 || "LIQUID".equals(b.kind());
        BigDecimal output = ((BigDecimal) out.get(liquid ? "litres" : "kg")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal yield = !liquid && input.signum() > 0 && output.signum() > 0
                ? output.multiply(BigDecimal.valueOf(100)).divide(input, 1, RoundingMode.HALF_UP) : null;
        // In stock: everything produced up to the end date, less what was moved out or sold from the batch
        BigDecimal producedAll = jdbc.sql("""
                        select coalesce(sum(m.quantity * u.factor), 0) from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id join sf_units u on u.code = i.base_unit_code
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'PRODUCE' and m.movement_date <= :to""")
                .param("t", t).param("co", b.costObjectId()).param("to", to).query(BigDecimal.class).single();
        BigDecimal movedAll = jdbc.sql("""
                        select coalesce(sum(x.base_quantity * u.factor), 0) from sf_internal_transfers x
                        join sf_finance_documents d on d.id = x.document_id and d.tenant_id = x.tenant_id
                        join sf_stock_items i on i.id = x.item_id and i.tenant_id = x.tenant_id join sf_units u on u.code = i.base_unit_code
                        where x.tenant_id = :t and x.source_cost_object_id = :co and d.status = 'POSTED' and d.doc_date <= :to""")
                .param("t", t).param("co", b.costObjectId()).param("to", to).query(BigDecimal.class).single();
        BigDecimal soldAll = jdbc.sql("""
                        select coalesce(sum(-m.quantity * u.factor), 0) from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id join sf_units u on u.code = i.base_unit_code
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'SALE' and m.movement_date <= :to
                          and m.item_id in (select item_id from sf_stock_movements where tenant_id = :t and cost_object_id = :co and movement_type = 'PRODUCE')""")
                .param("t", t).param("co", b.costObjectId()).param("to", to).query(BigDecimal.class).single();
        BigDecimal inStock = producedAll.subtract(movedAll).subtract(soldAll).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);

        BigDecimal costs = null, movedOut = null, revenue = null, profit = null, perUnit = null;
        if (ctx.has(Permission.FINANCE_READ)) {
            ReportService.IncomeStatement s = reports.incomeStatement(ctx, from, to, null, b.costObjectId(), "ACCRUAL");
            movedOut = jdbc.sql("""
                            select coalesce(sum(x.cost_moved), 0) from sf_internal_transfers x
                            join sf_finance_documents d on d.id = x.document_id and d.tenant_id = x.tenant_id
                            where x.tenant_id = :t and x.source_cost_object_id = :co and d.status = 'POSTED' and d.doc_date between :from and :to""")
                    .param("t", t).param("co", b.costObjectId()).param("from", from).param("to", to).query(BigDecimal.class).single();
            BigDecimal net = s.operatingCostTotal().add(s.interest()).add(s.losses());
            costs = net.add(movedOut);
            revenue = s.operatingRevenueTotal().add(s.otherIncomeTotal());
            profit = revenue.subtract(net);
            perUnit = output.signum() == 0 ? null : costs.divide(output, 2, RoundingMode.HALF_UP);
        }
        return new Figures(from, to, days, input, output, liquid ? "litre" : "kg", yield, costs, movedOut, revenue, profit, perUnit, inStock);
    }

    /** For the dashboard: batches past their expected ready date and not finished (L14). */
    public int overdue(TenantContext ctx, LocalDate date) {
        return jdbc.sql("select count(*) from sf_organic_batches where tenant_id = :t and finished_on is null and ready_by < :d")
                .param("t", ctx.tenantId()).param("d", date).query(Integer.class).single();
    }
}
