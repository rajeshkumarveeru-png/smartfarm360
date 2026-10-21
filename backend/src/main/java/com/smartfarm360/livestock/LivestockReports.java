package com.smartfarm360.livestock;

import com.smartfarm360.finance.ReportService;
import com.smartfarm360.livestock.HerdService.AnimalRow;
import com.smartfarm360.livestock.LivestockDtos.*;
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
 * Livestock reports (livestock design, section 8). Profit figures come from the finance activity report for the group's
 * cost object (principle 1 of the reports design); litres come from the milk sessions and the Milk item's stock movements.
 */
@Service
public class LivestockReports {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    static final int OPEN_LONG_DAYS = 90;

    private final JdbcClient jdbc;
    private final HerdService herd;
    private final EventService events;
    private final MilkService milk;
    private final ReportService reports;

    public LivestockReports(JdbcClient jdbc, HerdService herd, EventService events, MilkService milk, ReportService reports) {
        this.jdbc = jdbc;
        this.herd = herd;
        this.events = events;
        this.milk = milk;
        this.reports = reports;
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "The date range is too long.");
    }

    // ---- Animal card -------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AnimalCard card(TenantContext ctx, long id) {
        Animal a = herd.animal(ctx, id);
        List<Animal> calves = herd.animalRows(ctx.tenantId(), true).stream().filter(c -> Objects.equals(c.damId(), id))
                .map(c -> herd.toAnimal(ctx, c, LocalDate.now())).toList();
        List<DayYield> recent = jdbc.sql("""
                        select s.milk_date, s.session, y.litres from sf_milk_yields y join sf_milk_sessions s on s.id = y.session_id and s.tenant_id = y.tenant_id
                        where y.tenant_id = :t and y.animal_id = :a order by s.milk_date desc, s.session limit 60""")
                .param("t", ctx.tenantId()).param("a", id)
                .query((rs, i) -> new DayYield(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getBigDecimal(3))).list();
        return new AnimalCard(a, herd.moves(ctx, id), events.events(ctx, id, null, null), events.lactations(ctx, id), calves, recent);
    }

    // ---- Milk report -----------------------------------------------------------------------------------------------------

    @Transactional
    public MilkReport milk(TenantContext ctx, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<MilkRow> days = jdbc.sql("""
                        select s.milk_date, s.group_id, co.name,
                               coalesce(sum(case when s.session = 'MORNING' then s.litres end), 0), coalesce(sum(case when s.session = 'EVENING' then s.litres end), 0),
                               sum(s.litres),
                               case when sum(case when s.fat is not null then s.litres end) > 0
                                    then round(sum(s.fat * s.litres) / sum(case when s.fat is not null then s.litres end), 2) end,
                               case when sum(case when s.snf is not null then s.litres end) > 0
                                    then round(sum(s.snf * s.litres) / sum(case when s.snf is not null then s.litres end), 2) end
                        from sf_milk_sessions s join sf_animal_groups g on g.id = s.group_id and g.tenant_id = s.tenant_id
                        join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = s.tenant_id
                        where s.tenant_id = :t and s.milk_date between :from and :to group by s.milk_date, s.group_id, co.name order by s.milk_date, co.name""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to)
                .query((rs, i) -> new MilkRow(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8))).list();
        BigDecimal produced = days.stream().map(MilkRow::total).reduce(ZERO, BigDecimal::add);
        // Where the milk went, from the Milk item's movements (reversals net out)
        Map<String, BigDecimal> out = new HashMap<>();
        jdbc.sql("""
                        select case when m.movement_type = 'SALE' then 'SOLD'
                                    when m.movement_type = 'WASTAGE' then 'DISCARDED'
                                    when m.movement_type = 'ISSUE' and co.kind = 'ANIMAL_GROUP' then 'FED'
                                    when m.movement_type = 'ISSUE' then 'HOME'
                                    when m.movement_type = 'PRODUCE' then 'PRODUCED'
                                    else 'OTHER' end as k, sum(-m.quantity) as q
                        from sf_stock_movements m left join sf_cost_objects co on co.id = m.cost_object_id and co.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.item_id = :i and m.movement_date between :from and :to group by 1""")
                .param("t", ctx.tenantId()).param("i", milk.milkItem(ctx)).param("from", from).param("to", to).query().listOfRows()
                .forEach(r -> out.put((String) r.get("k"), ((BigDecimal) r.get("q")).setScale(2, RoundingMode.HALF_UP)));
        Map<String, Object> quality = jdbc.sql("""
                        select case when sum(case when fat is not null then litres end) > 0
                                    then round(sum(fat * litres) / sum(case when fat is not null then litres end), 2) end as fat,
                               case when sum(case when snf is not null then litres end) > 0
                                    then round(sum(snf * litres) / sum(case when snf is not null then litres end), 2) end as snf
                        from sf_milk_sessions where tenant_id = :t and milk_date between :from and :to""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to).query().singleRow();
        List<AnimalYield> animals = jdbc.sql("""
                        select a.id, a.tag, a.name, sum(y.litres), count(distinct s.milk_date) from sf_milk_yields y
                        join sf_milk_sessions s on s.id = y.session_id and s.tenant_id = y.tenant_id
                        join sf_animals a on a.id = y.animal_id and a.tenant_id = y.tenant_id
                        where y.tenant_id = :t and s.milk_date between :from and :to group by a.id, a.tag, a.name order by sum(y.litres) desc""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to)
                .query((rs, i) -> new AnimalYield(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getInt(5),
                        rs.getInt(5) == 0 ? null : rs.getBigDecimal(4).divide(BigDecimal.valueOf(rs.getInt(5)), 2, RoundingMode.HALF_UP))).list();
        return new MilkReport(from, to, days, produced, out.getOrDefault("SOLD", ZERO), out.getOrDefault("HOME", ZERO), out.getOrDefault("FED", ZERO),
                out.getOrDefault("DISCARDED", ZERO), out.getOrDefault("OTHER", ZERO),
                (BigDecimal) quality.get("fat"), (BigDecimal) quality.get("snf"), animals);
    }

    // ---- Herd profitability (I13) ----------------------------------------------------------------------------------------

    @Transactional
    public HerdReport herd(TenantContext ctx, LocalDate from, LocalDate to) {
        checkRange(from, to);
        long milkItem = milk.milkItem(ctx);
        List<HerdRow> rows = new ArrayList<>();
        for (Group g : herd.groups(ctx)) {
            ReportService.IncomeStatement s = reports.incomeStatement(ctx, from, to, null, g.costObjectId(), "ACCRUAL");
            BigDecimal produced = jdbc.sql("select coalesce(sum(litres), 0) from sf_milk_sessions where tenant_id = :t and group_id = :g and milk_date between :from and :to")
                    .param("t", ctx.tenantId()).param("g", g.id()).param("from", from).param("to", to).query(BigDecimal.class).single().setScale(2);
            Map<String, Object> sold = jdbc.sql("""
                            select coalesce(sum(l.quantity), 0) as litres, coalesce(sum(l.amount), 0) as amount from sf_finance_document_lines l
                            join sf_finance_documents d on d.id = l.document_id and d.tenant_id = l.tenant_id
                            where l.tenant_id = :t and l.item_id = :i and l.cost_object_id = :co and d.doc_type = 'SALE' and d.status = 'POSTED'
                              and d.doc_date between :from and :to""")
                    .param("t", ctx.tenantId()).param("i", milkItem).param("co", g.costObjectId()).param("from", from).param("to", to).query().singleRow();
            BigDecimal litresSold = ((BigDecimal) sold.get("litres")).setScale(2, RoundingMode.HALF_UP), saleAmount = (BigDecimal) sold.get("amount");
            BigDecimal revenue = s.operatingRevenueTotal().add(s.otherIncomeTotal());
            BigDecimal costs = s.operatingCostTotal().add(s.interest()).add(s.losses());
            if (revenue.signum() == 0 && costs.signum() == 0 && produced.signum() == 0) continue;
            List<CostLine> lines = s.operatingCosts().stream()
                    .map(c -> new CostLine(c.accountCode(), c.categoryId(), c.categoryCode(), c.categoryName(), c.amount())).toList();
            rows.add(new HerdRow(g.id(), g.name(), g.purpose(), revenue, lines, costs, revenue.subtract(costs), produced, litresSold,
                    produced.signum() == 0 ? null : costs.divide(produced, 2, RoundingMode.HALF_UP),
                    litresSold.signum() == 0 ? null : saleAmount.divide(litresSold, 2, RoundingMode.HALF_UP)));
        }
        return new HerdReport(from, to, true, rows);
    }

    // ---- Breeding calendar (I10) ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BreedingCalendar breeding(TenantContext ctx, LocalDate from, LocalDate to) {
        checkRange(from, to);
        LocalDate today = LocalDate.now();
        List<DueItem> due = new ArrayList<>(), open = new ArrayList<>();
        for (AnimalRow a : herd.animalRows(ctx.tenantId(), false)) {
            if (!"FEMALE".equals(a.sex())) continue;
            Breeding b = herd.breeding(ctx.tenantId(), a, today);
            if (b.checkDueFrom() != null && "INSEMINATED".equals(b.repro()) && !b.checkDueFrom().isAfter(to)) due.add(new DueItem("PREGNANCY_CHECK", a.id(), a.tag(), a.name(), b.checkDueFrom()));
            if (b.dryOffDue() != null && b.inMilk() && within(b.dryOffDue(), from, to)) due.add(new DueItem("DRY_OFF", a.id(), a.tag(), a.name(), b.dryOffDue()));
            if (b.expectedCalving() != null && within(b.expectedCalving(), from, to)) due.add(new DueItem("CALVING", a.id(), a.tag(), a.name(), b.expectedCalving()));
            if ("OPEN".equals(b.repro()) && b.lastCalving() != null && ChronoUnit.DAYS.between(b.lastCalving(), today) >= OPEN_LONG_DAYS) {
                open.add(new DueItem("OPEN_LONG", a.id(), a.tag(), a.name(), b.lastCalving()));
            }
        }
        due.sort(Comparator.comparing(DueItem::due));
        List<CalvingInterval> intervals = jdbc.sql("""
                        select a.id, a.tag, l1.calving_date, l2.calving_date from sf_lactations l2
                        join sf_lactations l1 on l1.tenant_id = l2.tenant_id and l1.animal_id = l2.animal_id and l1.number = l2.number - 1
                        join sf_animals a on a.id = l2.animal_id and a.tenant_id = l2.tenant_id
                        where l2.tenant_id = :t order by l2.calving_date desc""")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new CalvingInterval(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        ChronoUnit.DAYS.between(rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class)))).list();
        return new BreedingCalendar(from, to, due, open, intervals);
    }

    private static boolean within(LocalDate d, LocalDate from, LocalDate to) { return !d.isBefore(from) && !d.isAfter(to); }

    // ---- Health record (I9) -----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public HealthReport health(TenantContext ctx, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<Event> list = events.events(ctx, null, from, to).stream()
                .filter(e -> List.of("TREATMENT", "VACCINATION", "DEWORMING", "HEALTH_OTHER").contains(e.kind())).toList();
        return new HealthReport(from, to, list, nextDue(ctx, LocalDate.now().plusDays(30)), milk.withheld(ctx.tenantId(), LocalDate.now()));
    }

    /**
     * Health events whose next due date is on or before {@code until} and not yet followed by a later event of the same
     * kind for the same animal or group.
     */
    @Transactional(readOnly = true)
    public List<DueItem> nextDue(TenantContext ctx, LocalDate until) {
        return jdbc.sql("""
                        select e.kind, coalesce(e.animal_id, 0), coalesce(a.tag, co.name), a.name, e.next_due from sf_animal_events e
                        left join sf_animals a on a.id = e.animal_id and a.tenant_id = e.tenant_id
                        left join sf_animal_groups g on g.id = e.group_id and g.tenant_id = e.tenant_id
                        left join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = e.tenant_id
                        where e.tenant_id = :t and e.next_due is not null and e.next_due <= :until
                          and (a.id is null or a.status = 'ACTIVE')
                          and not exists (select 1 from sf_animal_events l where l.tenant_id = e.tenant_id and l.kind = e.kind and l.event_date > e.event_date
                                          and coalesce(l.animal_id, -1) = coalesce(e.animal_id, -1) and coalesce(l.group_id, -1) = coalesce(e.group_id, -1))
                        order by e.next_due""")
                .param("t", ctx.tenantId()).param("until", until)
                .query((rs, i) -> new DueItem(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class))).list();
    }

    /** For the dashboard: pregnancy checks, dry-offs and calvings due on or before {@code until}. */
    public List<DueItem> breedingDue(TenantContext ctx, LocalDate asOf, LocalDate until) {
        return breeding(ctx, asOf, until).due();
    }

    /** For the dashboard: animals whose milk is withheld on a date. */
    public List<WithheldAnimal> withheld(TenantContext ctx, LocalDate date) { return milk.withheld(ctx.tenantId(), date); }

    /** For the dashboard: whether any session was recorded for an active milking group on a date. */
    public boolean milkRecorded(TenantContext ctx, LocalDate date, String session) {
        return jdbc.sql("""
                        select exists (select 1 from sf_milk_sessions s where s.tenant_id = :t and s.milk_date = :d and s.session = :s)
                            or not exists (select 1 from sf_animal_groups g join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = g.tenant_id
                                           where g.tenant_id = :t and g.purpose = 'MILKING' and co.active)""")
                .param("t", ctx.tenantId()).param("d", date).param("s", session).query(Boolean.class).single();
    }
}
