package com.smartfarm360.reporting;

import com.smartfarm360.assets.AssetService;
import com.smartfarm360.assets.AssetDtos;
import com.smartfarm360.crop.CropDtos.Cycle;
import com.smartfarm360.crop.CropService;
import com.smartfarm360.finance.LedgerReportService;
import com.smartfarm360.finance.LedgerReportService.MonthlyProfitAndLoss;
import com.smartfarm360.finance.LedgerReportService.MonthlyRow;
import com.smartfarm360.finance.ReportService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockReportService;
import com.smartfarm360.aquaculture.AquaReports;
import com.smartfarm360.aquaculture.WaterService;
import com.smartfarm360.livestock.FlockReports;
import com.smartfarm360.livestock.LivestockReports;
import com.smartfarm360.operations.CropReportService;
import com.smartfarm360.operations.OperationsDtos.CycleReport;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.reporting.DashboardDtos.*;
import com.smartfarm360.workforce.WorkerService;
import com.smartfarm360.workforce.WorkforceDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The dashboard (dashboard and reports design, section 3). It only reads: every figure comes from the service that owns
 * it (principle 1), each tile is shown only with its permission and module (principle 3), and a tile that fails is
 * reported without hiding the others (H2). Figures are computed on request (H4).
 */
@Service
public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    static final int OVERDUE_DAYS = 30, BILLS_DUE_DAYS = 7, DRAFT_AGE_DAYS = 7;
    static final java.time.ZoneId FARM_ZONE = java.time.ZoneId.of("Asia/Kolkata");

    private final ReportService reports;
    private final LedgerReportService ledger;
    private final StockReportService stock;
    private final CropService crops;
    private final CropReportService cropReports;
    private final WorkerService workers;
    private final AssetService assets;
    private final LivestockReports livestock;
    private final FlockReports flocks;
    private final AquaReports aqua;
    private final WaterService water;

    public DashboardService(ReportService reports, LedgerReportService ledger, StockReportService stock, CropService crops,
                            CropReportService cropReports, WorkerService workers, AssetService assets,
                            LivestockReports livestock, FlockReports flocks, AquaReports aqua, WaterService water) {
        this.aqua = aqua;
        this.water = water;
        this.livestock = livestock;
        this.flocks = flocks;
        this.reports = reports;
        this.ledger = ledger;
        this.stock = stock;
        this.crops = crops;
        this.cropReports = cropReports;
        this.workers = workers;
        this.assets = assets;
    }

    public Dashboard dashboard(TenantContext ctx, LocalDate asOf) {
        List<TileError> errors = new ArrayList<>();
        boolean finance = ctx.has(Permission.FINANCE_READ);
        Money money = finance ? tile("money", errors, () -> money(ctx, asOf)) : null;
        Trend trend = finance ? tile("trend", errors, () -> trend(ctx, asOf)) : null;
        List<CropTile> cropTiles = ctx.has(Permission.CROP_READ) && module(ctx, FarmModule.CROP)
                ? tile("crops", errors, () -> crops(ctx, finance)) : null;
        List<Attention> attention = new ArrayList<>();
        if (finance) {
            add(attention, tile("overdueSales", errors, () -> overdueSales(ctx, asOf)));
            add(attention, tile("billsDueSoon", errors, () -> {
                LedgerReportService.DueSoon d = ledger.billsDueSoon(ctx, asOf, BILLS_DUE_DAYS);
                return d.count() == 0 ? null : new Attention("BILLS_DUE_SOON", d.count(), d.amount(), null);
            }));
            add(attention, tile("oldDrafts", errors, () -> {
                int n = ledger.oldDrafts(ctx, asOf, DRAFT_AGE_DAYS);
                return n == 0 ? null : new Attention("OLD_DRAFTS", n, null, null);
            }));
        }
        if (ctx.has(Permission.INVENTORY_READ)) {
            tile("stock", errors, () -> {
                StockDtos.Alerts a = stock.alerts(ctx);
                if (!a.lowStock().isEmpty()) attention.add(new Attention("LOW_STOCK", a.lowStock().size(), null, a.lowStock().get(0).name()));
                if (!a.expiringLots().isEmpty()) attention.add(new Attention("EXPIRING_LOTS", a.expiringLots().size(), null, a.expiringLots().get(0).itemName()));
                StockDtos.Reconciliation r = stock.reconciliation(ctx);
                if (r.difference().signum() != 0) attention.add(new Attention("STOCK_NOT_RECONCILED", 1, r.difference(), null));
                return null;
            });
        }
        if (ctx.has(Permission.WORKFORCE_READ) && module(ctx, FarmModule.WORKFORCE)) {
            boolean pay = ctx.has(Permission.PAYROLL_MANAGE) || finance;
            tile("workforce", errors, () -> {
                if (pay) {
                    WorkforceDtos.Reconciliation r = workers.reconciliation(ctx);
                    if (r.workersOwed().signum() != 0) attention.add(new Attention("WAGES_OWED", 1, r.workersOwed(), null));
                    if (r.workersAdvance().signum() != 0) attention.add(new Attention("ADVANCES_OUT", 1, r.workersAdvance(), null));
                    if (r.workersOwed().compareTo(r.ledgerWagesPayable()) != 0 || r.workersAdvance().compareTo(r.ledgerAdvances()) != 0) {
                        attention.add(new Attention("WORKERS_NOT_RECONCILED", 1, r.workersOwed().subtract(r.ledgerWagesPayable()), null));
                    }
                }
                LocalDate yesterday = asOf.minusDays(1);
                long missing = workers.muster(ctx, yesterday).workers().stream().filter(w -> w.status() == null && !w.locked()).count();
                if (missing > 0) attention.add(new Attention("ATTENDANCE_MISSING", (int) missing, null, yesterday.toString()));
                return null;
            });
        }
        if (ctx.has(Permission.ASSETS_READ) && module(ctx, FarmModule.ASSETS)) {
            tile("assets", errors, () -> {
                long due = assets.plans(ctx, null).stream().filter(p -> p.active() && !"OK".equals(p.status())).count();
                if (due > 0) attention.add(new Attention("MAINTENANCE_DUE", (int) due, null, null));
                List<AssetDtos.UnregisteredLine> unregistered = assets.unregistered(ctx);
                if (!unregistered.isEmpty()) attention.add(new Attention("UNREGISTERED_ASSETS", unregistered.size(),
                        unregistered.stream().map(AssetDtos.UnregisteredLine::amount).reduce(ZERO, BigDecimal::add), null));
                AssetDtos.Reconciliation r = assets.reconciliation(ctx);
                if (r.unexplained().signum() != 0) attention.add(new Attention("ASSETS_NOT_RECONCILED", 1, r.unexplained(), null));
                if (ctx.has(Permission.FINANCE_MANAGE)) {
                    assets.depreciationDue(ctx, asOf).ifPresent(m -> attention.add(new Attention("DEPRECIATION_DUE", 1, null, m)));
                }
                return null;
            });
        }
        boolean dairy = module(ctx, FarmModule.DAIRY), poultry = module(ctx, FarmModule.POULTRY);
        if (ctx.has(Permission.LIVESTOCK_READ) && (dairy || poultry || module(ctx, FarmModule.GOAT_SHEEP))) {
            tile("livestock", errors, () -> {
                boolean today = asOf.equals(java.time.LocalDate.now(FARM_ZONE));
                boolean afterTen = today && java.time.LocalTime.now(FARM_ZONE).isAfter(java.time.LocalTime.of(10, 0));
                if (dairy) {
                    int withheld = livestock.withheld(ctx, asOf).size();
                    if (withheld > 0) attention.add(new Attention("MILK_WITHHELD", withheld, null, null));
                    int due = livestock.breedingDue(ctx, asOf, asOf.plusDays(7)).size();
                    if (due > 0) attention.add(new Attention("BREEDING_DUE", due, null, null));
                    // The morning's milk is expected by 10 a.m. farm time (livestock design, section 8)
                    if (afterTen && !livestock.milkRecorded(ctx, asOf, "MORNING")) attention.add(new Attention("MILK_NOT_RECORDED", 1, null, asOf.toString()));
                }
                int health = livestock.nextDue(ctx, asOf.plusDays(7)).size();
                if (health > 0) attention.add(new Attention("HEALTH_DUE", health, null, null));
                if (poultry) {
                    // Poultry, goats and sheep design, section 9: daily records and mortality
                    int missing = afterTen ? flocks.batchesWithoutRecord(ctx, asOf) : 0;
                    if (missing > 0) attention.add(new Attention("BATCH_RECORD_MISSING", missing, null, asOf.toString()));
                    int high = flocks.highMortality(ctx, asOf.minusDays(1), asOf);
                    if (high > 0) attention.add(new Attention("HIGH_MORTALITY", high, null, null));
                }
                return null;
            });
        }
        if (ctx.has(Permission.AQUACULTURE_READ) && module(ctx, FarmModule.AQUACULTURE)) {
            tile("aquaculture", errors, () -> {
                // Aquaculture design, section 9 (K14): water outside the farmer's limits, the pond record, overdue harvests
                boolean today = asOf.equals(java.time.LocalDate.now(FARM_ZONE));
                boolean afterTen = today && java.time.LocalTime.now(FARM_ZONE).isAfter(java.time.LocalTime.of(10, 0));
                int outside = water.pondsOutOfLimits(ctx, asOf);
                if (outside > 0) attention.add(new Attention("WATER_OUT_OF_LIMITS", outside, null, asOf.toString()));
                int missing = afterTen ? aqua.cyclesWithoutRecord(ctx, asOf) : 0;
                if (missing > 0) attention.add(new Attention("POND_RECORD_MISSING", missing, null, asOf.toString()));
                int late = aqua.cyclesPastPlannedHarvest(ctx, asOf);
                if (late > 0) attention.add(new Attention("HARVEST_OVERDUE", late, null, null));
                return null;
            });
        }
        return new Dashboard(asOf, true, money, trend, cropTiles, attention, errors);
    }

    private static boolean module(TenantContext ctx, FarmModule m) { return ctx.enabledModules().contains(m); }

    private static void add(List<Attention> list, Attention a) { if (a != null) list.add(a); }

    /** Runs one tile; a failure is recorded with its error code and the tile is left empty (H2). */
    private static <T> T tile(String name, List<TileError> errors, Supplier<T> load) {
        try {
            return load.get();
        } catch (ApiException e) {
            errors.add(new TileError(name, e.code()));
        } catch (RuntimeException e) {
            log.warn("Dashboard tile {} failed", name, e);
            errors.add(new TileError(name, "TILE_FAILED"));
        }
        return null;
    }

    private Money money(TenantContext ctx, LocalDate asOf) {
        ReportService.Dues dues = reports.dues(ctx, asOf);
        ReportService.Summary s = reports.summary(ctx, asOf, dues);
        ReportService.Balances b = reports.balances(ctx, asOf, dues);
        return new Money(s.fiscalYearFrom(), s.cashTotal(), b.accounts(), s.receivableTotal(), s.overdueReceivables(), s.payableTotal(), b.loanTotal(),
                s.monthRevenue(), s.monthCosts(), s.monthRevenue().subtract(s.monthCosts()), s.yearRevenue(), s.yearCosts(), s.yearNetProfit());
    }

    private Trend trend(TenantContext ctx, LocalDate asOf) {
        MonthlyProfitAndLoss m = ledger.monthly(ctx, asOf.minusMonths(11).withDayOfMonth(1), asOf, null, "ACCRUAL");
        MonthlyRow revenue = total(m, "OPERATING_REVENUE"), costs = total(m, "OPERATING_COSTS"), profit = total(m, "OPERATING_PROFIT");
        List<TrendMonth> months = new ArrayList<>();
        for (int i = 0; i < m.months().size(); i++) {
            months.add(new TrendMonth(m.months().get(i), revenue.amounts().get(i), costs.amounts().get(i), profit.amounts().get(i)));
        }
        List<LedgerReportService.CategoryRow> top = ledger.categories(ctx, asOf.withDayOfMonth(1), asOf, "COSTS", null, null, "ACCRUAL", null).rows();
        return new Trend(months, top.subList(0, Math.min(5, top.size())));
    }

    private static MonthlyRow total(MonthlyProfitAndLoss m, String name) {
        return m.totals().stream().filter(r -> r.section().equals(name)).findFirst().orElseThrow();
    }

    private List<CropTile> crops(TenantContext ctx, boolean finance) {
        List<CropTile> tiles = new ArrayList<>();
        for (String status : List.of("GROWING", "HARVESTING", "PLANNED")) {
            for (Cycle c : crops.cycles(ctx, null, null, null, status, 0, 50).items()) {
                BigDecimal profit = null, estimate = null;
                if (finance && c.costObjectId() != null) {
                    CycleReport r = cropReports.report(ctx, c.id(), null, null, "ACCRUAL", null);
                    profit = r.profit();
                    estimate = r.estimateValue();
                }
                tiles.add(new CropTile(c.id(), c.name(), c.cropName(), c.status(), c.daysSinceSowing(), profit, estimate));
            }
        }
        return tiles;
    }

    private Attention overdueSales(TenantContext ctx, LocalDate asOf) {
        List<ReportService.PartyDues> late = reports.dues(ctx, asOf).receivables().stream()
                .filter(d -> d.days31to60().add(d.days61to90()).add(d.over90()).signum() > 0).toList();
        if (late.isEmpty()) return null;
        BigDecimal amount = late.stream().map(d -> d.days31to60().add(d.days61to90()).add(d.over90())).reduce(ZERO, BigDecimal::add);
        return new Attention("OVERDUE_SALES", late.size(), amount, late.get(0).partyName());
    }
}
