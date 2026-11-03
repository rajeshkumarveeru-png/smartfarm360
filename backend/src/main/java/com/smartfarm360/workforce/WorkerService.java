package com.smartfarm360.workforce;

import com.smartfarm360.finance.FinanceDtos.PartyRequest;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.workforce.WorkforceDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Workers (each also a party, G1), dated rates, the daily muster roll and piece work (workforce design, sections 3–4),
 * worker statements and labour reports. Wages and advances are posted by {@link PayrollService}.
 */
@Service
public class WorkerService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final JdbcClient jdbc;
    private final MasterDataService master;
    private final AuditService audit;

    public WorkerService(JdbcClient jdbc, MasterDataService master, AuditService audit) {
        this.jdbc = jdbc;
        this.master = master;
        this.audit = audit;
    }

    // ---- Workers ---------------------------------------------------------------------------------------------------

    record WorkerRow(long id, long partyId, String name, String payType, LocalDate joinedOn, LocalDate leftOn) {
        boolean employedOn(LocalDate d) { return !d.isBefore(joinedOn) && (leftOn == null || !d.isAfter(leftOn)); }
    }

    WorkerRow workerRow(long tenantId, long id) {
        return jdbc.sql("""
                        select w.id, w.party_id, p.name, w.pay_type, w.joined_on, w.left_on from sf_workers w
                        join sf_parties p on p.id = w.party_id and p.tenant_id = w.tenant_id where w.id = :id and w.tenant_id = :t""")
                .param("id", id).param("t", tenantId)
                .query((rs, i) -> new WorkerRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class)))
                .optional().orElseThrow(() -> ApiException.notFound("WORKER_NOT_FOUND", "Worker not found."));
    }

    List<WorkerRow> workerRows(long tenantId) {
        return jdbc.sql("""
                        select w.id, w.party_id, p.name, w.pay_type, w.joined_on, w.left_on from sf_workers w
                        join sf_parties p on p.id = w.party_id and p.tenant_id = w.tenant_id where w.tenant_id = :t order by lower(p.name), w.id""")
                .param("t", tenantId)
                .query((rs, i) -> new WorkerRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<Worker> workers(TenantContext ctx, boolean includeLeft) {
        return workerRows(ctx.tenantId()).stream().filter(w -> includeLeft || w.leftOn() == null || !w.leftOn().isBefore(LocalDate.now()))
                .map(w -> worker(ctx.tenantId(), w)).toList();
    }

    @Transactional(readOnly = true)
    public Worker worker(TenantContext ctx, long id) { return worker(ctx.tenantId(), workerRow(ctx.tenantId(), id)); }

    private Worker worker(long tenantId, WorkerRow w) {
        Map<String, Object> p = jdbc.sql("""
                        select p.phone, p.village, wk.usual_work, wk.notes from sf_parties p join sf_workers wk on wk.party_id = p.id and wk.tenant_id = p.tenant_id
                        where wk.id = :id and wk.tenant_id = :t""").param("id", w.id()).param("t", tenantId).query().singleRow();
        List<Rate> rates = rates(tenantId, w.id());
        Rate current = rateOn(rates, LocalDate.now());
        boolean active = w.leftOn() == null || !w.leftOn().isBefore(LocalDate.now());
        return new Worker(w.id(), w.partyId(), w.name(), (String) p.get("phone"), (String) p.get("village"), w.payType(),
                current == null ? null : current.rate(), current == null ? null : current.pieceUnit(), w.joinedOn(), w.leftOn(), active,
                (String) p.get("usual_work"), (String) p.get("notes"), owed(tenantId, w.partyId()), advance(tenantId, w.partyId()), rates);
    }

    @Transactional
    public Worker createWorker(TenantContext ctx, WorkerRequest r) {
        if ("PIECE".equals(r.payType()) && (r.pieceUnit() == null || r.pieceUnit().isBlank())) {
            throw ApiException.badRequest("PIECE_UNIT_REQUIRED", "Enter what piece work is paid by (kg, tree, bag…).");
        }
        long party = master.createParty(ctx, new PartyRequest(r.name(), r.phone(), r.village(), null, false, false, false)).id();
        jdbc.sql("update sf_parties set is_worker = true where id = :id and tenant_id = :t").param("id", party).param("t", ctx.tenantId()).update();
        long id = jdbc.sql("""
                        insert into sf_workers (tenant_id, party_id, pay_type, joined_on, usual_work, notes) values (:t, :p, :type, :j, :u, :n) returning id""")
                .param("t", ctx.tenantId()).param("p", party).param("type", r.payType()).param("j", r.joinedOn()).param("u", trim(r.usualWork()))
                .param("n", trim(r.notes())).query(Long.class).single();
        insertRate(ctx.tenantId(), id, r.joinedOn(), r.rate(), trim(r.pieceUnit()));
        audit.record(ctx.tenantId(), ctx.userId(), "WORKER_CREATED", "WORKER", id, Map.of("name", r.name().trim(), "payType", r.payType()));
        return worker(ctx, id);
    }

    @Transactional
    public Worker updateWorker(TenantContext ctx, long id, WorkerPatch p) {
        WorkerRow w = workerRow(ctx.tenantId(), id);
        if (p.leftOn() != null) {
            if (p.leftOn().isBefore(w.joinedOn())) throw ApiException.badRequest("INVALID_LEAVING_DATE", "The leaving date is before the joining date.");
            boolean later = jdbc.sql("""
                            select exists (select 1 from sf_attendance where tenant_id = :t and worker_id = :w and work_date > :d and status <> 'ABSENT')
                                or exists (select 1 from sf_piece_work where tenant_id = :t and worker_id = :w and work_date > :d)""")
                    .param("t", ctx.tenantId()).param("w", id).param("d", p.leftOn()).query(Boolean.class).single();
            if (later) throw ApiException.conflict("WORK_AFTER_LEAVING", "There is attendance or piece work after this date.");
            if (owed(ctx.tenantId(), w.partyId()).signum() != 0 || advance(ctx.tenantId(), w.partyId()).signum() != 0) {
                throw ApiException.conflict("WORKER_HAS_BALANCE", "Settle the wages owed and the advance before the worker leaves.");
            }
        }
        jdbc.sql("""
                        update sf_parties set name = coalesce(:name, name), phone = coalesce(:phone, phone), village = coalesce(:village, village),
                          active = case when :left then false when :rejoin then true else active end
                        where id = :p and tenant_id = :t""")
                .param("name", p.name() == null ? null : p.name().trim()).param("phone", trim(p.phone())).param("village", trim(p.village()))
                .param("left", p.leftOn() != null).param("rejoin", Boolean.TRUE.equals(p.rejoin())).param("p", w.partyId()).param("t", ctx.tenantId()).update();
        jdbc.sql("""
                        update sf_workers set usual_work = coalesce(:u, usual_work), notes = coalesce(:n, notes),
                          left_on = case when :rejoin then null else coalesce(:left, left_on) end where id = :id and tenant_id = :t""")
                .param("u", trim(p.usualWork())).param("n", trim(p.notes())).param("rejoin", Boolean.TRUE.equals(p.rejoin()))
                .param("left", p.leftOn()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "WORKER_UPDATED", "WORKER", id,
                p.leftOn() != null ? Map.of("leftOn", p.leftOn().toString()) : Map.of());
        return worker(ctx, id);
    }

    /** A new rate from a date; refused for days already paid by a posted pay run. */
    @Transactional
    public Worker addRate(TenantContext ctx, long id, RateRequest r) {
        WorkerRow w = workerRow(ctx.tenantId(), id);
        if (r.effectiveFrom().isBefore(w.joinedOn())) throw ApiException.badRequest("INVALID_RATE_DATE", "A rate cannot start before the worker joined.");
        if (paidThrough(ctx.tenantId(), id).filter(d -> !d.isBefore(r.effectiveFrom())).isPresent()) {
            throw ApiException.conflict("RATE_IN_PAID_PERIOD", "Wages up to that date are already paid at the old rate.");
        }
        jdbc.sql("delete from sf_worker_rates where tenant_id = :t and worker_id = :w and effective_from = :d")
                .param("t", ctx.tenantId()).param("w", id).param("d", r.effectiveFrom()).update();
        insertRate(ctx.tenantId(), id, r.effectiveFrom(), r.rate(), trim(r.pieceUnit()));
        audit.record(ctx.tenantId(), ctx.userId(), "WORKER_RATE_CHANGED", "WORKER", id, Map.of("from", r.effectiveFrom().toString(), "rate", r.rate()));
        return worker(ctx, id);
    }

    private void insertRate(long tenantId, long workerId, LocalDate from, BigDecimal rate, String unit) {
        jdbc.sql("insert into sf_worker_rates (tenant_id, worker_id, effective_from, rate, piece_unit) values (:t, :w, :d, :r, :u)")
                .param("t", tenantId).param("w", workerId).param("d", from).param("r", rate.setScale(2, RoundingMode.HALF_UP)).param("u", unit).update();
    }

    List<Rate> rates(long tenantId, long workerId) {
        return jdbc.sql("select effective_from, rate, piece_unit from sf_worker_rates where tenant_id = :t and worker_id = :w order by effective_from")
                .param("t", tenantId).param("w", workerId)
                .query((rs, i) -> new Rate(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getString(3))).list();
    }

    /** The rate on a day: the latest that started on or before it. */
    static Rate rateOn(List<Rate> rates, LocalDate day) {
        Rate best = null;
        for (Rate r : rates) if (!r.effectiveFrom().isAfter(day)) best = r;
        return best;
    }

    BigDecimal owed(long tenantId, long partyId) {
        return jdbc.sql("select coalesce(sum(credit - debit), 0) from sf_journal_lines where tenant_id = :t and party_id = :p and account_code = '2100'")
                .param("t", tenantId).param("p", partyId).query(BigDecimal.class).single().setScale(2);
    }

    BigDecimal advance(long tenantId, long partyId) {
        return jdbc.sql("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = :t and party_id = :p and account_code = '1150'")
                .param("t", tenantId).param("p", partyId).query(BigDecimal.class).single().setScale(2);
    }

    /** The last day covered by a posted pay run for the worker, if any. */
    Optional<LocalDate> paidThrough(long tenantId, long workerId) {
        return jdbc.sql("""
                        select max(r.period_to) from sf_pay_run_workers r join sf_finance_documents d on d.id = r.document_id
                        where r.tenant_id = :t and r.worker_id = :w and d.status = 'POSTED'""")
                .param("t", tenantId).param("w", workerId).query(LocalDate.class).optional();
    }

    boolean locked(long tenantId, long workerId, LocalDate day) {
        return jdbc.sql("""
                        select exists (select 1 from sf_pay_run_workers r join sf_finance_documents d on d.id = r.document_id
                          where r.tenant_id = :t and r.worker_id = :w and d.status = 'POSTED' and :d between r.period_from and r.period_to)""")
                .param("t", tenantId).param("w", workerId).param("d", day).query(Boolean.class).single();
    }

    void requireCostObject(long tenantId, Long costObjectId) {
        if (costObjectId != null) master.requireCostObject(tenantId, costObjectId);
    }

    // ---- Attendance (G3) ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Muster muster(TenantContext ctx, LocalDate date) {
        Map<Long, Map<String, Object>> entries = new HashMap<>();
        jdbc.sql("""
                        select a.worker_id, a.status, a.hours, a.cost_object_id, co.name as co_name, a.notes from sf_attendance a
                        left join sf_cost_objects co on co.id = a.cost_object_id and co.tenant_id = a.tenant_id
                        where a.tenant_id = :t and a.work_date = :d""")
                .param("t", ctx.tenantId()).param("d", date).query().listOfRows()
                .forEach(r -> entries.put(((Number) r.get("worker_id")).longValue(), r));
        // G7: a registered worker's day that is also on a posted labour bill would be paid twice
        Set<Long> billed = new HashSet<>(jdbc.sql("""
                        select distinct d.party_id from sf_finance_documents d join sf_finance_document_lines l on l.document_id = d.id and l.tenant_id = d.tenant_id
                        where d.tenant_id = :t and d.doc_type = 'BILL' and d.status = 'POSTED' and d.doc_date = :d and d.party_id is not null
                          and l.account_code = '5100'""")
                .param("t", ctx.tenantId()).param("d", date).query(Long.class).list());
        List<AttendanceRow> rows = new ArrayList<>();
        for (WorkerRow w : workerRows(ctx.tenantId())) {
            Map<String, Object> e = entries.get(w.id());
            if (!w.employedOn(date) && e == null) continue;
            rows.add(new AttendanceRow(w.id(), w.name(), w.payType(), e == null ? null : (String) e.get("status"),
                    e == null ? null : (BigDecimal) e.get("hours"), e == null || e.get("cost_object_id") == null ? null : ((Number) e.get("cost_object_id")).longValue(),
                    e == null ? null : (String) e.get("co_name"), e == null ? null : (String) e.get("notes"), locked(ctx.tenantId(), w.id(), date),
                    billed.contains(w.partyId())));
        }
        return new Muster(date, rows, musterVersion(ctx.tenantId(), date));
    }

    /** The version of a day's attendance: a hash of what was recorded (mobile offline design, N7). */
    private String musterVersion(long tenantId, LocalDate date) {
        List<Object> parts = new ArrayList<>();
        jdbc.sql("select worker_id, status, hours, cost_object_id, notes from sf_attendance where tenant_id = :t and work_date = :d order by worker_id")
                .param("t", tenantId).param("d", date).query().listOfRows()
                .forEach(r -> { parts.add(r.get("worker_id")); parts.add(r.get("status")); parts.add(r.get("hours")); parts.add(r.get("cost_object_id")); parts.add(r.get("notes")); });
        return parts.isEmpty() ? com.smartfarm360.platform.web.Versions.NONE : com.smartfarm360.platform.web.Versions.token(parts.toArray());
    }

    /** Saves a day's muster roll: one entry per worker; an entry without a status clears the day. */
    @Transactional
    public Muster saveMuster(TenantContext ctx, LocalDate date, MusterRequest r) {
        if (date.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        // One save of a day at a time, so the version check and the write see the same day (N7)
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "attendance:" + ctx.tenantId() + ":" + date).query().listOfRows();
        com.smartfarm360.platform.web.Versions.requireCurrent(r.baseVersion(), musterVersion(ctx.tenantId(), date));
        Set<Long> seen = new HashSet<>();
        for (AttendanceEntry e : r.entries()) {
            if (!seen.add(e.workerId())) throw ApiException.badRequest("DUPLICATE_LINE", "Each worker can appear only once per day.");
            WorkerRow w = workerRow(ctx.tenantId(), e.workerId());
            if (locked(ctx.tenantId(), w.id(), date)) {
                throw ApiException.conflict("ATTENDANCE_LOCKED", w.name() + " is already paid for this day. Reverse that pay run to change it.");
            }
            if (e.status() == null) {
                jdbc.sql("delete from sf_attendance where tenant_id = :t and worker_id = :w and work_date = :d")
                        .param("t", ctx.tenantId()).param("w", w.id()).param("d", date).update();
                continue;
            }
            if (!w.employedOn(date)) throw ApiException.badRequest("NOT_EMPLOYED", w.name() + " was not working here on that day.");
            requireCostObject(ctx.tenantId(), e.costObjectId());
            boolean working = "PRESENT".equals(e.status()) || "HALF_DAY".equals(e.status());
            jdbc.sql("""
                            insert into sf_attendance (tenant_id, worker_id, work_date, status, hours, cost_object_id, notes, updated_by)
                            values (:t, :w, :d, :s, :h, :co, :n, :u)
                            on conflict (tenant_id, worker_id, work_date) do update set status = excluded.status, hours = excluded.hours,
                              cost_object_id = excluded.cost_object_id, notes = excluded.notes, updated_by = excluded.updated_by, updated_at = now()""")
                    .param("t", ctx.tenantId()).param("w", w.id()).param("d", date).param("s", e.status()).param("h", working ? e.hours() : null)
                    .param("co", working ? e.costObjectId() : null).param("n", trim(e.notes())).param("u", ctx.userId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "ATTENDANCE_SAVED", "ATTENDANCE", date.toString(), Map.of("workers", r.entries().size()));
        return muster(ctx, date);
    }

    @Transactional(readOnly = true)
    public MonthSheet monthSheet(TenantContext ctx, YearMonth month) {
        List<MonthRow> rows = jdbc.sql("""
                        select w.id, p.name, w.pay_type,
                               count(*) filter (where a.status = 'PRESENT'), count(*) filter (where a.status = 'HALF_DAY'),
                               count(*) filter (where a.status = 'ABSENT'), count(*) filter (where a.status = 'PAID_LEAVE'),
                               count(*) filter (where a.status = 'UNPAID_LEAVE')
                        from sf_workers w join sf_parties p on p.id = w.party_id and p.tenant_id = w.tenant_id
                        left join sf_attendance a on a.worker_id = w.id and a.tenant_id = w.tenant_id and a.work_date between :from and :to
                        where w.tenant_id = :t and w.joined_on <= :to and (w.left_on is null or w.left_on >= :from)
                        group by w.id, p.name, w.pay_type order by lower(p.name)""")
                .param("t", ctx.tenantId()).param("from", month.atDay(1)).param("to", month.atEndOfMonth())
                .query((rs, i) -> {
                    BigDecimal present = BigDecimal.valueOf(rs.getLong(4)), half = BigDecimal.valueOf(rs.getLong(5));
                    return new MonthRow(rs.getLong(1), rs.getString(2), rs.getString(3), present, half, BigDecimal.valueOf(rs.getLong(6)),
                            BigDecimal.valueOf(rs.getLong(7)), BigDecimal.valueOf(rs.getLong(8)), present.add(half.multiply(new BigDecimal("0.5"))));
                }).list();
        return new MonthSheet(month.toString(), rows);
    }

    // ---- Piece work ------------------------------------------------------------------------------------------------

    @Transactional
    public PieceWork addPieceWork(TenantContext ctx, PieceWorkRequest r) {
        WorkerRow w = workerRow(ctx.tenantId(), r.workerId());
        if (r.workDate().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        if (!w.employedOn(r.workDate())) throw ApiException.badRequest("NOT_EMPLOYED", w.name() + " was not working here on that day.");
        if (locked(ctx.tenantId(), w.id(), r.workDate())) throw ApiException.conflict("ATTENDANCE_LOCKED", w.name() + " is already paid for this day.");
        requireCostObject(ctx.tenantId(), r.costObjectId());
        Rate rate = rateOn(rates(ctx.tenantId(), w.id()), r.workDate());
        BigDecimal unitRate = r.rate() != null ? r.rate() : rate == null ? null : rate.rate();
        String unit = trim(r.unit()) != null ? trim(r.unit()) : rate == null ? null : rate.pieceUnit();
        if (unitRate == null || unit == null) throw ApiException.badRequest("PIECE_RATE_REQUIRED", "Enter the rate and unit for this piece work.");
        long id = jdbc.sql("""
                        insert into sf_piece_work (tenant_id, worker_id, work_date, quantity, unit, rate, cost_object_id, notes, created_by)
                        values (:t, :w, :d, :q, :u, :r, :co, :n, :by) returning id""")
                .param("t", ctx.tenantId()).param("w", w.id()).param("d", r.workDate()).param("q", r.quantity()).param("u", unit).param("r", unitRate)
                .param("co", r.costObjectId()).param("n", trim(r.notes())).param("by", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "PIECE_WORK_SAVED", "WORKER", w.id(), Map.of("date", r.workDate().toString(), "quantity", r.quantity()));
        return pieceWork(ctx, null, null, null).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void deletePieceWork(TenantContext ctx, long id) {
        Map<String, Object> row = jdbc.sql("select worker_id, work_date from sf_piece_work where id = :id and tenant_id = :t")
                .param("id", id).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PIECE_WORK_NOT_FOUND", "Piece work not found."));
        long worker = ((Number) row.get("worker_id")).longValue();
        LocalDate date = ((java.sql.Date) row.get("work_date")).toLocalDate();
        if (locked(ctx.tenantId(), worker, date)) throw ApiException.conflict("ATTENDANCE_LOCKED", "This work is already paid. Reverse that pay run to change it.");
        jdbc.sql("delete from sf_piece_work where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "PIECE_WORK_DELETED", "WORKER", worker, Map.of("date", date.toString()));
    }

    @Transactional(readOnly = true)
    public List<PieceWork> pieceWork(TenantContext ctx, Long workerId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select pw.id, pw.worker_id, p.name, pw.work_date, pw.quantity, pw.unit, pw.rate, pw.cost_object_id, co.name, pw.notes
                        from sf_piece_work pw join sf_workers w on w.id = pw.worker_id and w.tenant_id = pw.tenant_id
                        join sf_parties p on p.id = w.party_id and p.tenant_id = w.tenant_id
                        left join sf_cost_objects co on co.id = pw.cost_object_id and co.tenant_id = pw.tenant_id
                        where pw.tenant_id = :t and (cast(:w as bigint) is null or pw.worker_id = :w)
                          and (cast(:from as date) is null or pw.work_date >= :from) and (cast(:to as date) is null or pw.work_date <= :to)
                        order by pw.work_date desc, pw.id desc limit 500""")
                .param("t", ctx.tenantId()).param("w", workerId).param("from", from).param("to", to)
                .query((rs, i) -> {
                    BigDecimal amount = rs.getBigDecimal(5).multiply(rs.getBigDecimal(7)).setScale(2, RoundingMode.HALF_UP);
                    return new PieceWork(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getString(6),
                            rs.getBigDecimal(7), amount, (Long) rs.getObject(8), rs.getString(9), rs.getString(10),
                            locked(ctx.tenantId(), rs.getLong(2), rs.getObject(4, LocalDate.class)));
                }).list();
    }

    // ---- Statements and reports -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Statement statement(TenantContext ctx, long workerId, LocalDate from, LocalDate to) {
        WorkerRow w = workerRow(ctx.tenantId(), workerId);
        BigDecimal opening = jdbc.sql("""
                        select coalesce(sum(case when l.account_code = '2100' then l.credit - l.debit else l.credit - l.debit end), 0)
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and l.party_id = :p and l.account_code in ('2100', '1150') and e.entry_date < :from""")
                .param("t", ctx.tenantId()).param("p", w.partyId()).param("from", from).query(BigDecimal.class).single().setScale(2);
        List<StatementLine> lines = new ArrayList<>();
        BigDecimal balance = opening;
        for (Map<String, Object> r : jdbc.sql("""
                        select d.id, d.doc_type, d.number, e.entry_date, e.kind,
                               coalesce(sum(case when l.account_code = '2100' then l.credit end), 0) as wages,
                               coalesce(sum(case when l.account_code = '2100' then l.debit end), 0) as paid,
                               coalesce(sum(case when l.account_code = '1150' then l.debit end), 0) as given,
                               coalesce(sum(case when l.account_code = '1150' then l.credit end), 0) as recovered
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id join sf_finance_documents d on d.id = e.document_id
                        where l.tenant_id = :t and l.party_id = :p and l.account_code in ('2100', '1150') and e.entry_date between :from and :to
                        group by d.id, d.doc_type, d.number, e.entry_date, e.kind, e.id order by e.entry_date, e.id""")
                .param("t", ctx.tenantId()).param("p", w.partyId()).param("from", from).param("to", to).query().listOfRows()) {
            BigDecimal wages = (BigDecimal) r.get("wages"), paid = (BigDecimal) r.get("paid"), given = (BigDecimal) r.get("given"),
                    recovered = (BigDecimal) r.get("recovered");
            balance = balance.add(wages).subtract(paid).subtract(given).add(recovered);
            lines.add(new StatementLine(((Number) r.get("id")).longValue(), (String) r.get("doc_type"), (String) r.get("number"),
                    ((java.sql.Date) r.get("entry_date")).toLocalDate(), wages, paid, given, recovered, balance, "REVERSAL".equals(r.get("kind"))));
        }
        return new Statement(w.id(), w.name(), from, to, opening, lines, balance, owed(ctx.tenantId(), w.partyId()), advance(ctx.tenantId(), w.partyId()));
    }

    /** Worker-days (from attendance and piece work dates) and labour cost from pay runs, by crop cycle or overhead. */
    @Transactional(readOnly = true)
    public LabourByActivity labourByActivity(TenantContext ctx, LocalDate from, LocalDate to) {
        Map<Long, LabourRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        select co.id, co.name, co.kind,
                               coalesce((select sum(case a.status when 'PRESENT' then 1 when 'HALF_DAY' then 0.5 else 0 end) from sf_attendance a
                                         where a.tenant_id = co.tenant_id and a.work_date between :from and :to
                                           and coalesce(a.cost_object_id, (select o.id from sf_cost_objects o where o.tenant_id = co.tenant_id and o.kind = 'OVERHEAD')) = co.id), 0),
                               coalesce((select sum(l.debit - l.credit) from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                                         join sf_finance_documents d on d.id = e.document_id
                                         where l.tenant_id = co.tenant_id and l.cost_object_id = co.id and l.account_code = '5100' and d.doc_type = 'PAY_RUN'
                                           and e.entry_date between :from and :to), 0)
                        from sf_cost_objects co where co.tenant_id = :t order by co.kind desc, co.name""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to)
                .query((rs, i) -> new LabourRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5).setScale(2)))
                .list().stream().filter(r -> r.days().signum() != 0 || r.cost().signum() != 0).forEach(r -> rows.put(r.costObjectId(), r));
        BigDecimal total = rows.values().stream().map(LabourRow::cost).reduce(ZERO, BigDecimal::add);
        return new LabourByActivity(from, to, new ArrayList<>(rows.values()), total);
    }

    @Transactional(readOnly = true)
    public Reconciliation reconciliation(TenantContext ctx) {
        BigDecimal owed = ZERO, adv = ZERO;
        for (WorkerRow w : workerRows(ctx.tenantId())) {
            owed = owed.add(owed(ctx.tenantId(), w.partyId()));
            adv = adv.add(advance(ctx.tenantId(), w.partyId()));
        }
        BigDecimal ledgerOwed = jdbc.sql("select coalesce(sum(credit - debit), 0) from sf_journal_lines where tenant_id = :t and account_code = '2100'")
                .param("t", ctx.tenantId()).query(BigDecimal.class).single().setScale(2);
        BigDecimal ledgerAdv = jdbc.sql("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = :t and account_code = '1150'")
                .param("t", ctx.tenantId()).query(BigDecimal.class).single().setScale(2);
        return new Reconciliation(owed, ledgerOwed, adv, ledgerAdv);
    }

    /** Days, wages, payments and advances by worker for a period, and labour by crop (dashboard and reports design, H5). */
    @Transactional(readOnly = true)
    public WagesReport wages(TenantContext ctx, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        boolean amounts = ctx.has(Permission.PAYROLL_MANAGE) || ctx.has(Permission.FINANCE_READ);
        List<WageRow> rows = jdbc.sql("""
                        select w.id, p.name, w.pay_type,
                               coalesce((select sum(case a.status when 'PRESENT' then 1 when 'HALF_DAY' then 0.5 else 0 end) from sf_attendance a
                                         where a.tenant_id = w.tenant_id and a.worker_id = w.id and a.work_date between :from and :to), 0) as days,
                               coalesce(sum(case when l.account_code = '2100' and d.doc_type = 'PAY_RUN' and e.entry_date >= :from then l.credit - l.debit end), 0) as wages,
                               coalesce(sum(case when l.account_code = '2100' and d.doc_type <> 'PAY_RUN' and e.entry_date >= :from then l.debit - l.credit end), 0) as paid,
                               coalesce(sum(case when l.account_code = '1150' and d.doc_type = 'ADVANCE' and e.entry_date >= :from then l.debit - l.credit end), 0) as given,
                               coalesce(sum(case when l.account_code = '1150' and d.doc_type <> 'ADVANCE' and e.entry_date >= :from then l.credit - l.debit end), 0) as recovered,
                               coalesce(sum(case when l.account_code = '2100' then l.credit - l.debit end), 0) as owed,
                               coalesce(sum(case when l.account_code = '1150' then l.debit - l.credit end), 0) as advance
                        from sf_workers w join sf_parties p on p.id = w.party_id and p.tenant_id = w.tenant_id
                        left join (sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id and e.entry_date <= :to
                                   join sf_finance_documents d on d.id = e.document_id)
                               on l.party_id = w.party_id and l.tenant_id = w.tenant_id and l.account_code in ('2100', '1150')
                        where w.tenant_id = :t and w.joined_on <= :to
                        group by w.id, w.tenant_id, p.name, w.pay_type order by lower(p.name)""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to)
                .query((rs, i) -> new WageRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5).setScale(2), rs.getBigDecimal(6).setScale(2), rs.getBigDecimal(7).setScale(2), rs.getBigDecimal(8).setScale(2),
                        rs.getBigDecimal(9).setScale(2), rs.getBigDecimal(10).setScale(2)))
                .list().stream()
                .filter(r -> r.days().signum() != 0 || r.wages().signum() != 0 || r.paid().signum() != 0 || r.advanceGiven().signum() != 0
                        || r.advanceRecovered().signum() != 0 || r.owedAtEnd().signum() != 0 || r.advanceAtEnd().signum() != 0)
                .toList();
        WageRow total = new WageRow(0, null, null, rows.stream().map(WageRow::days).reduce(BigDecimal.ZERO, BigDecimal::add),
                sum(rows, WageRow::wages), sum(rows, WageRow::paid), sum(rows, WageRow::advanceGiven), sum(rows, WageRow::advanceRecovered),
                sum(rows, WageRow::owedAtEnd), sum(rows, WageRow::advanceAtEnd));
        LabourByActivity byCrop = labourByActivity(ctx, from, to);
        if (!amounts) {
            rows = rows.stream().map(WorkerService::withoutAmounts).toList();
            total = withoutAmounts(total);
            byCrop = new LabourByActivity(from, to, byCrop.rows().stream().map(r -> new LabourRow(r.costObjectId(), r.name(), r.kind(), r.days(), null)).toList(), null);
        }
        return new WagesReport(from, to, true, amounts, rows, total, byCrop);
    }

    private static WageRow withoutAmounts(WageRow r) {
        return new WageRow(r.workerId(), r.name(), r.payType(), r.days(), null, null, null, null, null, null);
    }

    private static BigDecimal sum(List<WageRow> rows, java.util.function.Function<WageRow, BigDecimal> f) {
        return rows.stream().map(f).reduce(ZERO, BigDecimal::add);
    }

    static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
