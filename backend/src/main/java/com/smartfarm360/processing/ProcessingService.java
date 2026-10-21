package com.smartfarm360.processing;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.finance.ReportService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.processing.ProcessingDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Processing batches (processing and trading design): a run of one process as a cost object (M1), inputs through
 * internal transfers, stock uses and bills (M2, M3), a step log (M4), output by role with lots from the batch code (M5),
 * and the weight balance, yields and cost per kg of the main product by the byproduct method (M6, M7).
 */
@Service
public class ProcessingService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final MasterDataService master;
    private final StockRecordService stock;
    private final ReportService reports;
    private final AuditService audit;

    public ProcessingService(JdbcClient jdbc, CostObjectService costObjects, MasterDataService master, StockRecordService stock, ReportService reports,
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

    // ---- Batches (M1) ---------------------------------------------------------------------------------------------------

    private static final String SELECT = """
            select b.id, b.cost_object_id, b.code, co.name, b.kind, b.farm_id, f.name as farm_name, b.main_item_id, i.name as item_name,
              b.started_on, b.expected_end, b.finished_on, b.notes,
              exists (select 1 from sf_processing_outputs o left join sf_finance_documents d on d.id = o.document_id and d.tenant_id = o.tenant_id
                      where o.tenant_id = b.tenant_id and o.batch_id = b.id and (o.document_id is null or d.status = 'POSTED')) as has_output
            from sf_processing_batches b
            join sf_cost_objects co on co.id = b.cost_object_id and co.tenant_id = b.tenant_id
            join sf_farms f on f.id = b.farm_id and f.tenant_id = b.tenant_id
            join sf_stock_items i on i.id = b.main_item_id and i.tenant_id = b.tenant_id
            where b.tenant_id = :t""";

    private static Batch batch(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDate finished = rs.getObject("finished_on", LocalDate.class);
        return new Batch(rs.getLong("id"), rs.getLong("cost_object_id"), rs.getString("code"), rs.getString("name"), rs.getString("kind"), rs.getLong("farm_id"),
                rs.getString("farm_name"), rs.getLong("main_item_id"), rs.getString("item_name"), rs.getObject("started_on", LocalDate.class),
                rs.getObject("expected_end", LocalDate.class), finished, finished != null ? "FINISHED" : rs.getBoolean("has_output") ? "OUTPUT" : "RUNNING",
                rs.getString("notes"));
    }

    public Batch batch(TenantContext ctx, long id) {
        return jdbc.sql(SELECT + " and b.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> batch(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("PROCESSING_BATCH_NOT_FOUND", "Processing batch not found."));
    }

    public List<Batch> batches(TenantContext ctx) {
        return jdbc.sql(SELECT + " order by b.finished_on is not null, b.started_on desc, b.id desc").param("t", ctx.tenantId()).query((rs, i) -> batch(rs)).list();
    }

    private Batch open(TenantContext ctx, long id) {
        Batch b = batch(ctx, id);
        if (b.finishedOn() != null) throw ApiException.conflict("BATCH_FINISHED", "This batch is finished. Reopen it to record more.");
        return b;
    }

    private void requireProduce(TenantContext ctx, long itemId) {
        String kind = jdbc.sql("select kind from sf_stock_items where tenant_id = :t and id = :i").param("t", ctx.tenantId()).param("i", itemId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("STOCK_ITEM_NOT_FOUND", "Stock item not found."));
        if (!"PRODUCE".equals(kind)) throw ApiException.badRequest("NOT_FARM_PRODUCE", "Processed goods are farm produce; choose a farm-produce item.");
    }

    private void checkBatch(TenantContext ctx, Long id, BatchRequest r) {
        master.requireFarm(ctx.tenantId(), r.farmId());
        requireProduce(ctx, r.mainItemId());
        if (r.expectedEnd() != null && r.expectedEnd().isBefore(r.startedOn())) throw ApiException.badRequest("INVALID_DATE_RANGE", "The expected end is before the start.");
        boolean taken = jdbc.sql("select exists (select 1 from sf_processing_batches where tenant_id = :t and upper(code) = upper(:c) and id <> coalesce(:id, 0))")
                .param("t", ctx.tenantId()).param("c", r.code().trim()).param("id", id).query(Boolean.class).single();
        if (taken) throw ApiException.conflict("BATCH_CODE_TAKEN", "Another batch has this code.");
    }

    @Transactional
    public Batch create(TenantContext ctx, BatchRequest r) {
        notFuture(r.startedOn());
        checkBatch(ctx, null, r);
        long co = costObjects.createProcessingBatch(ctx.tenantId(), r.name().trim(), r.farmId(), r.startedOn());
        long id = jdbc.sql("""
                        insert into sf_processing_batches (tenant_id, cost_object_id, code, kind, farm_id, main_item_id, started_on, expected_end, notes, created_by)
                        values (:t, :co, :code, :k, :f, :i, :s, :e, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("co", co).param("code", r.code().trim().toUpperCase(Locale.ROOT)).param("k", r.kind()).param("f", r.farmId())
                .param("i", r.mainItemId()).param("s", r.startedOn()).param("e", r.expectedEnd()).param("n", trim(r.notes())).param("u", ctx.userId())
                .query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_BATCH_STARTED", "PROCESSING_BATCH", id, Map.of("code", r.code().trim(), "kind", r.kind()));
        return batch(ctx, id);
    }

    @Transactional
    public Batch update(TenantContext ctx, long id, BatchRequest r) {
        Batch b = batch(ctx, id);
        checkBatch(ctx, id, r);
        boolean hasOutput = jdbc.sql("select exists (select 1 from sf_processing_outputs where tenant_id = :t and batch_id = :b)").param("t", ctx.tenantId())
                .param("b", id).query(Boolean.class).single();
        if (hasOutput && (!b.code().equalsIgnoreCase(r.code().trim()) || b.mainItemId() != r.mainItemId())) {
            throw ApiException.conflict("BATCH_HAS_OUTPUT", "The code and main product cannot change once output is recorded.");
        }
        LocalDate first = jdbc.sql("""
                        select min(d) from (select min(step_date) d from sf_processing_steps where tenant_id = :t and batch_id = :b
                          union all select min(output_date) from sf_processing_outputs where tenant_id = :t and batch_id = :b
                          union all select min(movement_date) from sf_stock_movements where tenant_id = :t and cost_object_id = :co) x""")
                .param("t", ctx.tenantId()).param("b", id).param("co", b.costObjectId()).query(LocalDate.class).optional().orElse(null);
        if (first != null && r.startedOn().isAfter(first)) throw ApiException.badRequest("BEFORE_BATCH_START", "Records exist before this start date.");
        jdbc.sql("""
                        update sf_processing_batches set code = :code, kind = :k, farm_id = :f, main_item_id = :i, started_on = :s, expected_end = :e, notes = :n
                        where id = :id and tenant_id = :t""")
                .param("code", r.code().trim().toUpperCase(Locale.ROOT)).param("k", r.kind()).param("f", r.farmId()).param("i", r.mainItemId())
                .param("s", r.startedOn()).param("e", r.expectedEnd()).param("n", trim(r.notes())).param("id", id).param("t", ctx.tenantId()).update();
        costObjects.renameProcessingBatch(ctx.tenantId(), b.costObjectId(), r.name().trim());
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_BATCH_SAVED", "PROCESSING_BATCH", id, Map.of("code", r.code().trim()));
        return batch(ctx, id);
    }

    @Transactional
    public Batch finish(TenantContext ctx, long id, FinishRequest r) {
        notFuture(r.date());
        Batch b = open(ctx, id);
        if (r.date().isBefore(b.startedOn())) throw ApiException.badRequest("BEFORE_BATCH_START", "The batch started after this date.");
        jdbc.sql("update sf_processing_batches set finished_on = :d where id = :id and tenant_id = :t").param("d", r.date()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_BATCH_FINISHED", "PROCESSING_BATCH", id, Map.of("date", r.date().toString()));
        return batch(ctx, id);
    }

    @Transactional
    public Batch reopen(TenantContext ctx, long id) {
        Batch b = batch(ctx, id);
        if (b.finishedOn() == null) return b;
        jdbc.sql("update sf_processing_batches set finished_on = null where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_BATCH_REOPENED", "PROCESSING_BATCH", id, Map.of("code", b.code()));
        return batch(ctx, id);
    }

    // ---- Steps (M4) ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Step> steps(TenantContext ctx, long id) {
        batch(ctx, id);
        return jdbc.sql("""
                        select id, step_date, kind, kg_in, kg_out, moisture_before, moisture_after, grade, notes
                        from sf_processing_steps where tenant_id = :t and batch_id = :b order by step_date desc, id desc""")
                .param("t", ctx.tenantId()).param("b", id)
                .query((rs, i) -> {
                    BigDecimal in = rs.getBigDecimal(4), out = rs.getBigDecimal(5);
                    return new Step(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), in, out, in != null && out != null ? in.subtract(out) : null,
                            rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), rs.getString(9));
                }).list();
    }

    @Transactional
    public Step addStep(TenantContext ctx, long id, StepRequest r) {
        notFuture(r.date());
        Batch b = open(ctx, id);
        if (r.date().isBefore(b.startedOn())) throw ApiException.badRequest("BEFORE_BATCH_START", "The batch started after this date.");
        if (r.kgIn() != null && r.kgOut() != null && r.kgOut().compareTo(r.kgIn()) > 0) throw ApiException.badRequest("STEP_GAINS_WEIGHT", "More came out of the step than went in.");
        long stepId = jdbc.sql("""
                        insert into sf_processing_steps (tenant_id, batch_id, step_date, kind, kg_in, kg_out, moisture_before, moisture_after, grade, notes, created_by)
                        values (:t, :b, :d, :k, :in, :out, :mb, :ma, :g, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("b", id).param("d", r.date()).param("k", r.kind()).param("in", r.kgIn()).param("out", r.kgOut())
                .param("mb", r.moistureBefore()).param("ma", r.moistureAfter()).param("g", trim(r.grade())).param("n", trim(r.notes())).param("u", ctx.userId())
                .query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_STEP_RECORDED", "PROCESSING_BATCH", id, Map.of("kind", r.kind(), "date", r.date().toString()));
        return steps(ctx, id).stream().filter(s -> s.id() == stepId).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteStep(TenantContext ctx, long stepId) {
        Long batchId = jdbc.sql("select batch_id from sf_processing_steps where id = :id and tenant_id = :t").param("id", stepId).param("t", ctx.tenantId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("PROCESSING_STEP_NOT_FOUND", "Step not found."));
        open(ctx, batchId);
        jdbc.sql("delete from sf_processing_steps where id = :id and tenant_id = :t").param("id", stepId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_STEP_DELETED", "PROCESSING_BATCH", batchId, Map.of("step", stepId));
    }

    // ---- Output (M5) -----------------------------------------------------------------------------------------------------------

    @Transactional
    public Output output(TenantContext ctx, long id, OutputRequest r, String key) {
        notFuture(r.date());
        Batch b = open(ctx, id);
        if (r.date().isBefore(b.startedOn())) throw ApiException.badRequest("BEFORE_BATCH_START", "The batch started after this date.");
        Long doc = null;
        String lotNo = null;
        BigDecimal kg;
        if ("WASTE".equals(r.role())) {
            if (trim(r.description()) == null) throw ApiException.badRequest("WASTE_DESCRIPTION_REQUIRED", "Say what the waste is, for example husk.");
            kg = r.quantity();
        } else {
            if (r.itemId() == null) throw ApiException.badRequest("ITEM_REQUIRED", "Choose the item that came out.");
            requireProduce(ctx, r.itemId());
            if ("MAIN".equals(r.role()) && r.itemId() != b.mainItemId()) {
                throw ApiException.badRequest("MAIN_ITEM_MISMATCH", "The main output is the batch's main product; record other items as byproducts.");
            }
            boolean tracksLots = jdbc.sql("select track_lots from sf_stock_items where tenant_id = :t and id = :i").param("t", ctx.tenantId()).param("i", r.itemId())
                    .query(Boolean.class).single();
            if (tracksLots) {
                // Lots are made from the batch code, numbered in the order the products came out (M5)
                int n = jdbc.sql("select count(*) from sf_processing_outputs where tenant_id = :t and batch_id = :b and role <> 'WASTE'")
                        .param("t", ctx.tenantId()).param("b", id).query(Integer.class).single();
                lotNo = b.code() + "-" + (n + 1);
            }
            doc = stock.produce(ctx, new StockDtos.ProduceRequest(r.date(), r.locationId(), b.costObjectId(), b.farmId(),
                    List.of(new StockDtos.ItemQuantity(r.itemId(), r.quantity(), trim(r.unit()), lotNo, null)), "Output, " + b.code()), key).document().summary().id();
            kg = jdbc.sql("""
                            select coalesce(sum(m.quantity * u.factor) filter (where u.dimension = 'MASS'), 0) from sf_stock_movements m
                            join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id join sf_units u on u.code = i.base_unit_code
                            where m.tenant_id = :t and m.document_id = :d""")
                    .param("t", ctx.tenantId()).param("d", doc).query(BigDecimal.class).single();
        }
        long outputId = jdbc.sql("""
                        insert into sf_processing_outputs (tenant_id, batch_id, output_date, role, item_id, description, quantity, kg, grade, lot_no, document_id, created_by)
                        values (:t, :b, :d, :r, :i, :desc, :q, :kg, :g, :lot, :doc, :u) returning id""")
                .param("t", ctx.tenantId()).param("b", id).param("d", r.date()).param("r", r.role()).param("i", "WASTE".equals(r.role()) ? null : r.itemId())
                .param("desc", trim(r.description())).param("q", r.quantity()).param("kg", kg).param("g", trim(r.grade())).param("lot", lotNo).param("doc", doc)
                .param("u", ctx.userId()).query(Long.class).single();
        if (Boolean.TRUE.equals(r.finish())) {
            jdbc.sql("update sf_processing_batches set finished_on = :d where id = :id and tenant_id = :t").param("d", r.date()).param("id", id).param("t", ctx.tenantId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_OUTPUT_RECORDED", "PROCESSING_BATCH", id,
                Map.of("role", r.role(), "quantity", r.quantity(), "finished", Boolean.TRUE.equals(r.finish())));
        return outputs(ctx, id).stream().filter(o -> o.id() == outputId).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Output> outputs(TenantContext ctx, long id) {
        batch(ctx, id);
        return jdbc.sql("""
                        select o.id, o.output_date, o.role, o.item_id, i.name, o.description, o.quantity, coalesce(i.base_unit_code, 'kg'), o.kg, o.grade, o.lot_no,
                          o.document_id, d.number, o.document_id is not null and d.status <> 'POSTED'
                        from sf_processing_outputs o
                        left join sf_stock_items i on i.id = o.item_id and i.tenant_id = o.tenant_id
                        left join sf_finance_documents d on d.id = o.document_id and d.tenant_id = o.tenant_id
                        where o.tenant_id = :t and o.batch_id = :b order by o.output_date, o.id""")
                .param("t", ctx.tenantId()).param("b", id)
                .query((rs, i) -> new Output(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6),
                        rs.getBigDecimal(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10), rs.getString(11), (Long) rs.getObject(12), rs.getString(13),
                        rs.getBoolean(14))).list();
    }

    /** Removes a waste line; product output is undone by reversing its produce record. */
    @Transactional
    public void deleteWaste(TenantContext ctx, long outputId) {
        Map<String, Object> o = jdbc.sql("select batch_id, role from sf_processing_outputs where id = :id and tenant_id = :t").param("id", outputId).param("t", ctx.tenantId())
                .query().listOfRows().stream().findFirst().orElseThrow(() -> ApiException.notFound("PROCESSING_OUTPUT_NOT_FOUND", "Output not found."));
        if (!"WASTE".equals(o.get("role"))) throw ApiException.conflict("OUTPUT_HAS_RECORD", "This output is in stock; reverse its record instead.");
        long batchId = ((Number) o.get("batch_id")).longValue();
        open(ctx, batchId);
        jdbc.sql("delete from sf_processing_outputs where id = :id and tenant_id = :t").param("id", outputId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "PROCESSING_OUTPUT_DELETED", "PROCESSING_BATCH", batchId, Map.of("output", outputId));
    }

    // ---- Figures (M6, M7) --------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BatchReport report(TenantContext ctx, long id, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "The date range is too long.");
        Batch b = batch(ctx, id);
        return new BatchReport(b, figures(ctx, b, from, to), b.startedOn().isAfter(to) ? null : figures(ctx, b, b.startedOn(), to));
    }

    /** Every batch with activity in a period (the processing batches report). */
    @Transactional(readOnly = true)
    public List<BatchReport> all(TenantContext ctx, LocalDate from, LocalDate to) {
        List<BatchReport> out = new ArrayList<>();
        for (Batch b : batches(ctx)) {
            if (b.startedOn().isAfter(to)) continue;
            boolean active = b.finishedOn() == null || !b.finishedOn().isBefore(from) || jdbc.sql("""
                            select exists (select 1 from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                              where l.tenant_id = :t and l.cost_object_id = :co and e.entry_date >= :from)""")
                    .param("t", ctx.tenantId()).param("co", b.costObjectId()).param("from", from).query(Boolean.class).single();
            if (active) out.add(report(ctx, b.id(), from, to));
        }
        return out;
    }

    Figures figures(TenantContext ctx, Batch b, LocalDate from, LocalDate to) {
        long t = ctx.tenantId();
        LocalDate end = b.finishedOn() != null && b.finishedOn().isBefore(to) ? b.finishedOn() : to;
        Integer days = b.startedOn().isAfter(to) ? null : (int) ChronoUnit.DAYS.between(b.startedOn(), end);
        // Input: produce and other stock used on the batch, in kg; with the value it carried (bought produce at cost)
        Map<String, Object> in = jdbc.sql("""
                        select coalesce(sum(-m.quantity * u.factor), 0) as kg, coalesce(sum(-m.value), 0) as value from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        join sf_units u on u.code = i.base_unit_code and u.dimension = 'MASS'
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'ISSUE' and m.movement_date between :from and :to""")
                .param("t", t).param("co", b.costObjectId()).param("from", from).param("to", to).query().singleRow();
        BigDecimal inputKg = ((BigDecimal) in.get("kg")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal transferredCost = jdbc.sql("""
                        select coalesce(sum(x.cost_moved), 0) from sf_internal_transfers x
                        join sf_finance_documents d on d.id = x.document_id and d.tenant_id = x.tenant_id
                        join sf_stock_items i on i.id = x.item_id and i.tenant_id = x.tenant_id join sf_units u on u.code = i.base_unit_code and u.dimension = 'MASS'
                        where x.tenant_id = :t and x.target_cost_object_id = :co and d.status = 'POSTED' and d.doc_date between :from and :to""")
                .param("t", t).param("co", b.costObjectId()).param("from", from).param("to", to).query(BigDecimal.class).single();
        BigDecimal rawCost = ((BigDecimal) in.get("value")).add(transferredCost);
        // Output that counts: waste, and products whose produce record is posted
        List<Map<String, Object>> outs = jdbc.sql("""
                        select o.role, o.item_id, coalesce(i.name, o.description) as name, sum(o.kg) as kg
                        from sf_processing_outputs o
                        left join sf_stock_items i on i.id = o.item_id and i.tenant_id = o.tenant_id
                        left join sf_finance_documents d on d.id = o.document_id and d.tenant_id = o.tenant_id
                        where o.tenant_id = :t and o.batch_id = :b and o.output_date between :from and :to and (o.document_id is null or d.status = 'POSTED')
                        group by o.role, o.item_id, coalesce(i.name, o.description) order by min(o.id)""")
                .param("t", t).param("b", b.id()).param("from", from).param("to", to).query().listOfRows();
        List<OutputYield> yields = new ArrayList<>();
        BigDecimal outputKg = BigDecimal.ZERO, mainKg = BigDecimal.ZERO;
        for (Map<String, Object> o : outs) {
            BigDecimal kg = o.get("kg") == null ? BigDecimal.ZERO : ((BigDecimal) o.get("kg")).setScale(3, RoundingMode.HALF_UP);
            outputKg = outputKg.add(kg);
            if ("MAIN".equals(o.get("role"))) mainKg = mainKg.add(kg);
            yields.add(new OutputYield((String) o.get("role"), o.get("item_id") == null ? null : ((Number) o.get("item_id")).longValue(), (String) o.get("name"), kg,
                    inputKg.signum() == 0 ? null : kg.multiply(HUNDRED).divide(inputKg, 1, RoundingMode.HALF_UP)));
        }
        BigDecimal loss = inputKg.signum() == 0 ? null : inputKg.subtract(outputKg);
        BigDecimal lossPercent = loss == null ? null : loss.multiply(HUNDRED).divide(inputKg, 1, RoundingMode.HALF_UP);

        BigDecimal costs = null, revenue = null, profit = null, byproductSales = null, mainCost = null, rawPerKg = null;
        if (ctx.has(Permission.FINANCE_READ)) {
            ReportService.IncomeStatement s = reports.incomeStatement(ctx, from, to, null, b.costObjectId(), "ACCRUAL");
            costs = s.operatingCostTotal().add(s.interest()).add(s.losses());
            revenue = s.operatingRevenueTotal().add(s.otherIncomeTotal());
            profit = revenue.subtract(costs);
            // The byproduct method (M6): byproduct sales assigned to the batch reduce the main product's cost
            byproductSales = jdbc.sql("""
                            select coalesce(sum(l.amount), 0) from sf_finance_document_lines l
                            join sf_finance_documents d on d.id = l.document_id and d.tenant_id = l.tenant_id
                            where l.tenant_id = :t and d.doc_type = 'SALE' and d.status = 'POSTED' and d.doc_date between :from and :to
                              and l.cost_object_id = :co
                              and l.item_id in (select item_id from sf_processing_outputs where tenant_id = :t and batch_id = :b and role = 'BYPRODUCT')""")
                    .param("t", t).param("from", from).param("to", to).param("co", b.costObjectId()).param("b", b.id()).query(BigDecimal.class).single();
            mainCost = mainKg.signum() == 0 ? null : costs.subtract(byproductSales).divide(mainKg, 2, RoundingMode.HALF_UP);
            rawPerKg = inputKg.signum() == 0 ? null : rawCost.divide(inputKg, 2, RoundingMode.HALF_UP);
        }
        return new Figures(from, to, days, inputKg, yields, outputKg.setScale(3, RoundingMode.HALF_UP), loss, lossPercent, mainKg, costs, revenue, profit,
                byproductSales, mainCost, rawPerKg);
    }

    /** For the dashboard: batches past their expected end and not finished (M14). */
    public int overdue(TenantContext ctx, LocalDate date) {
        return jdbc.sql("select count(*) from sf_processing_batches where tenant_id = :t and finished_on is null and expected_end < :d")
                .param("t", ctx.tenantId()).param("d", date).query(Integer.class).single();
    }
}
