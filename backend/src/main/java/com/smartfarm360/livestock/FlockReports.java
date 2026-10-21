package com.smartfarm360.livestock;

import com.smartfarm360.finance.ReportService;
import com.smartfarm360.livestock.FlockDtos.Figures;
import com.smartfarm360.livestock.FlockDtos.FlockReport;
import com.smartfarm360.livestock.LivestockDtos.Group;
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
 * Batch and herd figures (poultry, goats and sheep design, section 9): heads from dated movements (J7), eggs from the
 * daily records, feed from stock use to the group, money from the income statement for the group's cost object
 * (principle 1 of the reports design). Every figure is for the chosen period and, for batches, since placement.
 */
@Service
public class FlockReports {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final HerdService herd;
    private final FlockService flocks;
    private final ReportService reports;

    public FlockReports(JdbcClient jdbc, HerdService herd, FlockService flocks, ReportService reports) {
        this.jdbc = jdbc;
        this.herd = herd;
        this.flocks = flocks;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public FlockReport report(TenantContext ctx, long groupId, LocalDate from, LocalDate to) {
        Group g = flocks.counted(ctx, groupId);
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "The date range is too long.");
        Figures period = figures(ctx, g, from, to);
        Figures since = null;
        LocalDate start = g.placedOn() != null ? g.placedOn() : firstMove(ctx.tenantId(), g.id());
        if ("CHICKEN".equals(g.species()) && start != null && !start.isAfter(to)) since = figures(ctx, g, start, to);
        return new FlockReport(g, flocks.heads(ctx.tenantId(), g.id(), to), period, since);
    }

    /** Every counted group's figures for a period (for the reports page and exports). */
    @Transactional(readOnly = true)
    public List<FlockReport> all(TenantContext ctx, LocalDate from, LocalDate to, String species) {
        List<FlockReport> out = new ArrayList<>();
        for (Group g : herd.groups(ctx)) {
            if (!g.counted() || (species != null && !species.equals(g.species()))) continue;
            if (!ctx.enabledModules().contains(HerdService.moduleFor(g.species()))) continue;
            FlockReport r = report(ctx, g.id(), from, to);
            Figures p = r.period();
            if (p.openingHeads() == 0 && p.closingHeads() == 0 && p.arrived() == 0 && p.sold() == 0) continue;
            out.add(r);
        }
        return out;
    }

    private LocalDate firstMove(long tenantId, long groupId) {
        return jdbc.sql("select min(move_date) from sf_head_movements where tenant_id = :t and group_id = :g").param("t", tenantId).param("g", groupId)
                .query(LocalDate.class).optional().orElse(null);
    }

    Figures figures(TenantContext ctx, Group g, LocalDate from, LocalDate to) {
        List<FlockService.Move> moves = flocks.moves(ctx.tenantId(), g.id());
        int opening = moves.stream().filter(m -> m.date().isBefore(from)).mapToInt(FlockService.Move::signed).sum();
        Map<String, Integer> byKind = new HashMap<>();
        for (FlockService.Move m : moves) if (!m.date().isBefore(from) && !m.date().isAfter(to)) byKind.merge(m.kind(), m.heads(), Integer::sum);
        // Head-days: each day counts the heads at the end of that day (deaths on the 15th count from the 15th)
        long headDays = 0;
        int running = opening, idx = 0;
        List<FlockService.Move> inRange = moves.stream().filter(m -> !m.date().isBefore(from) && !m.date().isAfter(to)).toList();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            while (idx < inRange.size() && inRange.get(idx).date().equals(d)) running += inRange.get(idx++).signed();
            headDays += Math.max(running, 0);
        }
        int closing = running;
        Map<String, Object> eggs = jdbc.sql("""
                        select coalesce(sum(eggs_good), 0) as good, coalesce(sum(eggs_cracked), 0) as cracked, coalesce(sum(eggs_broken), 0) as broken
                        from sf_batch_days where tenant_id = :t and group_id = :g and day between :from and :to""")
                .param("t", ctx.tenantId()).param("g", g.id()).param("from", from).param("to", to).query().singleRow();
        int good = ((Number) eggs.get("good")).intValue(), cracked = ((Number) eggs.get("cracked")).intValue(), broken = ((Number) eggs.get("broken")).intValue();
        int laid = good + cracked + broken;
        BigDecimal soldKg = jdbc.sql("""
                        select coalesce(sum(m.kg), 0) from sf_head_movements m left join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.group_id = :g and m.kind = 'SOLD' and m.move_date between :from and :to and (m.document_id is null or d.status = 'POSTED')""")
                .param("t", ctx.tenantId()).param("g", g.id()).param("from", from).param("to", to).query(BigDecimal.class).single().setScale(3, RoundingMode.HALF_UP);
        // Feed: stock used on the group from items of the Animal feed category, in kg (reversals net out)
        BigDecimal feedKg = jdbc.sql("""
                        select coalesce(sum(-m.quantity * u.factor), 0) from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        join sf_units u on u.code = i.base_unit_code and u.dimension = 'MASS'
                        join sf_finance_categories c on c.id = i.expense_category_id and c.code = 'ANIMAL_FEED'
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'ISSUE' and m.movement_date between :from and :to""")
                .param("t", ctx.tenantId()).param("co", g.costObjectId()).param("from", from).param("to", to).query(BigDecimal.class).single().setScale(3, RoundingMode.HALF_UP);
        int arrived = byKind.getOrDefault("PLACED", 0) + byKind.getOrDefault("BOUGHT", 0) + byKind.getOrDefault("MOVED_IN", 0);
        int born = byKind.getOrDefault("BORN", 0), died = byKind.getOrDefault("DIED", 0), culled = byKind.getOrDefault("CULLED", 0);
        int sold = byKind.getOrDefault("SOLD", 0), movedOut = byKind.getOrDefault("MOVED_OUT", 0);
        int exposed = opening + arrived + born;

        BigDecimal revenue = null, costs = null, profit = null, costPerEgg = null, costPerKg = null, costPerHeadMonth = null;
        if (ctx.has(Permission.FINANCE_READ)) {
            ReportService.IncomeStatement s = reports.incomeStatement(ctx, from, to, null, g.costObjectId(), "ACCRUAL");
            revenue = s.operatingRevenueTotal().add(s.otherIncomeTotal());
            costs = s.operatingCostTotal().add(s.interest()).add(s.losses());
            profit = revenue.subtract(costs);
            costPerEgg = laid == 0 ? null : costs.divide(BigDecimal.valueOf(laid), 2, RoundingMode.HALF_UP);
            costPerKg = soldKg.signum() == 0 ? null : costs.divide(soldKg, 2, RoundingMode.HALF_UP);
            costPerHeadMonth = headDays == 0 ? null : costs.multiply(BigDecimal.valueOf(30)).divide(BigDecimal.valueOf(headDays), 2, RoundingMode.HALF_UP);
        }
        return new Figures(from, to, opening, closing, arrived, born, died, culled, sold, movedOut, soldKg, headDays, good, cracked, broken, laid,
                headDays == 0 || laid == 0 ? null : BigDecimal.valueOf(laid).multiply(HUNDRED).divide(BigDecimal.valueOf(headDays), 1, RoundingMode.HALF_UP),
                feedKg, laid == 0 || feedKg.signum() == 0 ? null : feedKg.multiply(BigDecimal.valueOf(12)).divide(BigDecimal.valueOf(laid), 2, RoundingMode.HALF_UP),
                exposed == 0 ? null : BigDecimal.valueOf(died).multiply(HUNDRED).divide(BigDecimal.valueOf(exposed), 2, RoundingMode.HALF_UP),
                sold == 0 || soldKg.signum() == 0 ? null : soldKg.divide(BigDecimal.valueOf(sold), 2, RoundingMode.HALF_UP),
                soldKg.signum() == 0 || feedKg.signum() == 0 ? null : feedKg.divide(soldKg, 2, RoundingMode.HALF_UP),
                revenue, costs, profit, costPerEgg, costPerKg, costPerHeadMonth);
    }

    /** For the dashboard: open poultry batches with no daily record for a date. */
    public int batchesWithoutRecord(TenantContext ctx, LocalDate date) {
        int n = 0;
        for (Group g : herd.groups(ctx)) {
            if (!"CHICKEN".equals(g.species()) || g.closed() || !g.active() || flocks.heads(ctx.tenantId(), g.id(), date) == 0) continue;
            if (g.placedOn() != null && g.placedOn().isAfter(date)) continue;
            boolean recorded = jdbc.sql("select exists (select 1 from sf_batch_days where tenant_id = :t and group_id = :g and day = :d)")
                    .param("t", ctx.tenantId()).param("g", g.id()).param("d", date).query(Boolean.class).single();
            if (!recorded) n++;
        }
        return n;
    }

    /** For the dashboard: batches whose deaths on a day were more than 1% of the birds that morning. */
    public int highMortality(TenantContext ctx, LocalDate from, LocalDate to) {
        int n = 0;
        for (Group g : herd.groups(ctx)) {
            if (!"CHICKEN".equals(g.species())) continue;
            for (Map<String, Object> d : jdbc.sql("select day, deaths from sf_batch_days where tenant_id = :t and group_id = :g and deaths > 0 and day between :from and :to")
                    .param("t", ctx.tenantId()).param("g", g.id()).param("from", from).param("to", to).query().listOfRows()) {
                LocalDate day = ((java.sql.Date) d.get("day")).toLocalDate();
                int morning = flocks.heads(ctx.tenantId(), g.id(), day.minusDays(1));
                if (morning > 0 && ((Number) d.get("deaths")).intValue() * 100 > morning) { n++; break; }
            }
        }
        return n;
    }
}
