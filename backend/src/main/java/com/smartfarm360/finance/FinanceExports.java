package com.smartfarm360.finance;

import com.smartfarm360.finance.LedgerReportService.*;
import com.smartfarm360.finance.ReportService.*;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Finance reports laid out for CSV and XLSX export (dashboard and reports design, section 5), in the reader's language. */
@Component
public class FinanceExports {
    private final ReportLabels labels;
    private final JdbcClient jdbc;

    public FinanceExports(ReportLabels labels, JdbcClient jdbc) {
        this.labels = labels;
        this.jdbc = jdbc;
    }

    /** The header lines every report carries (principle 2). */
    private ReportDocument doc(String lang, String titleKey) {
        return new ReportDocument(labels.t(lang, titleKey));
    }

    private void period(ReportDocument d, String lang, LocalDate from, LocalDate to) {
        d.meta(labels.t(lang, "reports.export.period"), from + " – " + to);
    }

    private void asOf(ReportDocument d, String lang, LocalDate asOf) { d.meta(labels.t(lang, "reports.export.asOf"), asOf.toString()); }

    private void basis(ReportDocument d, String lang, String basis) {
        d.meta(labels.t(lang, "fin.basis"), labels.t(lang, "CASH".equals(basis) ? "fin.cashBasis" : "fin.accrual"));
    }

    private void filters(ReportDocument d, String lang, Long tenantId, Long farmId, Long costObjectId) {
        if (farmId != null) d.meta(labels.t(lang, "fin.farm"), jdbc.sql("select name from sf_farms where id = :id and tenant_id = :t")
                .param("id", farmId).param("t", tenantId).query(String.class).optional().orElse(String.valueOf(farmId)));
        if (costObjectId != null) d.meta(labels.t(lang, "fin.activity"), jdbc.sql("select name from sf_cost_objects where id = :id and tenant_id = :t")
                .param("id", costObjectId).param("t", tenantId).query(String.class).optional().orElse(String.valueOf(costObjectId)));
    }

    private void actual(ReportDocument d, String lang) {
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
    }

    String line(String lang, String accountCode, String categoryCode, String categoryName) {
        if (categoryCode != null) return labels.t(lang, "financeCategory." + categoryCode);
        if (categoryName != null) return categoryName;
        return labels.t(lang, "fin.ledger." + accountCode);
    }

    private String sectionLabel(String lang, String section) { return labels.t(lang, "reports.section." + section); }

    // ---- Income statement and comparison -------------------------------------------------------------------------

    public ReportDocument income(String lang, long tenantId, IncomeStatement s) {
        ReportDocument d = doc(lang, "fin.report.income");
        period(d, lang, s.from(), s.to());
        basis(d, lang, s.basis());
        filters(d, lang, tenantId, s.farmId(), s.costObjectId());
        actual(d, lang);
        d.text(labels.t(lang, "fin.description")).money(labels.t(lang, "fin.amount"));
        d.section(labels.t(lang, "fin.operatingRevenue"));
        s.operatingRevenue().forEach(r -> d.row(line(lang, r.accountCode(), r.categoryCode(), r.categoryName()), r.amount()));
        d.totalRow(labels.t(lang, "fin.totalRevenue"), s.operatingRevenueTotal());
        d.section(labels.t(lang, "fin.operatingCosts"));
        s.operatingCosts().forEach(r -> d.row(line(lang, r.accountCode(), r.categoryCode(), r.categoryName()), r.amount()));
        d.totalRow(labels.t(lang, "fin.totalCosts"), s.operatingCostTotal());
        d.totalRow(labels.t(lang, "fin.operatingProfit"), s.operatingProfit());
        if (!s.otherIncome().isEmpty()) {
            d.section(labels.t(lang, "fin.otherIncome"));
            s.otherIncome().forEach(r -> d.row(line(lang, r.accountCode(), r.categoryCode(), r.categoryName()), r.amount()));
        }
        d.row(labels.t(lang, "fin.interest"), s.interest());
        d.row(labels.t(lang, "fin.losses"), s.losses());
        d.totalRow(labels.t(lang, "fin.netProfit"), s.netProfit());
        return d;
    }

    public ReportDocument comparison(String lang, long tenantId, IncomeComparison c) {
        ReportDocument d = doc(lang, "fin.report.income");
        period(d, lang, c.current().from(), c.current().to());
        d.meta(labels.t(lang, "reports.compare." + c.compare()), c.previous().from() + " – " + c.previous().to());
        basis(d, lang, c.current().basis());
        filters(d, lang, tenantId, c.current().farmId(), c.current().costObjectId());
        actual(d, lang);
        d.text(labels.t(lang, "fin.description")).money(labels.t(lang, "reports.thisPeriod")).money(labels.t(lang, "reports.earlierPeriod"))
                .money(labels.t(lang, "reports.change")).column(labels.t(lang, "reports.changePercent"), ReportDocument.Kind.NUMBER);
        String section = null;
        for (ComparedLine l : c.lines()) {
            if (!l.section().equals(section)) d.section(sectionLabel(lang, section = l.section()));
            d.row(line(lang, l.accountCode(), l.categoryCode(), l.categoryName()), l.current(), l.previous(), l.change(), l.changePercent());
        }
        for (ComparedLine l : c.totals()) d.totalRow(sectionLabel(lang, l.section()), l.current(), l.previous(), l.change(), l.changePercent());
        return d;
    }

    // ---- Monthly profit and loss ---------------------------------------------------------------------------------

    public ReportDocument monthly(String lang, long tenantId, MonthlyProfitAndLoss m) {
        ReportDocument d = doc(lang, "reports.title.monthly");
        period(d, lang, m.from(), m.to());
        basis(d, lang, m.basis());
        filters(d, lang, tenantId, m.farmId(), null);
        actual(d, lang);
        d.text(labels.t(lang, "fin.description"));
        m.months().forEach(d::money);
        d.money(labels.t(lang, "fin.total"));
        String section = null;
        for (MonthlyRow r : m.rows()) {
            if (!r.section().equals(section)) d.section(sectionLabel(lang, section = r.section()));
            d.row(cells(line(lang, r.accountCode(), r.categoryCode(), r.categoryName()), r.amounts(), r.total()));
        }
        for (MonthlyRow r : m.totals()) d.totalRow(cells(sectionLabel(lang, r.section()), r.amounts(), r.total()));
        return d;
    }

    private static Object[] cells(String label, List<BigDecimal> amounts, BigDecimal total) {
        Object[] cells = new Object[amounts.size() + 2];
        cells[0] = label;
        for (int i = 0; i < amounts.size(); i++) cells[i + 1] = amounts.get(i);
        cells[cells.length - 1] = total;
        return cells;
    }

    // ---- Categories ----------------------------------------------------------------------------------------------

    public ReportDocument categories(String lang, long tenantId, CategoryBreakdown c) {
        ReportDocument d = doc(lang, "INCOME".equals(c.kind()) ? "reports.title.incomeCategories" : "reports.title.costCategories");
        period(d, lang, c.from(), c.to());
        if (c.compare() != null) d.meta(labels.t(lang, "reports.compare." + c.compare()), c.previousFrom() + " – " + c.previousTo());
        basis(d, lang, c.basis());
        filters(d, lang, tenantId, c.farmId(), c.costObjectId());
        actual(d, lang);
        d.text(labels.t(lang, "fin.category")).money(labels.t(lang, "fin.amount")).column(labels.t(lang, "reports.sharePercent"), ReportDocument.Kind.NUMBER);
        if (c.compare() != null) {
            d.money(labels.t(lang, "reports.earlierPeriod")).money(labels.t(lang, "reports.change"))
                    .column(labels.t(lang, "reports.changePercent"), ReportDocument.Kind.NUMBER);
        }
        for (CategoryRow r : c.rows()) {
            String label = line(lang, r.accountCode(), r.categoryCode(), r.categoryName());
            if (c.compare() != null) d.row(label, r.amount(), r.share(), r.previous(), r.change(), r.changePercent());
            else d.row(label, r.amount(), r.share());
        }
        if (c.compare() != null) d.totalRow(labels.t(lang, "fin.total"), c.total(), null, c.previousTotal(), c.total().subtract(c.previousTotal()),
                LedgerReportService.changePercent(c.total(), c.previousTotal()));
        else d.totalRow(labels.t(lang, "fin.total"), c.total());
        return d;
    }

    // ---- Balance sheet and trial balance -------------------------------------------------------------------------

    public ReportDocument balanceSheet(String lang, BalanceSheet b) {
        ReportDocument d = doc(lang, "reports.title.balanceSheet");
        asOf(d, lang, b.asOf());
        d.meta(labels.t(lang, "reports.fiscalYearFrom"), b.fiscalYearFrom().toString());
        actual(d, lang);
        d.text(labels.t(lang, "fin.description")).money(labels.t(lang, "fin.amount"));
        d.section(labels.t(lang, "reports.section.ASSETS"));
        b.assets().forEach(l -> d.row(labels.t(lang, "fin.ledger." + l.accountCode()), l.amount()));
        d.totalRow(labels.t(lang, "reports.assetTotal"), b.assetTotal());
        d.section(labels.t(lang, "reports.section.LIABILITIES"));
        b.liabilities().forEach(l -> d.row(labels.t(lang, "fin.ledger." + l.accountCode()), l.amount()));
        d.totalRow(labels.t(lang, "reports.liabilityTotal"), b.liabilityTotal());
        d.section(labels.t(lang, "reports.section.EQUITY"));
        b.equity().forEach(l -> d.row(labels.t(lang, "fin.ledger." + l.accountCode()), l.amount()));
        d.row(labels.t(lang, "reports.earlierProfit"), b.earlierProfit());
        d.row(labels.t(lang, "reports.yearProfit"), b.yearProfit());
        d.totalRow(labels.t(lang, "reports.equityTotal"), b.equityTotal());
        d.totalRow(labels.t(lang, "reports.liabilitiesAndEquity"), b.liabilitiesAndEquity());
        if (b.difference().signum() != 0) d.totalRow(labels.t(lang, "reports.difference"), b.difference());
        return d;
    }

    public ReportDocument trialBalance(String lang, TrialBalance t) {
        ReportDocument d = doc(lang, "reports.title.trialBalance");
        period(d, lang, t.from(), t.to());
        actual(d, lang);
        d.text(labels.t(lang, "reports.accountCode")).text(labels.t(lang, "fin.account")).money(labels.t(lang, "reports.openingBalance"))
                .money(labels.t(lang, "reports.debit")).money(labels.t(lang, "reports.credit")).money(labels.t(lang, "reports.closingBalance"));
        t.rows().forEach(r -> d.row(r.accountCode(), labels.t(lang, "fin.ledger." + r.accountCode()), r.opening(), r.debit(), r.credit(), r.closing()));
        d.totalRow(labels.t(lang, "fin.total"), null, null, t.debitTotal(), t.creditTotal(), t.closingTotal());
        return d;
    }

    // ---- Cash flow, dues, activity -------------------------------------------------------------------------------

    public ReportDocument cashFlow(String lang, CashFlow c) {
        ReportDocument d = doc(lang, "fin.report.cashflow");
        period(d, lang, c.from(), c.to());
        actual(d, lang);
        d.text(labels.t(lang, "fin.description")).money(labels.t(lang, "fin.amount"));
        d.row(labels.t(lang, "fin.openingCash"), c.opening());
        Map<String, BigDecimal> sections = new java.util.LinkedHashMap<>();
        sections.put("OPERATING", c.operating());
        sections.put("INVESTING", c.investing());
        sections.put("FINANCING", c.financing());
        sections.put("OPENING", c.openingBalances());
        for (Map.Entry<String, BigDecimal> e : sections.entrySet()) {
            d.section(labels.t(lang, "fin.flow." + e.getKey()));
            c.details().stream().filter(x -> x.section().equals(e.getKey())).forEach(x -> d.row(labels.t(lang, "fin.ledger." + x.accountCode()), x.amount()));
            d.totalRow(labels.t(lang, "fin.total"), e.getValue());
        }
        d.totalRow(labels.t(lang, "fin.netChange"), c.netChange());
        d.totalRow(labels.t(lang, "fin.closingCash"), c.closing());
        return d;
    }

    public ReportDocument dues(String lang, Dues dues) {
        ReportDocument d = doc(lang, "fin.report.dues");
        asOf(d, lang, dues.asOf());
        actual(d, lang);
        d.text(labels.t(lang, "reports.side")).text(labels.t(lang, "fin.party")).money(labels.t(lang, "fin.balance")).money(labels.t(lang, "fin.notDue"))
                .money("1–30").money("31–60").money("61–90").money("90+").money(labels.t(lang, "fin.unlinked")).money(labels.t(lang, "fin.advance"));
        for (PartyDues p : dues.receivables()) duesRow(d, labels.t(lang, "fin.theyOwe"), p);
        d.totalRow(labels.t(lang, "fin.receivable"), null, dues.receivableTotal());
        for (PartyDues p : dues.payables()) duesRow(d, labels.t(lang, "fin.weOwe"), p);
        d.totalRow(labels.t(lang, "fin.payable"), null, dues.payableTotal());
        return d;
    }

    private static void duesRow(ReportDocument d, String side, PartyDues p) {
        d.row(side, p.partyName(), p.balance(), p.notDue(), p.days1to30(), p.days31to60(), p.days61to90(), p.over90(), p.unmatched(), p.advance());
    }

    public ReportDocument activity(String lang, Activity a) {
        ReportDocument d = doc(lang, "fin.report.activity");
        period(d, lang, a.from(), a.to());
        actual(d, lang);
        d.text(labels.t(lang, "fin.activity")).money(labels.t(lang, "fin.revenue")).money(labels.t(lang, "fin.costs")).money(labels.t(lang, "fin.contribution"));
        a.rows().forEach(r -> d.row("OVERHEAD".equals(r.kind()) ? labels.t(lang, "fin.overhead") : r.name(), r.revenue(), r.costs(), r.contribution()));
        return d;
    }

    // ---- Party statement -----------------------------------------------------------------------------------------

    public ReportDocument partyStatement(String lang, PartyStatement s) {
        ReportDocument d = doc(lang, "reports.title.partyStatement");
        d.meta(labels.t(lang, "fin.party"), s.name());
        if (s.phone() != null) d.meta(labels.t(lang, "fin.phone"), s.phone());
        period(d, lang, s.from(), s.to());
        actual(d, lang);
        d.meta(labels.t(lang, "reports.balanceSign"), labels.t(lang, "reports.balanceSignHelp"));
        d.column(labels.t(lang, "fin.date"), ReportDocument.Kind.DATE).text(labels.t(lang, "reports.record")).text(labels.t(lang, "fin.recordType"))
                .money(labels.t(lang, "reports.debit")).money(labels.t(lang, "reports.credit")).money(labels.t(lang, "fin.balance"));
        d.row(null, labels.t(lang, "reports.openingBalance"), null, null, null, s.opening());
        for (StatementLine l : s.lines()) {
            d.row(l.date(), l.number(), labels.t(lang, "fin.type." + l.documentType()) + (l.reversal() ? " · " + labels.t(lang, "fin.status.REVERSED") : ""),
                    l.debit(), l.credit(), l.balance());
        }
        d.totalRow(null, labels.t(lang, "reports.closingBalance"), null, null, null, s.closing());
        return d;
    }
}
