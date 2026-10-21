package com.smartfarm360.aquaculture;

import com.smartfarm360.aquaculture.AquaDtos.*;
import com.smartfarm360.finance.ReportService;
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
 * Cycle figures (aquaculture design, section 9): fish from stockings, deaths seen and harvests; feed from stock used on the
 * cycle; money from the income statement for the cycle's cost object. Every figure is for the chosen period and for the
 * whole cycle (principle 4). Estimates (K3, K6) are kept apart from actual figures.
 */
@Service
public class AquaReports {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100), THOUSAND = BigDecimal.valueOf(1000);

    private final JdbcClient jdbc;
    private final PondService ponds;
    private final ReportService reports;

    public AquaReports(JdbcClient jdbc, PondService ponds, ReportService reports) {
        this.jdbc = jdbc;
        this.ponds = ponds;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public CycleReport report(TenantContext ctx, long cycleId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "The date range is too long.");
        Cycle c = ponds.cycle(ctx, cycleId);
        List<PondService.Flow> flows = ponds.flows(ctx.tenantId(), cycleId);
        Figures period = figures(ctx, c, flows, from, to);
        Figures whole = c.startedOn().isAfter(to) ? null : figures(ctx, c, flows, c.startedOn(), to);
        return new CycleReport(c, estimates(ctx.tenantId(), c, flows, to), period, whole);
    }

    /** Every cycle with activity in a period (the pond-cycles report). */
    @Transactional(readOnly = true)
    public List<CycleReport> all(TenantContext ctx, LocalDate from, LocalDate to) {
        List<CycleReport> out = new ArrayList<>();
        for (Cycle c : ponds.cycles(ctx, null)) {
            if (c.startedOn().isAfter(to) || (c.harvestedOn() != null && c.harvestedOn().isBefore(from))) continue;
            out.add(report(ctx, c.id(), from, to));
        }
        out.sort(Comparator.comparing((CycleReport r) -> r.cycle().pondName()).thenComparing(r -> r.cycle().startedOn()));
        return out;
    }

    // ---- Estimates (K3, K6) ---------------------------------------------------------------------------------------------------

    record SampleRow(LocalDate date, String key, BigDecimal grams) {}

    private List<SampleRow> sampleRows(long tenantId, long cycleId) {
        return jdbc.sql("select sampled_on, species, other_name, fish_weighed, kg from sf_pond_samples where tenant_id = :t and cycle_id = :c order by sampled_on, id")
                .param("t", tenantId).param("c", cycleId)
                .query((rs, i) -> new SampleRow(rs.getObject(1, LocalDate.class), rs.getString(2) == null ? null : PondService.key(rs.getString(2), rs.getString(3)),
                        rs.getBigDecimal(5).multiply(THOUSAND).divide(BigDecimal.valueOf(rs.getInt(4)), 4, RoundingMode.HALF_UP))).list();
    }

    /** The latest sample of the species, or a later mixed sample, on or before a date. */
    private static Optional<SampleRow> sampleFor(List<SampleRow> samples, String key, LocalDate date) {
        SampleRow best = null;
        for (SampleRow s : samples) {
            if (s.date().isAfter(date) || (s.key() != null && !s.key().equals(key))) continue;
            if (best == null || !s.date().isBefore(best.date())) best = s;
        }
        return Optional.ofNullable(best);
    }

    List<SpeciesEstimate> estimates(long tenantId, Cycle c, List<PondService.Flow> flows, LocalDate date) {
        Map<String, SpeciesEstimate> out = new LinkedHashMap<>();
        List<SampleRow> samples = sampleRows(tenantId, c.id());
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal[]> kgs = new HashMap<>();
        Map<String, String[]> names = new HashMap<>();
        for (PondService.Flow f : flows) {
            if (f.date().isAfter(date)) continue;
            String k = PondService.key(f.species(), f.otherName());
            int[] n = counts.computeIfAbsent(k, x -> new int[3]);
            n[0] += f.stocked();
            n[1] += f.deaths();
            n[2] += f.harvested();
            BigDecimal[] kg = kgs.computeIfAbsent(k, x -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            kg[0] = kg[0].add(f.stockedKg());
            kg[1] = kg[1].add(f.harvestedKg());
            names.putIfAbsent(k, new String[]{f.species(), f.otherName()});
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] n = e.getValue();
            int estimated = Math.max(n[0] - n[1] - n[2], 0);
            Optional<SampleRow> s = sampleFor(samples, e.getKey(), date);
            BigDecimal grams = s.map(SampleRow::grams).orElse(null);
            BigDecimal estKg = grams == null ? null : grams.multiply(BigDecimal.valueOf(estimated)).divide(THOUSAND, 1, RoundingMode.HALF_UP);
            String[] nm = names.get(e.getKey());
            out.put(e.getKey(), new SpeciesEstimate(nm[0], nm[1], n[0], kgs.get(e.getKey())[0], n[1], n[2], kgs.get(e.getKey())[1], estimated,
                    grams == null ? null : grams.setScale(1, RoundingMode.HALF_UP), s.map(SampleRow::date).orElse(null), estKg));
        }
        return new ArrayList<>(out.values());
    }

    private static BigDecimal estimatedKg(List<SpeciesEstimate> list) {
        BigDecimal sum = null;
        for (SpeciesEstimate e : list) if (e.estimatedKg() != null) sum = (sum == null ? BigDecimal.ZERO : sum).add(e.estimatedKg());
        return sum;
    }

    // ---- Figures (K9) ----------------------------------------------------------------------------------------------------------

    private BigDecimal feedKg(long tenantId, long costObjectId, LocalDate from, LocalDate to) {
        // Stock used on the cycle from items of the Animal feed category, in kg (reversals net out); lime and fertiliser are not feed
        return jdbc.sql("""
                        select coalesce(sum(-m.quantity * u.factor), 0) from sf_stock_movements m
                        join sf_stock_items i on i.id = m.item_id and i.tenant_id = m.tenant_id
                        join sf_units u on u.code = i.base_unit_code and u.dimension = 'MASS'
                        join sf_finance_categories c on c.id = i.expense_category_id and c.code = 'ANIMAL_FEED'
                        where m.tenant_id = :t and m.cost_object_id = :co and m.movement_type = 'ISSUE' and m.movement_date between :from and :to""")
                .param("t", tenantId).param("co", costObjectId).param("from", from).param("to", to).query(BigDecimal.class).single().setScale(3, RoundingMode.HALF_UP);
    }

    Figures figures(TenantContext ctx, Cycle c, List<PondService.Flow> flows, LocalDate from, LocalDate to) {
        long t = ctx.tenantId();
        int stocked = 0, deaths = 0, harvestedCount = 0, stockedAll = 0, deathsAll = 0, harvestedAll = 0;
        BigDecimal stockedKg = BigDecimal.ZERO, harvestedKg = BigDecimal.ZERO, stockedKgAll = BigDecimal.ZERO, harvestedKgAll = BigDecimal.ZERO;
        for (PondService.Flow f : flows) {
            if (f.date().isAfter(to)) continue;
            stockedAll += f.stocked(); deathsAll += f.deaths(); harvestedAll += f.harvested();
            stockedKgAll = stockedKgAll.add(f.stockedKg()); harvestedKgAll = harvestedKgAll.add(f.harvestedKg());
            if (f.date().isBefore(from)) continue;
            stocked += f.stocked(); deaths += f.deaths(); harvestedCount += f.harvested();
            stockedKg = stockedKg.add(f.stockedKg()); harvestedKg = harvestedKg.add(f.harvestedKg());
        }
        // Harvest lines without a count make counts unknown (K9)
        Map<String, Object> lines = jdbc.sql("""
                        select count(*) filter (where l.fish_count is null and h.harvested_on between :from and :to) as uncounted_period,
                          count(*) filter (where l.fish_count is null) as uncounted_all,
                          coalesce(sum(l.kg) filter (where l.use = 'SOLD' and h.harvested_on between :from and :to), 0) as sold_kg,
                          coalesce(sum(l.amount) filter (where l.use = 'SOLD' and h.harvested_on between :from and :to), 0) as sold_amount
                        from sf_pond_harvest_lines l join sf_pond_harvests h on h.id = l.harvest_id and h.tenant_id = l.tenant_id
                        left join sf_finance_documents d on d.id = h.sale_document_id and d.tenant_id = h.tenant_id
                        where l.tenant_id = :t and h.cycle_id = :c and h.harvested_on <= :to and (h.sale_document_id is null or d.status = 'POSTED')""")
                .param("t", t).param("c", c.id()).param("from", from).param("to", to).query().singleRow();
        boolean countedPeriod = ((Number) lines.get("uncounted_period")).intValue() == 0;
        boolean countedAll = ((Number) lines.get("uncounted_all")).intValue() == 0;
        BigDecimal soldKg = ((BigDecimal) lines.get("sold_kg")).setScale(3, RoundingMode.HALF_UP);
        BigDecimal soldAmount = (BigDecimal) lines.get("sold_amount");
        BigDecimal feed = feedKg(t, c.costObjectId(), from, to);
        boolean finalInPeriod = c.harvestedOn() != null && !c.harvestedOn().isBefore(from) && !c.harvestedOn().isAfter(to);

        Integer days = null;
        if (c.stockedOn() != null && !c.stockedOn().isAfter(to)) {
            LocalDate end = c.harvestedOn() != null && c.harvestedOn().isBefore(to) ? c.harvestedOn() : to;
            days = (int) ChronoUnit.DAYS.between(c.stockedOn(), end);
        }
        // Survival and unseen losses: the whole cycle, once the final harvest is in the period and every line was counted
        BigDecimal survival = null;
        Integer unseen = null;
        if (finalInPeriod && countedAll && stockedAll > 0) {
            survival = BigDecimal.valueOf(harvestedAll).multiply(HUNDRED).divide(BigDecimal.valueOf(stockedAll), 1, RoundingMode.HALF_UP);
            unseen = stockedAll - deathsAll - harvestedAll;
        }
        // FCR: the whole cycle's feed over the weight gained, once harvested
        BigDecimal fcr = null;
        BigDecimal gained = harvestedKgAll.subtract(stockedKgAll);
        if (finalInPeriod && gained.signum() > 0) {
            BigDecimal feedAll = feedKg(t, c.costObjectId(), c.startedOn(), to);
            if (feedAll.signum() > 0) fcr = feedAll.divide(gained, 2, RoundingMode.HALF_UP);
        }
        BigDecimal acres = c.areaAcres();
        BigDecimal yield = harvestedKg.signum() == 0 ? null : harvestedKg.divide(acres, 1, RoundingMode.HALF_UP);

        BigDecimal revenue = null, costs = null, profit = null, costPerKg = null, profitPerAcre = null, averagePrice = null;
        if (ctx.has(Permission.FINANCE_READ)) {
            ReportService.IncomeStatement s = reports.incomeStatement(ctx, from, to, null, c.costObjectId(), "ACCRUAL");
            revenue = s.operatingRevenueTotal().add(s.otherIncomeTotal());
            costs = s.operatingCostTotal().add(s.interest()).add(s.losses());
            profit = revenue.subtract(costs);
            costPerKg = harvestedKg.signum() == 0 ? null : costs.divide(harvestedKg, 2, RoundingMode.HALF_UP);
            profitPerAcre = profit.divide(acres, 2, RoundingMode.HALF_UP);
            averagePrice = soldKg.signum() == 0 ? null : soldAmount.divide(soldKg, 2, RoundingMode.HALF_UP);
        }

        // Estimates for a growing cycle: biomass now, and FCR to date at the latest sample (K6)
        BigDecimal estKg = null, estFcr = null;
        LocalDate estOn = null;
        if (c.harvestedOn() == null || c.harvestedOn().isAfter(to)) {
            List<SpeciesEstimate> now = estimates(t, c, flows, to);
            estKg = estimatedKg(now);
            estOn = now.stream().map(SpeciesEstimate::sampledOn).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            if (estOn != null) {
                List<SpeciesEstimate> atSample = estimates(t, c, flows, estOn);
                BigDecimal kgAtSample = estimatedKg(atSample);
                BigDecimal stockedBySample = atSample.stream().map(SpeciesEstimate::stockedKg).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal harvestedBySample = atSample.stream().map(SpeciesEstimate::harvestedKg).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal gain = kgAtSample == null ? BigDecimal.ZERO : kgAtSample.add(harvestedBySample).subtract(stockedBySample);
                BigDecimal feedBySample = feedKg(t, c.costObjectId(), c.startedOn(), estOn);
                if (gain.signum() > 0 && feedBySample.signum() > 0) estFcr = feedBySample.divide(gain, 2, RoundingMode.HALF_UP);
            }
        }
        return new Figures(from, to, days, stocked, stockedKg, deaths, countedPeriod ? harvestedCount : null, harvestedKg.setScale(3, RoundingMode.HALF_UP),
                soldKg, feed, survival, unseen, fcr, yield, revenue, costs, profit, costPerKg, profitPerAcre, averagePrice, estKg, estFcr, estOn);
    }

    // ---- Dashboard (K14) ---------------------------------------------------------------------------------------------------------

    /** Growing cycles with no daily pond record for a date. */
    public int cyclesWithoutRecord(TenantContext ctx, LocalDate date) {
        int n = 0;
        for (Cycle c : ponds.cycles(ctx, null)) {
            if (!"GROWING".equals(c.status()) || c.stockedOn().isAfter(date)) continue;
            boolean recorded = jdbc.sql("select exists (select 1 from sf_pond_days where tenant_id = :t and cycle_id = :c and day = :d)")
                    .param("t", ctx.tenantId()).param("c", c.id()).param("d", date).query(Boolean.class).single();
            if (!recorded) n++;
        }
        return n;
    }

    /** Cycles not harvested by their planned date. */
    public int cyclesPastPlannedHarvest(TenantContext ctx, LocalDate date) {
        return (int) ponds.cycles(ctx, null).stream()
                .filter(c -> !"HARVESTED".equals(c.status()) && c.plannedHarvest() != null && c.plannedHarvest().isBefore(date)).count();
    }
}
