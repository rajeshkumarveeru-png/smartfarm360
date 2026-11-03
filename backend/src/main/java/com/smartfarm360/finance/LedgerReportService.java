package com.smartfarm360.finance;

import com.smartfarm360.finance.FinanceDtos.FinancialAccount;
import com.smartfarm360.finance.ReportService.AmountRow;
import com.smartfarm360.finance.ReportService.IncomeStatement;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
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
 * Reports added by the dashboard and reports design (section 4): monthly profit and loss, category breakdown,
 * comparisons, balance sheet, trial balance and party statement. Profit figures reuse {@link ReportService#incomeStatement}
 * so every report agrees (principle 1); balances come straight from the journal.
 */
@Service
public class LedgerReportService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final MasterDataService master;
    private final FinanceSettingsService settings;

    public LedgerReportService(JdbcClient jdbc, ReportService reports, MasterDataService master, FinanceSettingsService settings) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.master = master;
        this.settings = settings;
    }

    // ---- Records -------------------------------------------------------------------------------------------------

    /** One line of a report across columns: {@code amounts} has one entry per month (or per compared period). */
    public record MonthlyRow(String section, String accountCode, Long categoryId, String categoryCode, String categoryName,
                             List<BigDecimal> amounts, BigDecimal total) {}

    /** Months of the range as columns (H5); the total column equals the income statement for the whole range. */
    public record MonthlyProfitAndLoss(LocalDate from, LocalDate to, String basis, boolean actual, Long farmId, List<String> months,
                                       List<MonthlyRow> rows, List<MonthlyRow> totals) {}

    /** {@code changePercent}: null when the earlier amount is zero (shown as "—", H10). */
    public record CategoryRow(String accountCode, Long categoryId, String categoryCode, String categoryName, BigDecimal amount,
                              BigDecimal share, BigDecimal previous, BigDecimal change, BigDecimal changePercent) {}

    public record CategoryBreakdown(LocalDate from, LocalDate to, String kind, String basis, boolean actual, Long farmId, Long costObjectId,
                                    String compare, LocalDate previousFrom, LocalDate previousTo, List<CategoryRow> rows, BigDecimal total,
                                    BigDecimal previousTotal) {}

    public record ComparedLine(String section, String accountCode, Long categoryId, String categoryCode, String categoryName,
                               BigDecimal current, BigDecimal previous, BigDecimal change, BigDecimal changePercent) {}

    /** The income statement beside an earlier period (H10). */
    public record IncomeComparison(String compare, IncomeStatement current, IncomeStatement previous, List<ComparedLine> lines,
                                   List<ComparedLine> totals) {}

    public record BalanceLine(String accountCode, BigDecimal amount) {}

    /**
     * As of a date (H5). Assets are shown net (accumulated depreciation negative); equity includes profit before this
     * fiscal year and profit this year. {@code difference} is zero when the journal is balanced.
     */
    public record BalanceSheet(LocalDate asOf, LocalDate fiscalYearFrom, boolean actual, List<FinancialAccount> cashAccounts,
                               List<BalanceLine> assets, BigDecimal assetTotal, List<BalanceLine> liabilities, BigDecimal liabilityTotal,
                               List<BalanceLine> equity, BigDecimal earlierProfit, BigDecimal yearProfit, BigDecimal equityTotal,
                               BigDecimal liabilitiesAndEquity, BigDecimal difference) {}

    /** {@code opening}/{@code closing}: debit minus credit before and at the end of the range. */
    public record TrialRow(String accountCode, String type, BigDecimal opening, BigDecimal debit, BigDecimal credit, BigDecimal closing) {}

    public record TrialBalance(LocalDate from, LocalDate to, List<TrialRow> rows, BigDecimal debitTotal, BigDecimal creditTotal,
                               BigDecimal closingTotal) {}

    /** {@code balance}: positive when the party owes the farm, negative when the farm owes the party. */
    public record StatementLine(long documentId, String documentType, String number, LocalDate date, String accountCode, BigDecimal debit,
                                BigDecimal credit, BigDecimal balance, boolean reversal) {}

    public record OpenItem(long documentId, String documentType, String number, LocalDate date, LocalDate dueDate, BigDecimal total, BigDecimal open) {}

    public record PartyStatement(long partyId, String name, String phone, String village, LocalDate from, LocalDate to, BigDecimal opening,
                                 List<StatementLine> lines, BigDecimal closing, List<OpenItem> openItems) {}

    // ---- Monthly profit and loss ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MonthlyProfitAndLoss monthly(TenantContext ctx, LocalDate from, LocalDate to, Long farmId, String basis) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        YearMonth first = YearMonth.from(from), last = YearMonth.from(to);
        if (ChronoUnit.MONTHS.between(first, last) >= 12) throw ApiException.badRequest("MONTHLY_RANGE_TOO_LONG", "Choose at most 12 months.");
        List<String> months = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) months.add(m.toString());
        List<IncomeStatement> columns;
        if ("CASH".equals(basis)) {
            columns = new ArrayList<>();
            for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                LocalDate a = m.atDay(1).isBefore(from) ? from : m.atDay(1), b = m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth();
                columns.add(reports.incomeStatement(ctx, a, b, farmId, null, basis));
            }
        } else {
            columns = reports.accrualByMonth(ctx, from, to, farmId);
        }
        IncomeStatement whole = reports.incomeStatement(ctx, from, to, farmId, null, basis);

        Map<String, AmountRow> meta = new LinkedHashMap<>();
        Map<String, String> sectionOf = new HashMap<>();
        for (IncomeStatement s : concat(columns, whole)) {
            for (AmountRow r : s.operatingRevenue()) { meta.putIfAbsent(key(r), r); sectionOf.put(key(r), "REVENUE"); }
            for (AmountRow r : s.operatingCosts()) { meta.putIfAbsent(key(r), r); sectionOf.put(key(r), "COSTS"); }
            for (AmountRow r : s.otherIncome()) { meta.putIfAbsent(key(r), r); sectionOf.put(key(r), "OTHER_INCOME"); }
        }
        List<MonthlyRow> rows = new ArrayList<>();
        for (String section : List.of("REVENUE", "COSTS", "OTHER_INCOME")) {
            meta.entrySet().stream().filter(e -> section.equals(sectionOf.get(e.getKey()))).forEach(e -> {
                AmountRow r = e.getValue();
                List<BigDecimal> amounts = columns.stream().map(s -> amountOf(s, section, e.getKey())).toList();
                rows.add(new MonthlyRow(section, r.accountCode(), r.categoryId(), r.categoryCode(), r.categoryName(), amounts, amountOf(whole, section, e.getKey())));
            });
        }
        List<MonthlyRow> totals = List.of(
                total("OPERATING_REVENUE", columns, whole, IncomeStatement::operatingRevenueTotal),
                total("OPERATING_COSTS", columns, whole, IncomeStatement::operatingCostTotal),
                total("OPERATING_PROFIT", columns, whole, IncomeStatement::operatingProfit),
                total("OTHER_INCOME", columns, whole, IncomeStatement::otherIncomeTotal),
                total("INTEREST", columns, whole, IncomeStatement::interest),
                total("LOSSES", columns, whole, IncomeStatement::losses),
                total("NET_PROFIT", columns, whole, IncomeStatement::netProfit));
        return new MonthlyProfitAndLoss(from, to, whole.basis(), true, farmId, months, rows, totals);
    }

    private static List<IncomeStatement> concat(List<IncomeStatement> a, IncomeStatement b) {
        List<IncomeStatement> all = new ArrayList<>(a);
        all.add(b);
        return all;
    }

    private static MonthlyRow total(String name, List<IncomeStatement> columns, IncomeStatement whole, java.util.function.Function<IncomeStatement, BigDecimal> f) {
        return new MonthlyRow(name, null, null, null, null, columns.stream().map(f).toList(), f.apply(whole));
    }

    private static String key(AmountRow r) { return r.accountCode() + "|" + r.categoryId(); }

    private static BigDecimal amountOf(IncomeStatement s, String section, String key) {
        List<AmountRow> rows = switch (section) {
            case "REVENUE" -> s.operatingRevenue();
            case "COSTS" -> s.operatingCosts();
            default -> s.otherIncome();
        };
        return rows.stream().filter(r -> key(r).equals(key)).map(AmountRow::amount).findFirst().orElse(ZERO);
    }

    // ---- Comparisons ---------------------------------------------------------------------------------------------

    /** H10: {@code previous} is the same length just before; {@code lastYear} is the same dates a year earlier. */
    public static LocalDate[] comparePeriod(LocalDate from, LocalDate to, String compare) {
        if ("lastYear".equals(compare)) return new LocalDate[]{from.minusYears(1), to.minusYears(1)};
        if ("previous".equals(compare)) {
            // Whole months compare with the same number of whole months; any other range with the same number of days.
            if (from.getDayOfMonth() == 1 && to.equals(YearMonth.from(to).atEndOfMonth())) {
                long months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
                YearMonth start = YearMonth.from(from).minusMonths(months);
                return new LocalDate[]{start.atDay(1), YearMonth.from(from).minusMonths(1).atEndOfMonth()};
            }
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            return new LocalDate[]{from.minusDays(days), from.minusDays(1)};
        }
        throw ApiException.badRequest("INVALID_COMPARISON", "Compare with the previous period or the same period last year.");
    }

    public static BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) return null;
        return current.subtract(previous).multiply(HUNDRED).divide(previous.abs(), 1, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public IncomeComparison compareIncome(TenantContext ctx, LocalDate from, LocalDate to, Long farmId, Long costObjectId, String basis, String compare) {
        IncomeStatement current = reports.incomeStatement(ctx, from, to, farmId, costObjectId, basis);
        LocalDate[] p = comparePeriod(from, to, compare);
        IncomeStatement previous = reports.incomeStatement(ctx, p[0], p[1], farmId, costObjectId, basis);
        List<ComparedLine> lines = new ArrayList<>();
        compareRows(lines, "REVENUE", current.operatingRevenue(), previous.operatingRevenue());
        compareRows(lines, "COSTS", current.operatingCosts(), previous.operatingCosts());
        compareRows(lines, "OTHER_INCOME", current.otherIncome(), previous.otherIncome());
        List<ComparedLine> totals = List.of(
                line("OPERATING_REVENUE", current.operatingRevenueTotal(), previous.operatingRevenueTotal()),
                line("OPERATING_COSTS", current.operatingCostTotal(), previous.operatingCostTotal()),
                line("OPERATING_PROFIT", current.operatingProfit(), previous.operatingProfit()),
                line("OTHER_INCOME", current.otherIncomeTotal(), previous.otherIncomeTotal()),
                line("INTEREST", current.interest(), previous.interest()),
                line("LOSSES", current.losses(), previous.losses()),
                line("NET_PROFIT", current.netProfit(), previous.netProfit()));
        return new IncomeComparison(compare, current, previous, lines, totals);
    }

    private static void compareRows(List<ComparedLine> out, String section, List<AmountRow> current, List<AmountRow> previous) {
        Map<String, AmountRow> meta = new LinkedHashMap<>();
        current.forEach(r -> meta.putIfAbsent(key(r), r));
        previous.forEach(r -> meta.putIfAbsent(key(r), r));
        meta.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            BigDecimal c = find(current, e.getKey()), p = find(previous, e.getKey());
            AmountRow r = e.getValue();
            out.add(new ComparedLine(section, r.accountCode(), r.categoryId(), r.categoryCode(), r.categoryName(), c, p, c.subtract(p), changePercent(c, p)));
        });
    }

    private static ComparedLine line(String name, BigDecimal c, BigDecimal p) {
        return new ComparedLine(name, null, null, null, null, c, p, c.subtract(p), changePercent(c, p));
    }

    private static BigDecimal find(List<AmountRow> rows, String key) {
        return rows.stream().filter(r -> key(r).equals(key)).map(AmountRow::amount).findFirst().orElse(ZERO);
    }

    // ---- Category breakdown --------------------------------------------------------------------------------------

    /** {@code kind}: COSTS (operating costs, interest and losses) or INCOME (operating revenue and other income). */
    @Transactional(readOnly = true)
    public CategoryBreakdown categories(TenantContext ctx, LocalDate from, LocalDate to, String kind, Long farmId, Long costObjectId,
                                        String basis, String compare) {
        boolean income = "INCOME".equals(kind);
        if (!income && !"COSTS".equals(kind)) throw ApiException.badRequest("INVALID_REPORT_KIND", "Choose costs or income.");
        IncomeStatement current = reports.incomeStatement(ctx, from, to, farmId, costObjectId, basis);
        LocalDate[] p = compare == null || compare.isBlank() ? null : comparePeriod(from, to, compare);
        IncomeStatement previous = p == null ? null : reports.incomeStatement(ctx, p[0], p[1], farmId, costObjectId, basis);
        List<AmountRow> now = categoryRows(current, income), before = previous == null ? List.of() : categoryRows(previous, income);
        BigDecimal total = sum(now), previousTotal = previous == null ? null : sum(before);
        Map<String, AmountRow> meta = new LinkedHashMap<>();
        now.forEach(r -> meta.putIfAbsent(key(r), r));
        before.forEach(r -> meta.putIfAbsent(key(r), r));
        List<CategoryRow> rows = new ArrayList<>();
        for (Map.Entry<String, AmountRow> e : meta.entrySet()) {
            BigDecimal amount = find(now, e.getKey());
            BigDecimal prev = previous == null ? null : find(before, e.getKey());
            BigDecimal share = total.signum() == 0 ? null : amount.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
            AmountRow r = e.getValue();
            rows.add(new CategoryRow(r.accountCode(), r.categoryId(), r.categoryCode(), r.categoryName(), amount, share, prev,
                    prev == null ? null : amount.subtract(prev), prev == null ? null : changePercent(amount, prev)));
        }
        rows.sort(Comparator.comparing(CategoryRow::amount).reversed());
        return new CategoryBreakdown(from, to, kind, current.basis(), true, farmId, costObjectId, previous == null ? null : compare,
                p == null ? null : p[0], p == null ? null : p[1], rows, total, previousTotal);
    }

    private static List<AmountRow> categoryRows(IncomeStatement s, boolean income) {
        List<AmountRow> rows = new ArrayList<>();
        if (income) {
            rows.addAll(s.operatingRevenue());
            rows.addAll(s.otherIncome());
        } else {
            rows.addAll(s.operatingCosts());
            if (s.interest().signum() != 0) rows.add(new AmountRow(LedgerAccount.LOAN_INTEREST.code(), null, null, null, s.interest()));
            if (s.losses().signum() != 0) rows.add(new AmountRow(LedgerAccount.LOSSES.code(), null, null, null, s.losses()));
        }
        return rows;
    }

    private static BigDecimal sum(List<AmountRow> rows) { return rows.stream().map(AmountRow::amount).reduce(ZERO, BigDecimal::add); }

    // ---- Balance sheet -------------------------------------------------------------------------------------------

    @Transactional
    public BalanceSheet balanceSheet(TenantContext ctx, LocalDate asOf) {
        settings.ensureDefaults(ctx.tenantId());
        LocalDate yearStart = FinanceSettingsService.fiscalYearStart(asOf, settings.settings(ctx.tenantId()).fiscalYearStartMonth());
        Map<String, BigDecimal> balance = new TreeMap<>();   // debit minus credit
        BigDecimal earlier = ZERO, year = ZERO;               // profit: credit minus debit on income and expense accounts
        for (Map<String, Object> r : jdbc.sql("""
                        select l.account_code, e.entry_date < :yearStart as earlier, sum(l.debit - l.credit) as net
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and e.entry_date <= :asOf group by 1, 2""")
                .param("t", ctx.tenantId()).param("asOf", asOf).param("yearStart", yearStart).query().listOfRows()) {
            String code = (String) r.get("account_code");
            BigDecimal net = (BigDecimal) r.get("net");
            LedgerAccount account = LedgerAccount.ofCode(code);
            if (account.isProfitAndLoss()) {
                if (Boolean.TRUE.equals(r.get("earlier"))) earlier = earlier.subtract(net);
                else year = year.subtract(net);
            } else {
                balance.merge(code, net, BigDecimal::add);
            }
        }
        List<BalanceLine> assets = new ArrayList<>(), liabilities = new ArrayList<>(), equity = new ArrayList<>();
        for (LedgerAccount a : LedgerAccount.values()) {
            BigDecimal net = balance.getOrDefault(a.code(), ZERO).setScale(2, RoundingMode.HALF_UP);
            switch (a.type()) {
                case ASSET, CONTRA_ASSET -> { if (net.signum() != 0 || a.type() == LedgerAccount.Type.ASSET && isCore(a)) assets.add(new BalanceLine(a.code(), net)); }
                case LIABILITY -> { if (net.signum() != 0 || isCore(a)) liabilities.add(new BalanceLine(a.code(), net.negate())); }
                case EQUITY -> { if (net.signum() != 0) equity.add(new BalanceLine(a.code(), net.negate())); }
                default -> { }
            }
        }
        BigDecimal assetTotal = assets.stream().map(BalanceLine::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal liabilityTotal = liabilities.stream().map(BalanceLine::amount).reduce(ZERO, BigDecimal::add);
        earlier = earlier.setScale(2, RoundingMode.HALF_UP);
        year = year.setScale(2, RoundingMode.HALF_UP);
        BigDecimal equityTotal = equity.stream().map(BalanceLine::amount).reduce(ZERO, BigDecimal::add).add(earlier).add(year);
        BigDecimal right = liabilityTotal.add(equityTotal);
        return new BalanceSheet(asOf, yearStart, true, master.financialAccounts(ctx, asOf), assets, assetTotal, liabilities, liabilityTotal,
                equity, earlier, year, equityTotal, right, assetTotal.subtract(right));
    }

    /** Lines shown even at zero, so a new farm business still sees the shape of its balance sheet. */
    private static boolean isCore(LedgerAccount a) {
        return a == LedgerAccount.CASH || a == LedgerAccount.BANK || a == LedgerAccount.RECEIVABLES || a == LedgerAccount.PAYABLES;
    }

    // ---- Trial balance -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TrialBalance trialBalance(TenantContext ctx, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        Map<String, BigDecimal[]> byAccount = new TreeMap<>();   // opening, debit, credit
        jdbc.sql("""
                        select l.account_code,
                               coalesce(sum(case when e.entry_date < :from then l.debit - l.credit end), 0) as opening,
                               coalesce(sum(case when e.entry_date >= :from then l.debit end), 0) as debit,
                               coalesce(sum(case when e.entry_date >= :from then l.credit end), 0) as credit
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and e.entry_date <= :to group by l.account_code""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to).query().listOfRows()
                .forEach(r -> byAccount.put((String) r.get("account_code"),
                        new BigDecimal[]{(BigDecimal) r.get("opening"), (BigDecimal) r.get("debit"), (BigDecimal) r.get("credit")}));
        List<TrialRow> rows = new ArrayList<>();
        BigDecimal debit = ZERO, credit = ZERO, closing = ZERO;
        for (Map.Entry<String, BigDecimal[]> e : byAccount.entrySet()) {
            BigDecimal[] v = e.getValue();
            BigDecimal close = v[0].add(v[1]).subtract(v[2]);
            if (v[0].signum() == 0 && v[1].signum() == 0 && v[2].signum() == 0) continue;
            rows.add(new TrialRow(e.getKey(), LedgerAccount.ofCode(e.getKey()).type().name(), scale(v[0]), scale(v[1]), scale(v[2]), scale(close)));
            debit = debit.add(v[1]);
            credit = credit.add(v[2]);
            closing = closing.add(close);
        }
        return new TrialBalance(from, to, rows, scale(debit), scale(credit), scale(closing));
    }

    private static BigDecimal scale(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    // ---- Dashboard checks ----------------------------------------------------------------------------------------

    /** Count and open amount of posted bills due within {@code days} after {@code asOf} (dashboard, section 3C). */
    public record DueSoon(int count, BigDecimal amount) {}

    @Transactional(readOnly = true)
    public DueSoon billsDueSoon(TenantContext ctx, LocalDate asOf, int days) {
        List<BigDecimal> open = jdbc.sql("""
                        select d.total_amount - coalesce((select sum(a.amount) from sf_payment_allocations a where a.target_document_id = d.id
                                                          and a.removed_at is null and a.allocation_date <= :asOf), 0)
                        from sf_finance_documents d
                        where d.tenant_id = :t and d.doc_type = 'BILL' and d.status = 'POSTED' and d.party_id is not null
                          and d.due_date between :asOf and :until""")
                .param("t", ctx.tenantId()).param("asOf", asOf).param("until", asOf.plusDays(days)).query(BigDecimal.class).list()
                .stream().filter(b -> b.signum() > 0).toList();
        return new DueSoon(open.size(), open.stream().reduce(ZERO, BigDecimal::add));
    }

    /** Draft records not posted for more than {@code days} days (dashboard, section 3C). */
    @Transactional(readOnly = true)
    public int oldDrafts(TenantContext ctx, LocalDate asOf, int days) {
        return jdbc.sql("select count(*) from sf_finance_documents where tenant_id = :t and status = 'DRAFT' and doc_date < :before")
                .param("t", ctx.tenantId()).param("before", asOf.minusDays(days)).query(Integer.class).single();
    }

    // ---- Party statement -----------------------------------------------------------------------------------------

    private static final List<String> PARTY_ACCOUNTS = List.of("1100", "1150", "2000", "2100", "2200");

    @Transactional(readOnly = true)
    public PartyStatement partyStatement(TenantContext ctx, long partyId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        Map<String, Object> party = jdbc.sql("select name, phone, village from sf_parties where id = :id and tenant_id = :t")
                .param("id", partyId).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PARTY_NOT_FOUND", "Party not found."));
        BigDecimal opening = jdbc.sql("""
                        select coalesce(sum(l.debit - l.credit), 0) from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and l.party_id = :p and l.account_code in (:acc) and e.entry_date < :from""")
                .param("t", ctx.tenantId()).param("p", partyId).param("acc", PARTY_ACCOUNTS).param("from", from).query(BigDecimal.class).single();
        List<StatementLine> lines = new ArrayList<>();
        BigDecimal running = scale(opening);
        for (Map<String, Object> r : jdbc.sql("""
                        select d.id, d.doc_type, d.number, e.entry_date, l.account_code, e.kind, sum(l.debit) as debit, sum(l.credit) as credit, min(e.id) as entry
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id join sf_finance_documents d on d.id = e.document_id
                        where l.tenant_id = :t and l.party_id = :p and l.account_code in (:acc) and e.entry_date between :from and :to
                        group by d.id, d.doc_type, d.number, e.entry_date, l.account_code, e.kind, e.id order by e.entry_date, e.id, l.account_code""")
                .param("t", ctx.tenantId()).param("p", partyId).param("acc", PARTY_ACCOUNTS).param("from", from).param("to", to).query().listOfRows()) {
            BigDecimal debit = scale((BigDecimal) r.get("debit")), credit = scale((BigDecimal) r.get("credit"));
            if (debit.signum() == 0 && credit.signum() == 0) continue;
            running = running.add(debit).subtract(credit);
            lines.add(new StatementLine(((Number) r.get("id")).longValue(), (String) r.get("doc_type"), (String) r.get("number"),
                    ((java.sql.Date) r.get("entry_date")).toLocalDate(), (String) r.get("account_code"), debit, credit, running,
                    !"POSTING".equals(r.get("kind"))));
        }
        List<OpenItem> open = jdbc.sql("""
                        select d.id, d.doc_type, d.number, d.doc_date, d.due_date, d.total_amount,
                               d.total_amount - coalesce((select sum(a.amount) from sf_payment_allocations a where a.target_document_id = d.id
                                                          and a.removed_at is null and a.allocation_date <= :to), 0) as open
                        from sf_finance_documents d
                        where d.tenant_id = :t and d.party_id = :p and d.doc_type in ('SALE', 'BILL') and d.doc_date <= :to
                          and (d.status = 'POSTED' or (d.status = 'REVERSED' and d.reversal_date > :to))
                        order by d.doc_date, d.id""")
                .param("t", ctx.tenantId()).param("p", partyId).param("to", to)
                .query((rs, i) -> new OpenItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getBigDecimal(6), rs.getBigDecimal(7)))
                .list().stream().filter(o -> o.open().signum() > 0).toList();
        return new PartyStatement(partyId, (String) party.get("name"), (String) party.get("phone"), (String) party.get("village"), from, to,
                scale(opening), lines, running, open);
    }
}
