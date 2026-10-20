package com.smartfarm360.workforce;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.DocumentService.ModuleLine;
import com.smartfarm360.finance.DocumentService.ModuleRecord;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.JournalPart;
import com.smartfarm360.finance.LedgerAccount;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.workforce.WorkerService.WorkerRow;
import com.smartfarm360.workforce.WorkforceDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Advances (W1, W4), pay runs (W2) and wage payments (W3) of the workforce design. Each is a finance record posted
 * through {@link DocumentService#recordModule}; labour cost follows attendance to the crop cycle worked on (G3).
 */
@Service
public class PayrollService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private final JdbcClient jdbc;
    private final WorkerService workers;
    private final DocumentService documents;
    private final CostObjectService costObjects;

    public PayrollService(JdbcClient jdbc, WorkerService workers, DocumentService documents, CostObjectService costObjects) {
        this.jdbc = jdbc;
        this.workers = workers;
        this.documents = documents;
        this.costObjects = costObjects;
    }

    // ---- Advances ----------------------------------------------------------------------------------------------------

    @Transactional
    public Document advance(TenantContext ctx, AdvanceRequest r, String key) {
        WorkerRow w = workers.workerRow(ctx.tenantId(), r.workerId());
        if (!w.employedOn(r.date())) throw ApiException.badRequest("NOT_EMPLOYED", w.name() + " was not working here on that day.");
        BigDecimal amount = money(r.amount());
        return documents.recordModule(ctx, new ModuleRecord(DocType.ADVANCE, r.date(), w.partyId(), null, r.financialAccountId(), null, null, null,
                amount, r.notes(), null, List.of(new ModuleLine(null, LedgerAccount.WORKER_ADVANCES.code(), "DEBIT", "Advance · " + w.name(), null, null,
                amount, null, null, w.partyId(), null))), key, r, (id, lines) -> List.of(
                JournalPart.debit(LedgerAccount.WORKER_ADVANCES.code(), amount, null, w.partyId(), null),
                JournalPart.cash(r.financialAccountId(), BigDecimal.ZERO, amount)));
    }

    /** An advance paid back in cash (W4), e.g. by a worker who is leaving. */
    @Transactional
    public Document advanceRepayment(TenantContext ctx, AdvanceRequest r, String key) {
        WorkerRow w = workers.workerRow(ctx.tenantId(), r.workerId());
        lockParty(ctx.tenantId(), w.partyId());
        BigDecimal amount = money(r.amount());
        if (amount.compareTo(workers.advance(ctx.tenantId(), w.partyId())) > 0) {
            throw ApiException.badRequest("MORE_THAN_ADVANCE", "This is more than the advance still outstanding.");
        }
        return documents.recordModule(ctx, new ModuleRecord(DocType.ADVANCE_REPAYMENT, r.date(), w.partyId(), null, r.financialAccountId(), null, null,
                null, amount, r.notes(), null, List.of(new ModuleLine(null, LedgerAccount.WORKER_ADVANCES.code(), "CREDIT", "Advance repaid · " + w.name(),
                null, null, amount, null, null, w.partyId(), null))), key, r, (id, lines) -> List.of(
                JournalPart.cash(r.financialAccountId(), amount, BigDecimal.ZERO),
                JournalPart.credit(LedgerAccount.WORKER_ADVANCES.code(), amount, null, w.partyId(), null)));
    }

    // ---- Pay runs (W2) --------------------------------------------------------------------------------------------------

    /** Working total for one worker: money per cost object (null = overhead) and the days behind it. */
    private static final class Tally {
        final Map<Long, BigDecimal> amount = new LinkedHashMap<>();
        final Map<Long, BigDecimal> days = new LinkedHashMap<>();
        BigDecimal timePay = BigDecimal.ZERO, piecePay = BigDecimal.ZERO, extraPay = BigDecimal.ZERO, totalDays = BigDecimal.ZERO;
        final List<String> notes = new ArrayList<>();

        void add(Long costObject, BigDecimal value, BigDecimal workDays) {
            amount.merge(costObject, value, BigDecimal::add);
            if (workDays.signum() != 0) days.merge(costObject, workDays, BigDecimal::add);
        }
    }

    @Transactional(readOnly = true)
    public PayRunPreview preview(TenantContext ctx, PayRunRequest r) {
        return compute(ctx, r, false);
    }

    private PayRunPreview compute(TenantContext ctx, PayRunRequest r, boolean strict) {
        if (r.to().isBefore(r.from()) || ChronoUnit.DAYS.between(r.from(), r.to()) > 92) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a pay period of at most three months.");
        }
        long t = ctx.tenantId();
        List<YearMonth> wholeMonths = wholeMonths(r.from(), r.to());
        Set<Long> chosen = r.workerIds() == null || r.workerIds().isEmpty() ? null : new HashSet<>(r.workerIds());
        Map<Long, List<Extra>> extras = new HashMap<>();
        if (r.extras() != null) for (Extra e : r.extras()) extras.computeIfAbsent(e.workerId(), k -> new ArrayList<>()).add(e);
        Map<Long, String> costObjectNames = new HashMap<>();
        jdbc.sql("select id, name from sf_cost_objects where tenant_id = :t").param("t", t).query().listOfRows()
                .forEach(x -> costObjectNames.put(((Number) x.get("id")).longValue(), (String) x.get("name")));

        List<PayRunWorker> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (WorkerRow w : workers.workerRows(t)) {
            boolean employed = !w.joinedOn().isAfter(r.to()) && (w.leftOn() == null || !w.leftOn().isBefore(r.from()));
            if (chosen != null && !chosen.contains(w.id())) continue;
            if (!employed) {
                if (chosen != null) throw ApiException.badRequest("NOT_EMPLOYED", w.name() + " was not working here in this period.");
                continue;
            }
            if (overlapsPostedRun(t, w.id(), r.from(), r.to())) {
                if (chosen != null || strict) throw ApiException.conflict("PAY_RUN_OVERLAP", w.name() + " is already paid for part of this period.");
                skipped.add("ALREADY_PAID:" + w.name());
                continue;
            }
            if ("MONTHLY".equals(w.payType()) && !coversWholeMonths(r.from(), r.to())) {
                if (chosen != null) throw ApiException.badRequest("MONTHLY_NEEDS_WHOLE_MONTHS", w.name() + " is paid monthly; choose whole calendar months.");
                skipped.add("PARTIAL_MONTH:" + w.name());
                continue;
            }
            for (Extra e : extras.getOrDefault(w.id(), List.of())) workers.requireCostObject(t, e.costObjectId());
            Tally tally = tally(t, w, r.from(), r.to(), wholeMonths, extras.getOrDefault(w.id(), List.of()));
            List<CostShare> costs = new ArrayList<>();
            BigDecimal gross = ZERO;
            for (Map.Entry<Long, BigDecimal> e : tally.amount.entrySet()) {
                BigDecimal value = money(e.getValue());
                if (value.signum() == 0) continue;
                gross = gross.add(value);
                costs.add(new CostShare(e.getKey(), e.getKey() == null ? null : costObjectNames.get(e.getKey()),
                        tally.days.getOrDefault(e.getKey(), BigDecimal.ZERO), value));
            }
            if (gross.signum() == 0 && chosen == null) continue;
            out.add(new PayRunWorker(w.id(), w.name(), w.payType(), tally.totalDays, money(tally.timePay), money(tally.piecePay), money(tally.extraPay), gross,
                    costs, workers.advance(t, w.partyId()), workers.owed(t, w.partyId()), tally.notes));
        }
        BigDecimal total = out.stream().map(PayRunWorker::gross).reduce(ZERO, BigDecimal::add);
        return new PayRunPreview(r.from(), r.to(), out, total, skipped);
    }

    private Tally tally(long t, WorkerRow w, LocalDate from, LocalDate to, List<YearMonth> wholeMonths, List<Extra> extras) {
        Tally tally = new Tally();
        List<Rate> rates = workers.rates(t, w.id());
        List<Map<String, Object>> attendance = jdbc.sql("""
                        select work_date, status, cost_object_id from sf_attendance where tenant_id = :t and worker_id = :w and work_date between :from and :to
                        order by work_date""")
                .param("t", t).param("w", w.id()).param("from", from).param("to", to).query().listOfRows();
        if ("DAILY".equals(w.payType())) {
            for (Map<String, Object> a : attendance) {
                LocalDate day = ((java.sql.Date) a.get("work_date")).toLocalDate();
                String status = (String) a.get("status");
                BigDecimal units = switch (status) { case "PRESENT", "PAID_LEAVE" -> BigDecimal.ONE; case "HALF_DAY" -> HALF; default -> BigDecimal.ZERO; };
                if (units.signum() == 0) continue;
                Rate rate = WorkerService.rateOn(rates, day);
                if (rate == null) { addNote(tally, "NO_RATE"); continue; }
                Long co = "PAID_LEAVE".equals(status) || a.get("cost_object_id") == null ? null : ((Number) a.get("cost_object_id")).longValue();
                BigDecimal value = units.multiply(rate.rate());
                tally.add(co, value, units);
                tally.timePay = tally.timePay.add(value);
                tally.totalDays = tally.totalDays.add(units);
            }
        } else if ("MONTHLY".equals(w.payType())) {
            for (YearMonth m : wholeMonths) monthly(tally, w, rates, m, attendance);
        }
        // Piece work, for any pay type
        for (Map<String, Object> p : jdbc.sql("""
                        select quantity, rate, cost_object_id from sf_piece_work where tenant_id = :t and worker_id = :w and work_date between :from and :to""")
                .param("t", t).param("w", w.id()).param("from", from).param("to", to).query().listOfRows()) {
            BigDecimal value = ((BigDecimal) p.get("quantity")).multiply((BigDecimal) p.get("rate"));
            tally.add(p.get("cost_object_id") == null ? null : ((Number) p.get("cost_object_id")).longValue(), value, BigDecimal.ZERO);
            tally.piecePay = tally.piecePay.add(value);
        }
        for (Extra e : extras) {
            tally.add(e.costObjectId(), e.amount(), BigDecimal.ZERO);
            tally.extraPay = tally.extraPay.add(e.amount());
        }
        return tally;
    }

    /**
     * A monthly worker's pay for one calendar month (G2): the salary, less unpaid leave and days outside employment at
     * salary ÷ days in the month. The cost is split by days worked on each cycle; the rest is overhead.
     */
    private void monthly(Tally tally, WorkerRow w, List<Rate> rates, YearMonth m, List<Map<String, Object>> attendance) {
        Rate rate = WorkerService.rateOn(rates, m.atDay(1));
        if (rate == null) rate = WorkerService.rateOn(rates, m.atEndOfMonth());
        if (rate == null) { addNote(tally, "NO_RATE"); return; }
        int daysInMonth = m.lengthOfMonth();
        int employed = 0;
        for (LocalDate d = m.atDay(1); !d.isAfter(m.atEndOfMonth()); d = d.plusDays(1)) if (w.employedOn(d)) employed++;
        BigDecimal unpaid = BigDecimal.ZERO;
        Map<Long, BigDecimal> worked = new LinkedHashMap<>();
        for (Map<String, Object> a : attendance) {
            LocalDate day = ((java.sql.Date) a.get("work_date")).toLocalDate();
            if (!YearMonth.from(day).equals(m)) continue;
            String status = (String) a.get("status");
            if ("UNPAID_LEAVE".equals(status)) unpaid = unpaid.add(BigDecimal.ONE);
            BigDecimal units = "PRESENT".equals(status) ? BigDecimal.ONE : "HALF_DAY".equals(status) ? HALF : BigDecimal.ZERO;
            if (units.signum() > 0 && a.get("cost_object_id") != null) worked.merge(((Number) a.get("cost_object_id")).longValue(), units, BigDecimal::add);
        }
        BigDecimal paidDays = BigDecimal.valueOf(employed).subtract(unpaid);
        if (paidDays.signum() <= 0) return;
        BigDecimal salary = paidDays.compareTo(BigDecimal.valueOf(daysInMonth)) == 0 ? rate.rate()
                : rate.rate().multiply(paidDays).divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
        BigDecimal assigned = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : worked.entrySet()) {
            BigDecimal share = salary.multiply(e.getValue()).divide(paidDays, 2, RoundingMode.HALF_UP);
            tally.add(e.getKey(), share, e.getValue());
            assigned = assigned.add(share);
        }
        tally.add(null, salary.subtract(assigned), BigDecimal.ZERO);
        tally.timePay = tally.timePay.add(salary);
        tally.totalDays = tally.totalDays.add(paidDays);
    }

    @Transactional
    public Document postPayRun(TenantContext ctx, PayRunRequest r, String key) {
        Optional<Document> replay = documents.replayed(ctx, key, DocType.PAY_RUN, r);
        if (replay.isPresent()) return replay.get();
        long t = ctx.tenantId();
        // Serialize pay runs per tenant so two runs cannot both pay the same days
        jdbc.sql("select id from sf_tenants where id = :t for update").param("t", t).query(Long.class).single();
        PayRunPreview p = compute(ctx, r, true);
        if (p.total().signum() == 0) throw ApiException.badRequest("NOTHING_TO_PAY", "There is nothing to pay for this period.");
        LocalDate date = r.date() != null ? r.date() : r.to();
        if (date.isBefore(r.to())) throw ApiException.badRequest("INVALID_PAY_DATE", "Post the pay run on or after the period's last day.");
        Long labour = jdbc.sql("select id from sf_finance_categories where tenant_id is null and code = 'LABOUR'").query(Long.class).single();
        long overhead = costObjects.overhead(t);
        Map<Long, Long> partyOf = new HashMap<>();
        for (PayRunWorker w : p.workers()) partyOf.put(w.workerId(), workers.workerRow(t, w.workerId()).partyId());
        List<ModuleLine> lines = new ArrayList<>();
        List<JournalPart> parts = new ArrayList<>();
        for (PayRunWorker w : p.workers()) {
            if (w.gross().signum() == 0) continue;
            long party = partyOf.get(w.workerId());
            for (CostShare c : w.costs()) {
                lines.add(new ModuleLine(labour, LedgerAccount.LABOUR.code(), "DEBIT", w.name(), c.days().signum() == 0 ? null : c.days(), "days",
                        c.amount(), c.costObjectId() == null ? overhead : c.costObjectId(), null, party, null));
                parts.add(JournalPart.debit(LedgerAccount.LABOUR.code(), c.amount(), labour, party, c.costObjectId()));
            }
            parts.add(JournalPart.credit(LedgerAccount.WAGES_PAYABLE.code(), w.gross(), null, party, null));
        }
        return documents.recordModule(ctx, new ModuleRecord(DocType.PAY_RUN, date, null, null, null, r.from(), r.to(), null, p.total(), r.notes(), null,
                lines), key, r, (id, lineIds) -> {
            for (PayRunWorker w : p.workers()) {
                if (w.gross().signum() == 0) continue;
                jdbc.sql("""
                                insert into sf_pay_run_workers (tenant_id, document_id, worker_id, period_from, period_to, days, time_pay, piece_pay, extra_pay, gross)
                                values (:t, :d, :w, :from, :to, :days, :time, :piece, :extra, :gross)""")
                        .param("t", t).param("d", id).param("w", w.workerId()).param("from", r.from()).param("to", r.to()).param("days", w.days())
                        .param("time", w.timePay()).param("piece", w.piecePay()).param("extra", w.extraPay()).param("gross", w.gross()).update();
            }
            return parts;
        });
    }

    private boolean overlapsPostedRun(long t, long workerId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select exists (select 1 from sf_pay_run_workers r join sf_finance_documents d on d.id = r.document_id
                          where r.tenant_id = :t and r.worker_id = :w and d.status = 'POSTED' and r.period_from <= :to and r.period_to >= :from)""")
                .param("t", t).param("w", workerId).param("from", from).param("to", to).query(Boolean.class).single();
    }

    static boolean coversWholeMonths(LocalDate from, LocalDate to) {
        return from.getDayOfMonth() == 1 && to.equals(YearMonth.from(to).atEndOfMonth());
    }

    private static List<YearMonth> wholeMonths(LocalDate from, LocalDate to) {
        List<YearMonth> months = new ArrayList<>();
        if (!coversWholeMonths(from, to)) return months;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) months.add(m);
        return months;
    }

    private static void addNote(Tally tally, String note) { if (!tally.notes.contains(note)) tally.notes.add(note); }

    // ---- Wage payments (W3) -------------------------------------------------------------------------------------------

    /** One wage-payment record per worker, so each can be reversed alone; advances are recovered in the same record. */
    @Transactional
    public List<RecordRef> pay(TenantContext ctx, WagePaymentRequest r, String key) {
        Set<Long> seen = new HashSet<>();
        List<RecordRef> out = new ArrayList<>();
        for (Payment p : r.payments()) {
            if (!seen.add(p.workerId())) throw ApiException.badRequest("DUPLICATE_LINE", "Each worker can appear only once in a payment.");
            BigDecimal cash = money(p.cash()), recovered = money(p.advanceRecovered()), total = cash.add(recovered);
            if (total.signum() == 0) continue;
            if (cash.signum() > 0 && r.financialAccountId() == null) {
                throw ApiException.badRequest("FINANCIAL_ACCOUNT_REQUIRED", "Choose the cash or bank account the wages are paid from.");
            }
            WorkerRow w = workers.workerRow(ctx.tenantId(), p.workerId());
            String workerKey = key == null ? null : key + ":" + w.id();
            Optional<Document> replay = documents.replayed(ctx, workerKey, DocType.WAGE_PAYMENT, p);
            if (replay.isPresent()) { out.add(ref(replay.get(), w.id())); continue; }
            lockParty(ctx.tenantId(), w.partyId());
            if (recovered.compareTo(workers.advance(ctx.tenantId(), w.partyId())) > 0) {
                throw ApiException.badRequest("MORE_THAN_ADVANCE", "More is recovered from " + w.name() + " than the advance outstanding.");
            }
            if (total.compareTo(workers.owed(ctx.tenantId(), w.partyId())) > 0) {
                throw ApiException.badRequest("WAGES_OVERPAID", w.name() + " is owed less than this. Record extra money as an advance.");
            }
            List<ModuleLine> lines = List.of(new ModuleLine(null, LedgerAccount.WAGES_PAYABLE.code(), "DEBIT", "Wages · " + w.name(), null, null, total,
                    null, null, w.partyId(), null));
            Document doc = documents.recordModule(ctx, new ModuleRecord(DocType.WAGE_PAYMENT, r.date(), w.partyId(), null,
                    cash.signum() > 0 ? r.financialAccountId() : null, null, null, null, total, r.notes(), null, lines), workerKey, p, (id, ids) -> {
                List<JournalPart> parts = new ArrayList<>();
                parts.add(JournalPart.debit(LedgerAccount.WAGES_PAYABLE.code(), total, null, w.partyId(), null));
                if (recovered.signum() > 0) parts.add(JournalPart.credit(LedgerAccount.WORKER_ADVANCES.code(), recovered, null, w.partyId(), null));
                if (cash.signum() > 0) parts.add(JournalPart.cash(r.financialAccountId(), BigDecimal.ZERO, cash));
                return parts;
            });
            out.add(ref(doc, w.id()));
        }
        if (out.isEmpty()) throw ApiException.badRequest("NOTHING_TO_PAY", "There is nothing to pay.");
        return out;
    }

    private static RecordRef ref(Document d, long workerId) {
        return new RecordRef(d.summary().id(), d.summary().number(), workerId, d.summary().totalAmount());
    }

    private void lockParty(long tenantId, long partyId) {
        jdbc.sql("select id from sf_parties where id = :p and tenant_id = :t for update").param("p", partyId).param("t", tenantId).query(Long.class).single();
    }

    static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
