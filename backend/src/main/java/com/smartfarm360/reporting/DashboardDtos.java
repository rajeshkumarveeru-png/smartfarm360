package com.smartfarm360.reporting;

import com.smartfarm360.finance.FinanceDtos.FinancialAccount;
import com.smartfarm360.finance.LedgerReportService.CategoryRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Dashboard response (dashboard and reports design, section 3). A section the reader may not see is null. */
public final class DashboardDtos {
    private DashboardDtos() {}

    /** Section A: money at a glance (needs FINANCE_READ). */
    public record Money(LocalDate fiscalYearFrom, BigDecimal cashTotal, List<FinancialAccount> accounts, BigDecimal receivableTotal,
                        BigDecimal overdueReceivables, BigDecimal payableTotal, BigDecimal loanTotal, BigDecimal monthRevenue,
                        BigDecimal monthCosts, BigDecimal monthOperatingProfit, BigDecimal yearRevenue, BigDecimal yearCosts,
                        BigDecimal yearNetProfit) {}

    public record TrendMonth(String month, BigDecimal revenue, BigDecimal costs, BigDecimal operatingProfit) {}

    /** Section B: the last 12 months and this month's largest costs (needs FINANCE_READ). */
    public record Trend(List<TrendMonth> months, List<CategoryRow> topCosts) {}

    /** Actual module-level business results for the current month; only enabled modules with active records are returned. */
    public record BusinessProfit(String module, int activeEntries, BigDecimal revenue, BigDecimal costs, BigDecimal profit) {}

    /**
     * A crop in the field. {@code profitToDate} is actual and needs FINANCE_READ; {@code unsoldEstimate} is a labelled
     * estimate and is never added into a total (H12).
     */
    public record CropTile(long cycleId, String name, String cropName, String status, Long daysSinceSowing, BigDecimal profitToDate,
                           BigDecimal unsoldEstimate) {}

    /**
     * Section C: one "needs attention" item. {@code code} selects the label and the screen it links to; {@code detail} is a
     * month, a date or a name where the item has one.
     */
    public record Attention(String code, int count, BigDecimal amount, String detail) {}

    /** A tile that could not load; the rest of the dashboard still answers (H2). */
    public record TileError(String tile, String code) {}

    public record Dashboard(LocalDate asOf, boolean actual, Money money, Trend trend, List<CropTile> crops, List<BusinessProfit> businessProfits, List<Attention> attention,
                            List<TileError> errors) {}
}
