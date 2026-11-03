package com.smartfarm360.operations;

import com.smartfarm360.crop.CropDtos.Crop;
import com.smartfarm360.crop.CropDtos.Cycle;
import com.smartfarm360.crop.CropService;
import com.smartfarm360.finance.ReportService;
import com.smartfarm360.finance.ReportService.AmountRow;
import com.smartfarm360.finance.ReportService.IncomeStatement;
import com.smartfarm360.inventory.Csv;
import com.smartfarm360.operations.OperationsDtos.*;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Crop cycle reports (crop design, section 7): money from the journal by the cycle's cost object, so the figures always
 * agree with the profit and loss statement; quantities from harvests and stock. Estimates are kept apart (F7).
 */
@Service
public class CropReportService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final JdbcClient jdbc;
    private final CropService crops;
    private final ReportService reports;

    public CropReportService(JdbcClient jdbc, CropService crops, ReportService reports) {
        this.jdbc = jdbc;
        this.crops = crops;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public CycleReport report(TenantContext ctx, long cycleId, LocalDate from, LocalDate to, String basis, BigDecimal price) {
        Cycle c = crops.cycle(ctx, cycleId);
        if (c.costObjectId() == null) throw ApiException.notFound("CYCLE_NOT_FOUND", "Crop cycle not found.");
        // By default: from six months before planned sowing (inputs are often bought early) to today or the closing day
        LocalDate start = from != null ? from : c.plannedSowingDate().minusMonths(6);
        LocalDate end = to != null ? to : latest(LocalDate.now(), c.closedOn(), c.expectedHarvestDate());
        if (price != null && price.signum() < 0) throw ApiException.badRequest("INVALID_PRICE", "Enter a price of zero or more.");
        boolean cash = "CASH".equals(basis);
        IncomeStatement own = reports.incomeStatement(ctx, start, end, null, c.costObjectId(), cash ? "CASH" : "ACCRUAL", true);
        List<AmountRow> allocated = cash ? List.of() : reports.allocatedOverhead(ctx, c.costObjectId(), start, end);
        BigDecimal allocatedTotal = allocated.stream().map(AmountRow::amount).reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCost = own.operatingCostTotal().add(allocatedTotal);
        BigDecimal profit = own.netProfit().subtract(allocatedTotal);
        BigDecimal acres = c.areaAcres();

        List<HarvestTotal> harvested = harvestTotals(ctx.tenantId(), cycleId);
        HarvestTotal main = harvested.isEmpty() ? null : harvested.get(0);
        BigDecimal unsold = main == null ? BigDecimal.ZERO : main.unsold();
        BigDecimal estimate = price == null || main == null ? null : unsold.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal costPerAcre = acres == null ? null : totalCost.divide(acres, 2, RoundingMode.HALF_UP);
        BigDecimal yieldPerAcre = acres == null || main == null ? null : main.quantity().divide(acres, 4, RoundingMode.HALF_UP);
        BigDecimal costPerUnit = main == null || main.quantity().signum() == 0 ? null : totalCost.divide(main.quantity(), 4, RoundingMode.HALF_UP);
        LocalDate firstHarvest = jdbc.sql("select min(harvest_date) from sf_harvest_records where tenant_id = :t and cycle_id = :c and status = 'ACTIVE'")
                .param("t", ctx.tenantId()).param("c", cycleId).query(LocalDate.class).optional().orElse(null);
        Plan plan = new Plan(c.plannedSowingDate(), c.actualSowingDate(), c.expectedHarvestDate(), firstHarvest, c.expectedYield(),
                main == null ? null : main.quantity());
        Map<String, Object> memo = jdbc.sql("""
                        select (select coalesce(sum(cost), 0) from sf_farm_activities where tenant_id = :t and cycle_id = :c and legacy_memo) as cost,
                               (select coalesce(sum(sale_value), 0) from sf_harvest_records where tenant_id = :t and cycle_id = :c and legacy) as value,
                               (select count(*) from sf_harvest_records where tenant_id = :t and cycle_id = :c and legacy) as n""")
                .param("t", ctx.tenantId()).param("c", cycleId).query().singleRow();
        return new CycleReport(c.id(), c.name(), c.cropName(), c.status(), start, end, cash ? "CASH" : "ACCRUAL", true, own.operatingRevenue(),
                own.operatingRevenueTotal(), own.otherIncomeTotal(), own.operatingCosts(), own.operatingCostTotal(), allocated, allocatedTotal,
                own.interest(), own.losses(), totalCost, profit, acres, costPerAcre, harvested, yieldPerAcre, costPerUnit,
                main == null ? null : main.baseUnit(), unsold, price, estimate, plan,
                new Memo((BigDecimal) memo.get("cost"), (BigDecimal) memo.get("value"), ((Number) memo.get("n")).intValue()));
    }

    /** Harvested quantity per produce item (base unit), largest first, and how much of each harvest lot is still in stock. */
    private List<HarvestTotal> harvestTotals(long tenantId, long cycleId) {
        return jdbc.sql("""
                        select h.item_id, i.name, coalesce(i.custom_unit_name, i.base_unit_code) as unit, sum(h.base_quantity) as qty,
                               coalesce((select sum(b.quantity) from sf_stock_balances b where b.tenant_id = :t
                                          and b.lot_id in (select x.lot_id from sf_harvest_records x where x.tenant_id = :t and x.cycle_id = :c
                                                           and x.item_id = h.item_id and x.status = 'ACTIVE' and x.lot_id is not null)), 0) as unsold
                        from sf_harvest_records h join sf_stock_items i on i.id = h.item_id and i.tenant_id = h.tenant_id
                        where h.tenant_id = :t and h.cycle_id = :c and h.status = 'ACTIVE' and not h.legacy
                        group by h.item_id, i.name, i.custom_unit_name, i.base_unit_code order by sum(h.base_quantity) desc""")
                .param("t", tenantId).param("c", cycleId)
                .query((rs, i) -> new HarvestTotal(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5)))
                .list();
    }

    /** Every cycle of a crop side by side (F8): plan against actual, yield, cost per acre and profit. */
    @Transactional(readOnly = true)
    public Comparison comparison(TenantContext ctx, long cropId) {
        Crop crop = crops.crop(ctx, cropId);
        List<ComparisonRow> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            var p = crops.cycles(ctx, null, cropId, null, null, page++, 100);
            for (Cycle c : p.items()) {
                if ("CANCELLED".equals(c.status())) continue;
                CycleReport r = report(ctx, c.id(), null, null, "ACCRUAL", null);
                HarvestTotal main = r.harvested().isEmpty() ? null : r.harvested().get(0);
                rows.add(new ComparisonRow(c.id(), c.name(), c.seasonName(), c.status(), c.areaAcres(), c.plannedSowingDate(), c.actualSowingDate(),
                        c.expectedHarvestDate(), r.plan().firstHarvestDate(), c.expectedYield(), main == null ? null : main.quantity(),
                        main == null ? null : main.baseUnit(), r.yieldPerAcre(), r.revenueTotal(), r.totalCost(), r.costPerAcre(), r.profit()));
            }
            if (page >= p.totalPages()) break;
        }
        return new Comparison(cropId, crop.name(), rows);
    }

    // ---- CSV (stock report format; XLSX/PDF in Phase 7) ----

    @Transactional(readOnly = true)
    public String reportCsv(TenantContext ctx, long cycleId, LocalDate from, LocalDate to, String basis, BigDecimal price) {
        CycleReport r = report(ctx, cycleId, from, to, basis, price);
        StringBuilder csv = new StringBuilder("section,item,amount\r\n");
        csv.append(Csv.row("period", r.from() + " to " + r.to(), r.basis()));
        for (AmountRow a : r.revenue()) csv.append(Csv.row("revenue", label(a), a.amount().toPlainString()));
        csv.append(Csv.row("revenue", "total", r.revenueTotal().toPlainString()));
        for (AmountRow a : r.directCosts()) csv.append(Csv.row("direct cost", label(a), a.amount().toPlainString()));
        csv.append(Csv.row("direct cost", "total", r.directCostTotal().toPlainString()));
        for (AmountRow a : r.allocatedOverhead()) csv.append(Csv.row("allocated overhead", label(a), a.amount().toPlainString()));
        csv.append(Csv.row("allocated overhead", "total", r.allocatedTotal().toPlainString()));
        csv.append(Csv.row("result", "profit", r.profit().toPlainString()));
        csv.append(Csv.row("result", "total cost", r.totalCost().toPlainString()));
        csv.append(Csv.row("result", "area (acres)", plain(r.areaAcres())));
        csv.append(Csv.row("result", "cost per acre", plain(r.costPerAcre())));
        for (HarvestTotal h : r.harvested()) {
            csv.append(Csv.row("harvest", h.itemName() + " (" + h.baseUnit() + ")", plain(h.quantity())));
            csv.append(Csv.row("harvest", h.itemName() + " unsold (" + h.baseUnit() + ")", plain(h.unsold())));
        }
        csv.append(Csv.row("result", "cost per unit" + (r.costUnit() == null ? "" : " (" + r.costUnit() + ")"), plain(r.costPerUnit())));
        if (r.estimateValue() != null) csv.append(Csv.row("estimate (not in accounts)", "unsold produce at " + plain(r.estimatePrice()), plain(r.estimateValue())));
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public String comparisonCsv(TenantContext ctx, long cropId) {
        StringBuilder csv = new StringBuilder("cycle,season,status,area_acres,planned_sowing,actual_sowing,expected_harvest,first_harvest,expected_yield," +
                "harvested,unit,yield_per_acre,revenue,total_cost,cost_per_acre,profit\r\n");
        for (ComparisonRow r : comparison(ctx, cropId).cycles()) {
            csv.append(Csv.row(r.cycleName(), r.seasonName(), r.status(), plain(r.areaAcres()), str(r.plannedSowingDate()), str(r.actualSowingDate()),
                    str(r.expectedHarvestDate()), str(r.firstHarvestDate()), plain(r.expectedYield()), plain(r.harvested()), r.unit(),
                    plain(r.yieldPerAcre()), plain(r.revenue()), plain(r.totalCost()), plain(r.costPerAcre()), plain(r.profit())));
        }
        return csv.toString();
    }

    private static String label(AmountRow a) {
        return a.categoryName() != null ? a.categoryName() : a.categoryCode() != null ? a.categoryCode() : "account " + a.accountCode();
    }

    private static String plain(BigDecimal v) { return v == null ? "" : v.stripTrailingZeros().toPlainString(); }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static LocalDate latest(LocalDate... dates) {
        LocalDate best = null;
        for (LocalDate d : dates) if (d != null && (best == null || d.isAfter(best))) best = d;
        return best;
    }
}
