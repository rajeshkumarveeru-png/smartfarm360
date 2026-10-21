package com.smartfarm360.finance;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.FinanceDtos.FinancialAccount;
import com.smartfarm360.finance.FinanceDtos.Loan;
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
import java.util.stream.Collectors;

/**
 * Reports defined in the posting rules (section 7). All figures are actual (nothing estimated is posted) and are
 * computed from the journal, so every report agrees with every other.
 */
@Service
public class ReportService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final List<String> PROFIT_AND_LOSS_CODES = Arrays.stream(LedgerAccount.values())
            .filter(LedgerAccount::isProfitAndLoss).map(LedgerAccount::code).toList();

    private final JdbcClient jdbc;
    private final MasterDataService master;
    private final FinanceSettingsService settings;
    private final CostObjectService costObjects;

    public ReportService(JdbcClient jdbc, MasterDataService master, FinanceSettingsService settings, CostObjectService costObjects) {
        this.jdbc = jdbc;
        this.master = master;
        this.settings = settings;
        this.costObjects = costObjects;
    }

    public record AmountRow(String accountCode, Long categoryId, String categoryCode, String categoryName, BigDecimal amount) {}

    public record IncomeStatement(LocalDate from, LocalDate to, String basis, boolean actual, Long farmId, Long costObjectId,
                                  List<AmountRow> operatingRevenue, BigDecimal operatingRevenueTotal,
                                  List<AmountRow> operatingCosts, BigDecimal operatingCostTotal, BigDecimal operatingProfit,
                                  List<AmountRow> otherIncome, BigDecimal otherIncomeTotal, BigDecimal interest, BigDecimal losses,
                                  BigDecimal netProfit) {}

    public record FlowRow(String section, String accountCode, BigDecimal amount) {}

    public record CashFlow(LocalDate from, LocalDate to, boolean actual, BigDecimal opening, BigDecimal operating, BigDecimal investing,
                           BigDecimal financing, BigDecimal openingBalances, BigDecimal netChange, BigDecimal closing, List<FlowRow> details) {}

    /** Positive amounts. {@code unmatched}: owed but not linked to an open bill/sale; {@code advance}: paid ahead. */
    public record PartyDues(long partyId, String partyName, BigDecimal balance, BigDecimal notDue, BigDecimal days1to30,
                            BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90, BigDecimal unmatched, BigDecimal advance) {}

    public record Dues(LocalDate asOf, List<PartyDues> receivables, BigDecimal receivableTotal, List<PartyDues> payables, BigDecimal payableTotal) {}

    public record Balances(LocalDate asOf, List<FinancialAccount> accounts, BigDecimal cashTotal, List<Loan> loans, BigDecimal loanTotal,
                           BigDecimal receivableTotal, BigDecimal payableTotal) {}

    public record ActivityRow(long costObjectId, String kind, String name, BigDecimal revenue, BigDecimal costs, BigDecimal contribution) {}

    public record Activity(LocalDate from, LocalDate to, boolean actual, List<ActivityRow> rows) {}

    public record Summary(LocalDate fiscalYearFrom, LocalDate asOf, BigDecimal yearRevenue, BigDecimal yearCosts, BigDecimal yearOperatingProfit,
                          BigDecimal yearNetProfit, BigDecimal monthRevenue, BigDecimal monthCosts, BigDecimal cashTotal,
                          BigDecimal receivableTotal, BigDecimal overdueReceivables, BigDecimal payableTotal) {}

    // ---- Income statement ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public IncomeStatement incomeStatement(TenantContext ctx, LocalDate from, LocalDate to, Long farmId, Long costObjectId, String basis) {
        return incomeStatement(ctx, from, to, farmId, costObjectId, basis, false);
    }

    /** {@code withoutAllocations}: leave out overhead allocation records (P16), to show an activity's own costs. */
    @Transactional(readOnly = true)
    public IncomeStatement incomeStatement(TenantContext ctx, LocalDate from, LocalDate to, Long farmId, Long costObjectId, String basis,
                                           boolean withoutAllocations) {
        checkRange(from, to);
        if (farmId != null) master.requireFarm(ctx.tenantId(), farmId);
        if (costObjectId != null) master.requireCostObject(ctx.tenantId(), costObjectId);
        boolean cash = "CASH".equals(basis);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();   // key: account|category
        Map<String, AmountRow> meta = new HashMap<>();

        // Cash basis counts money paid and received for categories: cash bills and sales, cash refunds of sales, and loan interest.
        // Stock bought is not a cost in either view, and stock used or sold (5700) is not cash, so it is left out here.
        String docFilter = cash ? " and ((d.doc_type in ('BILL', 'SALE', 'SALE_RETURN') and d.party_id is null) or d.doc_type = 'LOAN_REPAYMENT')"
                + " and l.account_code <> '5700'" : "";
        jdbc.sql("""
                        select l.account_code, l.category_id, c.code, c.name, sum(l.credit - l.debit) as net
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        join sf_finance_documents d on d.id = e.document_id
                        left join sf_finance_categories c on c.id = l.category_id
                        where l.tenant_id = :t and e.entry_date between :from and :to and l.account_code in (:codes)
                          and (cast(:farm as bigint) is null or l.farm_id = :farm) and (cast(:co as bigint) is null or l.cost_object_id = :co)""" + docFilter
                        + (withoutAllocations ? " and d.doc_type <> 'ALLOCATION'" : "") + """
                        group by l.account_code, l.category_id, c.code, c.name""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to).param("codes", PROFIT_AND_LOSS_CODES)
                .param("farm", farmId).param("co", costObjectId)
                .query((rs, i) -> new AmountRow(rs.getString(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5)))
                .list()
                .forEach(r -> add(totals, meta, r, r.amount()));

        if (cash) {
            long overhead = costObjects.overhead(ctx.tenantId());
            // Party bills and sales count when (and as far as) they are paid, received or settled.
            jdbc.sql("""
                            select l.account_code, l.category_id, c.code, c.name, a.amount, t.total_amount, l.amount as line_amount,
                                   t.doc_type, coalesce(l.cost_object_id, :overhead) as co, coalesce(l.farm_id, t.farm_id) as farm
                            from sf_payment_allocations a
                            join sf_finance_documents s on s.id = a.source_document_id
                            join sf_finance_documents t on t.id = a.target_document_id
                            join sf_finance_document_lines l on l.document_id = t.id
                            left join sf_finance_categories c on c.id = l.category_id
                            where a.tenant_id = :t and a.removed_at is null and a.allocation_date between :from and :to
                              and s.doc_type in ('PAYMENT', 'RECEIPT', 'SETTLEMENT') and s.status = 'POSTED' and t.status = 'POSTED'
                              and l.account_code in (:codes)""")
                    .param("t", ctx.tenantId()).param("from", from).param("to", to).param("codes", PROFIT_AND_LOSS_CODES).param("overhead", overhead)
                    .query().listOfRows()
                    .forEach(row -> {
                        Long co = ((Number) row.get("co")).longValue();
                        Long farm = row.get("farm") == null ? null : ((Number) row.get("farm")).longValue();
                        if (costObjectId != null && !costObjectId.equals(co)) return;
                        if (farmId != null && !farmId.equals(farm)) return;
                        BigDecimal share = ((BigDecimal) row.get("amount")).multiply((BigDecimal) row.get("line_amount"))
                                .divide((BigDecimal) row.get("total_amount"), 2, RoundingMode.HALF_UP);
                        BigDecimal net = "SALE".equals(row.get("doc_type")) ? share : share.negate();
                        AmountRow r = new AmountRow((String) row.get("account_code"),
                                row.get("category_id") == null ? null : ((Number) row.get("category_id")).longValue(),
                                (String) row.get("code"), (String) row.get("name"), net);
                        add(totals, meta, r, net);
                    });
        }

        return assemble(from, to, cash ? "CASH" : "ACCRUAL", farmId, costObjectId, totals, meta);
    }

    /**
     * Accrual income statements for each calendar month of a range (clipped to the range), from one grouped query: the
     * same lines and the same {@link #assemble} as {@link #incomeStatement}, so the months add up to the period
     * (dashboard and reports design, H4, H5).
     */
    @Transactional(readOnly = true)
    public List<IncomeStatement> accrualByMonth(TenantContext ctx, LocalDate from, LocalDate to, Long farmId) {
        checkRange(from, to);
        if (farmId != null) master.requireFarm(ctx.tenantId(), farmId);
        Map<YearMonth, Map<String, BigDecimal>> totals = new TreeMap<>();
        Map<String, AmountRow> meta = new HashMap<>();
        jdbc.sql("""
                        select cast(date_trunc('month', e.entry_date) as date) as month, l.account_code, l.category_id, c.code, c.name,
                               sum(l.credit - l.debit) as net
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        left join sf_finance_categories c on c.id = l.category_id
                        where l.tenant_id = :t and e.entry_date between :from and :to and l.account_code in (:codes)
                          and (cast(:farm as bigint) is null or l.farm_id = :farm)
                        group by 1, 2, 3, 4, 5""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to).param("codes", PROFIT_AND_LOSS_CODES).param("farm", farmId)
                .query().listOfRows()
                .forEach(row -> {
                    YearMonth m = YearMonth.from(((java.sql.Date) row.get("month")).toLocalDate());
                    AmountRow r = new AmountRow((String) row.get("account_code"),
                            row.get("category_id") == null ? null : ((Number) row.get("category_id")).longValue(),
                            (String) row.get("code"), (String) row.get("name"), (BigDecimal) row.get("net"));
                    add(totals.computeIfAbsent(m, x -> new LinkedHashMap<>()), meta, r, r.amount());
                });
        List<IncomeStatement> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            LocalDate a = m.atDay(1).isBefore(from) ? from : m.atDay(1), b = m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth();
            months.add(assemble(a, b, "ACCRUAL", farmId, null, totals.getOrDefault(m, Map.of()), meta));
        }
        return months;
    }

    /** Sorts account and category totals (credit minus debit) into the income statement's sections. */
    private static IncomeStatement assemble(LocalDate from, LocalDate to, String basis, Long farmId, Long costObjectId,
                                            Map<String, BigDecimal> totals, Map<String, AmountRow> meta) {
        List<AmountRow> revenue = new ArrayList<>(), costs = new ArrayList<>(), other = new ArrayList<>();
        BigDecimal interest = ZERO, losses = ZERO;
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            AmountRow m = meta.get(e.getKey());
            LedgerAccount account = LedgerAccount.ofCode(m.accountCode());
            BigDecimal net = e.getValue();
            if (net.signum() == 0) continue;
            if (account.isOperatingRevenue()) revenue.add(withAmount(m, net));
            else if (account.isOtherIncome()) other.add(withAmount(m, net));
            else if (account == LedgerAccount.LOAN_INTEREST) interest = interest.add(net.negate());
            else if (account == LedgerAccount.LOSSES) losses = losses.add(net.negate());
            else if (account.isOperatingCost()) costs.add(withAmount(m, net.negate()));
        }
        BigDecimal revenueTotal = sum(revenue), costTotal = sum(costs), otherTotal = sum(other);
        BigDecimal operatingProfit = revenueTotal.subtract(costTotal);
        BigDecimal netProfit = operatingProfit.add(otherTotal).subtract(interest).subtract(losses);
        return new IncomeStatement(from, to, basis, true, farmId, costObjectId, sorted(revenue), revenueTotal,
                sorted(costs), costTotal, operatingProfit, sorted(other), otherTotal, interest, losses, netProfit);
    }

    /** Overhead allocated to an activity in a period (net of reversals), by expense category. */
    @Transactional(readOnly = true)
    public List<AmountRow> allocatedOverhead(TenantContext ctx, long costObjectId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        return jdbc.sql("""
                        select l.account_code, l.category_id, c.code, c.name, sum(l.debit - l.credit)
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        join sf_finance_documents d on d.id = e.document_id
                        left join sf_finance_categories c on c.id = l.category_id
                        where l.tenant_id = :t and l.cost_object_id = :co and d.doc_type = 'ALLOCATION' and e.entry_date between :from and :to
                        group by l.account_code, l.category_id, c.code, c.name having sum(l.debit - l.credit) <> 0
                        order by l.account_code, l.category_id""")
                .param("t", ctx.tenantId()).param("co", costObjectId).param("from", from).param("to", to)
                .query((rs, i) -> new AmountRow(rs.getString(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5)))
                .list();
    }

    private static void add(Map<String, BigDecimal> totals, Map<String, AmountRow> meta, AmountRow r, BigDecimal amount) {
        String key = r.accountCode() + "|" + r.categoryId();
        totals.merge(key, amount, BigDecimal::add);
        meta.putIfAbsent(key, r);
    }

    private static AmountRow withAmount(AmountRow r, BigDecimal amount) {
        return new AmountRow(r.accountCode(), r.categoryId(), r.categoryCode(), r.categoryName(), amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static List<AmountRow> sorted(List<AmountRow> rows) {
        rows.sort(Comparator.comparing(AmountRow::accountCode).thenComparing(r -> r.categoryId() == null ? 0L : r.categoryId()));
        return rows;
    }

    // ---- Cash flow -----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CashFlow cashFlow(TenantContext ctx, LocalDate from, LocalDate to) {
        checkRange(from, to);
        BigDecimal opening = jdbc.sql("""
                        select coalesce(sum(l.debit - l.credit), 0) from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and l.account_code in ('1000', '1010') and e.entry_date < :from""")
                .param("t", ctx.tenantId()).param("from", from).query(BigDecimal.class).single();
        Map<String, BigDecimal> bySectionAccount = new TreeMap<>();
        jdbc.sql("""
                        select l.account_code, sum(l.credit - l.debit) as cash_effect
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and e.entry_date between :from and :to and l.account_code not in ('1000', '1010')
                          and exists (select 1 from sf_journal_lines c where c.entry_id = e.id and c.account_code in ('1000', '1010'))
                        group by l.account_code""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to).query().listOfRows()
                .forEach(row -> {
                    LedgerAccount account = LedgerAccount.ofCode((String) row.get("account_code"));
                    bySectionAccount.merge(account.flow().name() + "|" + account.code(), (BigDecimal) row.get("cash_effect"), BigDecimal::add);
                });
        BigDecimal operating = ZERO, investing = ZERO, financing = ZERO, openingBalances = ZERO;
        List<FlowRow> details = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : bySectionAccount.entrySet()) {
            String[] key = e.getKey().split("\\|");
            BigDecimal amount = e.getValue();
            if (amount.signum() == 0) continue;
            details.add(new FlowRow(key[0], key[1], amount));
            switch (LedgerAccount.Flow.valueOf(key[0])) {
                case OPERATING -> operating = operating.add(amount);
                case INVESTING -> investing = investing.add(amount);
                case FINANCING -> financing = financing.add(amount);
                case OPENING -> openingBalances = openingBalances.add(amount);
                case CASH -> { /* own-account transfers never reach here */ }
            }
        }
        BigDecimal net = operating.add(investing).add(financing).add(openingBalances);
        return new CashFlow(from, to, true, opening, operating, investing, financing, openingBalances, net, opening.add(net), details);
    }

    // ---- Dues (receivables and payables with ageing) -------------------------------------------------------------

    @Transactional(readOnly = true)
    public Dues dues(TenantContext ctx, LocalDate asOf) {
        List<PartyDues> receivables = partyDues(ctx, asOf, "1100", "SALE");
        List<PartyDues> payables = partyDues(ctx, asOf, "2000", "BILL");
        return new Dues(asOf, receivables, receivables.stream().map(PartyDues::balance).filter(b -> b.signum() > 0).reduce(ZERO, BigDecimal::add),
                payables, payables.stream().map(PartyDues::balance).filter(b -> b.signum() > 0).reduce(ZERO, BigDecimal::add));
    }

    private List<PartyDues> partyDues(TenantContext ctx, LocalDate asOf, String controlAccount, String docType) {
        String sign = "1100".equals(controlAccount) ? "l.debit - l.credit" : "l.credit - l.debit";
        Map<Long, BigDecimal> balances = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        jdbc.sql("select l.party_id, p.name, sum(" + sign + ") from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id " +
                        "join sf_parties p on p.id = l.party_id where l.tenant_id = :t and l.account_code = :acc and e.entry_date <= :asOf group by l.party_id, p.name")
                .param("t", ctx.tenantId()).param("acc", controlAccount).param("asOf", asOf).query().listOfRows()
                .forEach(r -> {
                    long id = ((Number) r.get("party_id")).longValue();
                    balances.put(id, (BigDecimal) r.get("sum"));
                    names.put(id, (String) r.get("name"));
                });
        record Open(long partyId, LocalDate ageFrom, BigDecimal open) {}
        List<Open> open = jdbc.sql("""
                        select d.party_id, coalesce(d.due_date, d.doc_date) as age_from,
                               d.total_amount - coalesce((select sum(a.amount) from sf_payment_allocations a where a.target_document_id = d.id
                                                          and a.removed_at is null and a.allocation_date <= :asOf), 0) as open
                        from sf_finance_documents d
                        where d.tenant_id = :t and d.doc_type = :type and d.party_id is not null and d.doc_date <= :asOf
                          and (d.status = 'POSTED' or (d.status = 'REVERSED' and d.reversal_date > :asOf))""")
                .param("t", ctx.tenantId()).param("type", docType).param("asOf", asOf)
                .query((rs, i) -> new Open(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3)))
                .list().stream().filter(o -> o.open().signum() > 0).toList();
        Map<Long, List<Open>> openByParty = open.stream().collect(Collectors.groupingBy(Open::partyId));

        List<PartyDues> result = new ArrayList<>();
        Set<Long> parties = new TreeSet<>(balances.keySet());
        parties.addAll(openByParty.keySet());
        for (long partyId : parties) {
            BigDecimal balance = balances.getOrDefault(partyId, ZERO);
            BigDecimal[] buckets = {ZERO, ZERO, ZERO, ZERO, ZERO};
            BigDecimal openTotal = ZERO;
            for (Open o : openByParty.getOrDefault(partyId, List.of())) {
                long days = ChronoUnit.DAYS.between(o.ageFrom(), asOf);
                int bucket = days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : 4;
                buckets[bucket] = buckets[bucket].add(o.open());
                openTotal = openTotal.add(o.open());
            }
            BigDecimal residual = balance.subtract(openTotal);
            if (balance.signum() == 0 && openTotal.signum() == 0) continue;
            String name = names.computeIfAbsent(partyId, id -> jdbc.sql("select name from sf_parties where id = :id").param("id", id).query(String.class).single());
            result.add(new PartyDues(partyId, name, balance, buckets[0], buckets[1], buckets[2], buckets[3], buckets[4],
                    residual.signum() > 0 ? residual : ZERO, residual.signum() < 0 ? residual.negate() : ZERO));
        }
        result.sort(Comparator.comparing(PartyDues::balance).reversed());
        return result;
    }

    // ---- Balances, activity and dashboard ------------------------------------------------------------------------

    @Transactional
    public Balances balances(TenantContext ctx, LocalDate asOf) { return balances(ctx, asOf, dues(ctx, asOf)); }

    /** {@code dues}: already computed for {@code asOf}, so the dashboard ages receivables once. */
    @Transactional
    public Balances balances(TenantContext ctx, LocalDate asOf, Dues dues) {
        List<FinancialAccount> accounts = master.financialAccounts(ctx, asOf);
        List<Loan> loans = master.loans(ctx);
        return new Balances(asOf, accounts, accounts.stream().map(FinancialAccount::balance).reduce(ZERO, BigDecimal::add), loans,
                loans.stream().map(Loan::outstanding).reduce(ZERO, BigDecimal::add), dues.receivableTotal(), dues.payableTotal());
    }

    @Transactional(readOnly = true)
    public Activity activity(TenantContext ctx, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<String> revenueCodes = Arrays.stream(LedgerAccount.values()).filter(LedgerAccount::isOperatingRevenue).map(LedgerAccount::code).toList();
        List<String> costCodes = Arrays.stream(LedgerAccount.values()).filter(LedgerAccount::isOperatingCost).map(LedgerAccount::code).toList();
        List<ActivityRow> rows = jdbc.sql("""
                        select co.id, co.kind, co.name,
                               coalesce(sum(case when l.account_code in (:rev) then l.credit - l.debit end), 0) as revenue,
                               coalesce(sum(case when l.account_code in (:cost) then l.debit - l.credit end), 0) as costs
                        from sf_cost_objects co
                        left join (sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id and e.entry_date between :from and :to)
                               on l.cost_object_id = co.id and l.tenant_id = co.tenant_id
                        where co.tenant_id = :t group by co.id, co.kind, co.name order by co.kind desc, co.name""")
                .param("rev", revenueCodes).param("cost", costCodes).param("from", from).param("to", to).param("t", ctx.tenantId())
                .query((rs, i) -> new ActivityRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(4).subtract(rs.getBigDecimal(5))))
                .list().stream().filter(r -> r.revenue().signum() != 0 || r.costs().signum() != 0 || "OVERHEAD".equals(r.kind())).toList();
        return new Activity(from, to, true, rows);
    }

    @Transactional
    public Summary summary(TenantContext ctx, LocalDate asOf) { return summary(ctx, asOf, dues(ctx, asOf)); }

    /** {@code dues}: already computed for {@code asOf} (see {@link #balances(TenantContext, LocalDate, Dues)}). */
    @Transactional
    public Summary summary(TenantContext ctx, LocalDate asOf, Dues dues) {
        settings.ensureDefaults(ctx.tenantId());
        LocalDate yearStart = FinanceSettingsService.fiscalYearStart(asOf, settings.settings(ctx.tenantId()).fiscalYearStartMonth());
        IncomeStatement year = incomeStatement(ctx, yearStart, asOf, null, null, "ACCRUAL");
        IncomeStatement month = incomeStatement(ctx, asOf.withDayOfMonth(1), asOf, null, null, "ACCRUAL");
        BigDecimal overdue = dues.receivables().stream()
                .map(d -> d.days1to30().add(d.days31to60()).add(d.days61to90()).add(d.over90())).reduce(ZERO, BigDecimal::add);
        BigDecimal cash = master.financialAccounts(ctx, asOf).stream().map(FinancialAccount::balance).reduce(ZERO, BigDecimal::add);
        return new Summary(yearStart, asOf, year.operatingRevenueTotal(), year.operatingCostTotal(), year.operatingProfit(), year.netProfit(),
                month.operatingRevenueTotal(), month.operatingCostTotal(), cash, dues.receivableTotal(), overdue, dues.payableTotal());
    }

    private static BigDecimal sum(List<AmountRow> rows) { return rows.stream().map(AmountRow::amount).reduce(ZERO, BigDecimal::add); }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "The date range is too long.");
    }
}
