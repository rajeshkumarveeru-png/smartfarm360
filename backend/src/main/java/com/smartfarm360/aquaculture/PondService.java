package com.smartfarm360.aquaculture;

import com.smartfarm360.aquaculture.AquaDtos.*;
import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.Versions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Ponds and culture cycles (aquaculture design): ponds under a farm (K1), cycles as cost objects from preparation to
 * final harvest (K2), stocking with the seed bill (K3, K4), the daily pond record with feed as one stock use (K5), weight
 * samples (K6) and graded harvests sold at the pond bank (K8). Fish counts are estimates (K3).
 */
@Service
public class PondService {
    static final Map<String, BigDecimal> ACRES_PER_UNIT = Map.of("ACRE", BigDecimal.ONE, "HECTARE", new BigDecimal("2.4710538147"),
            "CENT", new BigDecimal("0.01"), "SQFT", BigDecimal.ONE.divide(new BigDecimal("43560"), 12, RoundingMode.HALF_UP));

    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final MasterDataService master;
    private final DocumentService documents;
    private final StockRecordService stock;
    private final AuditService audit;

    public PondService(JdbcClient jdbc, CostObjectService costObjects, MasterDataService master, DocumentService documents, StockRecordService stock,
                       AuditService audit) {
        this.jdbc = jdbc;
        this.costObjects = costObjects;
        this.master = master;
        this.documents = documents;
        this.stock = stock;
        this.audit = audit;
    }

    static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static void notFuture(LocalDate d) {
        if (d.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
    }

    long systemCategory(String code) {
        return jdbc.sql("select id from sf_finance_categories where tenant_id is null and code = :c").param("c", code).query(Long.class).single();
    }

    /** The species key: the code, and for OTHER its name (K11). */
    static String otherName(String species, String otherName) {
        String name = trim(otherName);
        if ("OTHER".equals(species) && name == null) throw ApiException.badRequest("SPECIES_NAME_REQUIRED", "Enter the name of the species.");
        return "OTHER".equals(species) ? name : null;
    }

    static String key(String species, String otherName) { return species + "|" + (otherName == null ? "" : otherName.toLowerCase(Locale.ROOT)); }

    // ---- Ponds (K1) -------------------------------------------------------------------------------------------------

    private static final String POND_SELECT = """
            select p.id, p.farm_id, f.name as farm_name, p.parcel_id, p.name, p.kind, p.water_area, p.area_unit, p.area_acres, p.depth_m, p.active
            from sf_ponds p join sf_farms f on f.id = p.farm_id and f.tenant_id = p.tenant_id where p.tenant_id = :t""";

    record PondRow(long id, long farmId, String farmName, Long parcelId, String name, String kind, BigDecimal waterArea, String areaUnit,
                   BigDecimal areaAcres, BigDecimal depthM, boolean active) {}

    private static PondRow pondRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PondRow(rs.getLong("id"), rs.getLong("farm_id"), rs.getString("farm_name"), (Long) rs.getObject("parcel_id"), rs.getString("name"),
                rs.getString("kind"), rs.getBigDecimal("water_area"), rs.getString("area_unit"), rs.getBigDecimal("area_acres"), rs.getBigDecimal("depth_m"),
                rs.getBoolean("active"));
    }

    PondRow pondRow(TenantContext ctx, long id) {
        return jdbc.sql(POND_SELECT + " and p.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> pondRow(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("POND_NOT_FOUND", "Pond not found."));
    }

    List<PondRow> pondRows(TenantContext ctx) {
        return jdbc.sql(POND_SELECT + " order by p.active desc, p.name").param("t", ctx.tenantId()).query((rs, i) -> pondRow(rs)).list();
    }

    @Transactional
    public long savePond(TenantContext ctx, Long id, PondRequest r) {
        master.requireFarm(ctx.tenantId(), r.farmId());
        if (r.parcelId() != null && !jdbc.sql("select exists (select 1 from sf_land_parcels where tenant_id = :t and id = :p and farm_id = :f)")
                .param("t", ctx.tenantId()).param("p", r.parcelId()).param("f", r.farmId()).query(Boolean.class).single()) {
            throw ApiException.notFound("PARCEL_NOT_FOUND", "Land parcel not found on this farm.");
        }
        String unit = r.areaUnit() == null ? jdbc.sql("select coalesce((select area_unit from sf_tenant_settings where tenant_id = :t), 'ACRE')")
                .param("t", ctx.tenantId()).query(String.class).single() : r.areaUnit();
        BigDecimal acres = r.waterArea().multiply(ACRES_PER_UNIT.getOrDefault(unit, BigDecimal.ONE)).setScale(4, RoundingMode.HALF_UP);
        if (acres.signum() == 0) throw ApiException.badRequest("AREA_TOO_SMALL", "The water area is too small.");
        boolean taken = jdbc.sql("select exists (select 1 from sf_ponds where tenant_id = :t and lower(name) = lower(:n) and id <> coalesce(:id, 0))")
                .param("t", ctx.tenantId()).param("n", r.name().trim()).param("id", id).query(Boolean.class).single();
        if (taken) throw ApiException.conflict("POND_NAME_TAKEN", "A pond with this name already exists.");
        long pondId;
        if (id == null) {
            pondId = jdbc.sql("""
                            insert into sf_ponds (tenant_id, farm_id, parcel_id, name, kind, water_area, area_unit, area_acres, depth_m, created_by)
                            values (:t, :f, :p, :n, :k, :a, :u, :ac, :d, :by) returning id""")
                    .param("t", ctx.tenantId()).param("f", r.farmId()).param("p", r.parcelId()).param("n", r.name().trim()).param("k", r.kind())
                    .param("a", r.waterArea()).param("u", unit).param("ac", acres).param("d", r.depthM()).param("by", ctx.userId()).query(Long.class).single();
        } else {
            PondRow old = pondRow(ctx, id);
            if (Boolean.FALSE.equals(r.active()) && openCycle(ctx, id).isPresent()) {
                throw ApiException.conflict("POND_HAS_OPEN_CYCLE", "Harvest the pond's crop before making it inactive.");
            }
            jdbc.sql("""
                            update sf_ponds set farm_id = :f, parcel_id = :p, name = :n, kind = :k, water_area = :a, area_unit = :u, area_acres = :ac,
                              depth_m = :d, active = :active where id = :id and tenant_id = :t""")
                    .param("f", r.farmId()).param("p", r.parcelId()).param("n", r.name().trim()).param("k", r.kind()).param("a", r.waterArea()).param("u", unit)
                    .param("ac", acres).param("d", r.depthM()).param("active", r.active() == null ? old.active() : r.active())
                    .param("id", id).param("t", ctx.tenantId()).update();
            pondId = id;
        }
        audit.record(ctx.tenantId(), ctx.userId(), "POND_SAVED", "POND", pondId, Map.of("name", r.name().trim()));
        return pondId;
    }

    // ---- Culture cycles (K2) ------------------------------------------------------------------------------------------

    /** Stockings and harvests count unless their finance record was reversed. */
    private static final String COUNTS = "(%1$s is null or exists (select 1 from sf_finance_documents d where d.id = %1$s and d.tenant_id = c.tenant_id and d.status = 'POSTED'))";

    private static final String CYCLE_SELECT = """
            select c.id, c.pond_id, p.name as pond_name, c.cost_object_id, co.name, c.started_on, c.planned_harvest, c.notes, p.area_acres,
              (select min(s.stocked_on) from sf_pond_stockings s where s.tenant_id = c.tenant_id and s.cycle_id = c.id and %s) as stocked_on,
              (select max(h.harvested_on) from sf_pond_harvests h where h.tenant_id = c.tenant_id and h.cycle_id = c.id and h.kind = 'FINAL' and %s) as harvested_on
            from sf_pond_cycles c
            join sf_ponds p on p.id = c.pond_id and p.tenant_id = c.tenant_id
            join sf_cost_objects co on co.id = c.cost_object_id and co.tenant_id = c.tenant_id
            where c.tenant_id = :t""".formatted(COUNTS.formatted("s.document_id"), COUNTS.formatted("h.sale_document_id"));

    private static Cycle cycle(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDate stocked = rs.getObject("stocked_on", LocalDate.class), harvested = rs.getObject("harvested_on", LocalDate.class);
        return new Cycle(rs.getLong("id"), rs.getLong("pond_id"), rs.getString("pond_name"), rs.getLong("cost_object_id"), rs.getString("name"),
                harvested != null ? "HARVESTED" : stocked != null ? "GROWING" : "PREPARING", rs.getObject("started_on", LocalDate.class), stocked,
                rs.getObject("planned_harvest", LocalDate.class), harvested, rs.getBigDecimal("area_acres"), rs.getString("notes"));
    }

    public Cycle cycle(TenantContext ctx, long id) {
        return jdbc.sql(CYCLE_SELECT + " and c.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> cycle(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("POND_CYCLE_NOT_FOUND", "Culture cycle not found."));
    }

    public List<Cycle> cycles(TenantContext ctx, Long pondId) {
        return jdbc.sql(CYCLE_SELECT + (pondId == null ? "" : " and c.pond_id = :p") + " order by c.started_on desc, c.id desc")
                .param("t", ctx.tenantId()).param("p", pondId).query((rs, i) -> cycle(rs)).list();
    }

    Optional<Cycle> openCycle(TenantContext ctx, long pondId) {
        return cycles(ctx, pondId).stream().filter(c -> !"HARVESTED".equals(c.status())).findFirst();
    }

    /** A cycle that still takes records: not harvested (K2). */
    private Cycle open(TenantContext ctx, long cycleId) {
        Cycle c = cycle(ctx, cycleId);
        if ("HARVESTED".equals(c.status())) throw ApiException.conflict("CYCLE_HARVESTED", "This pond's crop has been harvested. Start a new cycle.");
        return c;
    }

    private static void notBeforeStart(Cycle c, LocalDate d) {
        if (d.isBefore(c.startedOn())) throw ApiException.badRequest("BEFORE_CYCLE_START", "The cycle started after this date.");
    }

    @Transactional
    public Cycle startCycle(TenantContext ctx, long pondId, CycleRequest r) {
        notFuture(r.startedOn());
        PondRow p = pondRow(ctx, pondId);
        if (!p.active()) throw ApiException.conflict("POND_INACTIVE", "This pond is not in use.");
        if (openCycle(ctx, pondId).isPresent()) throw ApiException.conflict("CYCLE_OPEN", "This pond already has a crop. Harvest it before starting another.");
        if (r.plannedHarvest() != null && r.plannedHarvest().isBefore(r.startedOn())) throw ApiException.badRequest("INVALID_DATE_RANGE", "The harvest is planned before the start.");
        String name = trim(r.name()) != null ? trim(r.name()) : p.name() + " – " + r.startedOn().getYear();
        long co = costObjects.createPondCycle(ctx.tenantId(), name, p.farmId(), p.areaAcres(), r.startedOn());
        long id = jdbc.sql("""
                        insert into sf_pond_cycles (tenant_id, pond_id, cost_object_id, started_on, planned_harvest, notes, created_by)
                        values (:t, :p, :co, :s, :ph, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("p", pondId).param("co", co).param("s", r.startedOn()).param("ph", r.plannedHarvest())
                .param("n", trim(r.notes())).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "POND_CYCLE_STARTED", "POND_CYCLE", id, Map.of("pond", p.name(), "name", name));
        return cycle(ctx, id);
    }

    @Transactional
    public Cycle updateCycle(TenantContext ctx, long cycleId, CycleRequest r) {
        Cycle c = cycle(ctx, cycleId);
        LocalDate firstRecord = jdbc.sql("""
                        select min(d) from (select min(stocked_on) d from sf_pond_stockings where tenant_id = :t and cycle_id = :c
                          union all select min(day) from sf_pond_days where tenant_id = :t and cycle_id = :c
                          union all select min(sampled_on) from sf_pond_samples where tenant_id = :t and cycle_id = :c
                          union all select min(harvested_on) from sf_pond_harvests where tenant_id = :t and cycle_id = :c) x""")
                .param("t", ctx.tenantId()).param("c", cycleId).query(LocalDate.class).optional().orElse(null);
        if (firstRecord != null && r.startedOn().isAfter(firstRecord)) throw ApiException.badRequest("BEFORE_CYCLE_START", "Records exist before this start date.");
        jdbc.sql("update sf_pond_cycles set started_on = :s, planned_harvest = :ph, notes = :n where id = :id and tenant_id = :t")
                .param("s", r.startedOn()).param("ph", r.plannedHarvest()).param("n", trim(r.notes())).param("id", cycleId).param("t", ctx.tenantId()).update();
        if (trim(r.name()) != null) costObjects.renamePondCycle(ctx.tenantId(), c.costObjectId(), trim(r.name()));
        audit.record(ctx.tenantId(), ctx.userId(), "POND_CYCLE_SAVED", "POND_CYCLE", cycleId, Map.of("name", trim(r.name()) == null ? c.name() : trim(r.name())));
        return cycle(ctx, cycleId);
    }

    // ---- Stocking (K3, K4) --------------------------------------------------------------------------------------------------

    @Transactional
    public Stocking stock(TenantContext ctx, long cycleId, StockingRequest r, String key) {
        notFuture(r.date());
        Cycle c = open(ctx, cycleId);
        notBeforeStart(c, r.date());
        String other = otherName(r.species(), r.otherName());
        Long doc = null;
        if (r.bill() != null) {
            if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a bill needs permission to record money.");
            SeedBill b = r.bill();
            if (b.partyId() == null && b.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose who sold them or the account it was paid from.");
            PondRow p = pondRow(ctx, c.pondId());
            Document d = documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(r.date(), null, b.partyId(), p.farmId(),
                    b.partyId() == null ? b.financialAccountId() : null,
                    List.of(new LineRequest(systemCategory("FISH_SEED"), r.count() + " " + label(r.species(), other) + " seed, " + c.name(),
                            BigDecimal.valueOf(r.count()), null, null, b.amount(), c.costObjectId(), p.farmId())),
                    null, b.referenceNo(), true, b.paidNow()), key);
            doc = d.summary().id();
        }
        long id = jdbc.sql("""
                        insert into sf_pond_stockings (tenant_id, cycle_id, stocked_on, species, other_name, fish_count, kg, hatchery, document_id, created_by)
                        values (:t, :c, :d, :s, :o, :n, :kg, :h, :doc, :u) returning id""")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", r.date()).param("s", r.species()).param("o", other).param("n", r.count())
                .param("kg", r.kg()).param("h", trim(r.hatchery())).param("doc", doc).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "POND_STOCKED", "POND_CYCLE", cycleId, Map.of("species", r.species(), "count", r.count()));
        return stockings(ctx, cycleId).stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    /** English names for finance descriptions (records are data, not UI text). */
    static String label(String species, String other) {
        if (other != null) return other;
        String s = species.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Transactional(readOnly = true)
    public List<Stocking> stockings(TenantContext ctx, long cycleId) {
        cycle(ctx, cycleId);
        return jdbc.sql("""
                        select s.id, s.stocked_on, s.species, s.other_name, s.fish_count, s.kg, s.hatchery, s.document_id,
                          s.document_id is not null and d.status <> 'POSTED' as reversed
                        from sf_pond_stockings s left join sf_finance_documents d on d.id = s.document_id and d.tenant_id = s.tenant_id
                        where s.tenant_id = :t and s.cycle_id = :c order by s.stocked_on, s.id""")
                .param("t", ctx.tenantId()).param("c", cycleId)
                .query((rs, i) -> new Stocking(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getString(7), (Long) rs.getObject(8), rs.getBoolean(9))).list();
    }

    /** Removes a stocking entered without a bill; one with a bill is undone by reversing the bill. */
    @Transactional
    public void deleteStocking(TenantContext ctx, long id) {
        Map<String, Object> s = jdbc.sql("select cycle_id, document_id from sf_pond_stockings where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query().listOfRows().stream().findFirst().orElseThrow(() -> ApiException.notFound("STOCKING_NOT_FOUND", "Stocking not found."));
        if (s.get("document_id") != null) throw ApiException.conflict("STOCKING_HAS_BILL", "This stocking has a seed bill; reverse the bill instead.");
        long cycleId = ((Number) s.get("cycle_id")).longValue();
        open(ctx, cycleId);
        jdbc.sql("delete from sf_pond_stockings where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        requireDeathsWithinStock(ctx, cycleId);
        audit.record(ctx.tenantId(), ctx.userId(), "POND_STOCKING_DELETED", "POND_CYCLE", cycleId, Map.of("stocking", id));
    }

    // ---- Estimated counts (K3) ------------------------------------------------------------------------------------------

    record Flow(LocalDate date, String species, String otherName, int stocked, BigDecimal stockedKg, int deaths, int harvested, BigDecimal harvestedKg) {}

    /** Every dated change to the fish in a cycle that counts: stockings, deaths seen and counted harvests. */
    List<Flow> flows(long tenantId, long cycleId) {
        return jdbc.sql("""
                        select s.stocked_on, s.species, s.other_name, s.fish_count, s.kg, 0, 0, 0 from sf_pond_stockings s
                        join sf_pond_cycles c on c.id = s.cycle_id and c.tenant_id = s.tenant_id
                        where s.tenant_id = :t and s.cycle_id = :c and %s
                        union all
                        select d.day, x.species, x.other_name, 0, 0, x.fish_count, 0, 0 from sf_pond_deaths x
                        join sf_pond_days d on d.id = x.day_id and d.tenant_id = x.tenant_id
                        where x.tenant_id = :t and d.cycle_id = :c
                        union all
                        select h.harvested_on, l.species, l.other_name, 0, 0, 0, coalesce(l.fish_count, 0), l.kg from sf_pond_harvest_lines l
                        join sf_pond_harvests h on h.id = l.harvest_id and h.tenant_id = l.tenant_id
                        join sf_pond_cycles c on c.id = h.cycle_id and c.tenant_id = h.tenant_id
                        where l.tenant_id = :t and h.cycle_id = :c and %s
                        order by 1""".formatted(COUNTS.formatted("s.document_id"), COUNTS.formatted("h.sale_document_id")))
                .param("t", tenantId).param("c", cycleId)
                .query((rs, i) -> new Flow(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), rs.getInt(6),
                        rs.getInt(7), rs.getBigDecimal(8))).list();
    }

    /** Deaths seen never exceed the fish estimated to be in the pond, species by species and day by day (K3). */
    private void requireDeathsWithinStock(TenantContext ctx, long cycleId) {
        Map<String, Integer> running = new HashMap<>();
        List<Flow> list = flows(ctx.tenantId(), cycleId);
        for (int i = 0; i < list.size(); i++) {
            Flow f = list.get(i);
            running.merge(key(f.species(), f.otherName()), f.stocked() - f.deaths() - f.harvested(), Integer::sum);
            boolean endOfDay = i == list.size() - 1 || !list.get(i + 1).date().equals(f.date());
            if (endOfDay && list.stream().anyMatch(x -> x.date().equals(f.date()) && x.deaths() > 0)) {
                for (Map.Entry<String, Integer> e : running.entrySet()) {
                    if (e.getValue() < 0) throw ApiException.conflict("DEATHS_EXCEED_STOCK", "More deaths than fish of this kind in the pond on " + f.date() + ".");
                }
            }
        }
    }

    // ---- Daily pond record (K5) ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Day day(TenantContext ctx, long cycleId, LocalDate date) {
        cycle(ctx, cycleId);
        return toDay(ctx, cycleId, date);
    }

    private Day toDay(TenantContext ctx, long cycleId, LocalDate date) {
        Optional<Map<String, Object>> row = jdbc.sql("select id, notes, feed_document_id from sf_pond_days where tenant_id = :t and cycle_id = :c and day = :d")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", date).query().listOfRows().stream().findFirst();
        if (row.isEmpty()) return new Day(date, List.of(), List.of(), null, null, Versions.NONE);
        long id = ((Number) row.get().get("id")).longValue();
        List<FeedLine> feeds = jdbc.sql("select item_id, kg from sf_pond_day_feeds where tenant_id = :t and day_id = :d order by id")
                .param("t", ctx.tenantId()).param("d", id).query((rs, i) -> new FeedLine(rs.getLong(1), rs.getBigDecimal(2))).list();
        List<DeathLine> deaths = jdbc.sql("select species, other_name, fish_count, cause from sf_pond_deaths where tenant_id = :t and day_id = :d order by id")
                .param("t", ctx.tenantId()).param("d", id).query((rs, i) -> new DeathLine(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4))).list();
        Object doc = row.get().get("feed_document_id");
        String notes = (String) row.get().get("notes");
        return new Day(date, feeds, deaths, notes, doc == null ? null : ((Number) doc).longValue(), dayVersion(feeds, deaths, notes));
    }

    /** The version of a pond day: a hash of what the user recorded (mobile offline design, N7). */
    private static String dayVersion(List<FeedLine> feeds, List<DeathLine> deaths, String notes) {
        List<Object> parts = new ArrayList<>();
        parts.add(notes);
        feeds.forEach(f -> { parts.add(f.itemId()); parts.add(f.kg()); });
        parts.add("deaths");
        deaths.forEach(d -> { parts.add(d.species()); parts.add(d.otherName()); parts.add(d.count()); parts.add(d.cause()); });
        return Versions.token(parts.toArray());
    }

    @Transactional
    public Day saveDay(TenantContext ctx, long cycleId, LocalDate date, DayRequest r) {
        notFuture(date);
        Cycle c = open(ctx, cycleId);
        notBeforeStart(c, date);
        if (r.baseVersion() != null) {
            // One save of a pond day at a time, so the version check and the write see the same day (N7)
            jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "pondday:" + ctx.tenantId() + ":" + cycleId + ":" + date).query().listOfRows();
            Versions.requireCurrent(r.baseVersion(), toDay(ctx, cycleId, date).version());
        }
        List<FeedLine> feeds = r.feeds() == null ? List.of() : r.feeds();
        List<DeathLine> deaths = r.deaths() == null ? List.of() : r.deaths();
        if (!deaths.isEmpty() && (c.stockedOn() == null || date.isBefore(c.stockedOn()))) throw ApiException.badRequest("NOTHING_STOCKED", "No fish were stocked by this date.");
        Set<String> stocked = new HashSet<>();
        for (Stocking s : stockings(ctx, cycleId)) if (!s.reversed()) stocked.add(key(s.species(), s.otherName()));
        for (DeathLine d : deaths) {
            if (!stocked.contains(key(d.species(), otherName(d.species(), d.otherName())))) {
                throw ApiException.badRequest("SPECIES_NOT_STOCKED", "This species was not stocked in the pond.");
            }
        }
        Optional<Map<String, Object>> existing = jdbc.sql("select id, feed_document_id from sf_pond_days where tenant_id = :t and cycle_id = :c and day = :d")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", date).query().listOfRows().stream().findFirst();
        Day before = existing.isPresent() ? toDay(ctx, cycleId, date) : null;
        long dayId = existing.map(m -> ((Number) m.get("id")).longValue()).orElseGet(() -> jdbc.sql(
                        "insert into sf_pond_days (tenant_id, cycle_id, day, created_by) values (:t, :c, :d, :u) returning id")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", date).param("u", ctx.userId()).query(Long.class).single());
        jdbc.sql("update sf_pond_days set notes = :n, updated_at = now() where id = :id and tenant_id = :t")
                .param("n", trim(r.notes())).param("id", dayId).param("t", ctx.tenantId()).update();
        jdbc.sql("delete from sf_pond_deaths where tenant_id = :t and day_id = :d").param("t", ctx.tenantId()).param("d", dayId).update();
        for (DeathLine d : deaths) {
            jdbc.sql("insert into sf_pond_deaths (tenant_id, day_id, species, other_name, fish_count, cause) values (:t, :d, :s, :o, :n, :c)")
                    .param("t", ctx.tenantId()).param("d", dayId).param("s", d.species()).param("o", otherName(d.species(), d.otherName()))
                    .param("n", d.count()).param("c", trim(d.cause())).update();
        }
        requireDeathsWithinStock(ctx, cycleId);
        // Feed: one stock use to the cycle for the day; a change reverses the earlier use (K5, as J13)
        boolean feedChanged = before == null ? !feeds.isEmpty() : !sameFeeds(before.feeds(), feeds);
        if (feedChanged) {
            Long oldDoc = existing.map(m -> m.get("feed_document_id")).map(o -> ((Number) o).longValue()).orElse(null);
            if (oldDoc != null) documents.reverse(ctx, oldDoc, null);
            jdbc.sql("delete from sf_pond_day_feeds where tenant_id = :t and day_id = :d").param("t", ctx.tenantId()).param("d", dayId).update();
            Long newDoc = null;
            if (!feeds.isEmpty()) {
                PondRow p = pondRow(ctx, c.pondId());
                newDoc = stock.issue(ctx, new StockDtos.IssueRequest(date, null, c.costObjectId(), p.farmId(),
                        feeds.stream().map(f -> new StockDtos.ItemQuantity(f.itemId(), f.kg(), "kg", null, null)).toList(), "Feed, " + c.name()), null)
                        .document().summary().id();
                for (FeedLine f : feeds) {
                    jdbc.sql("insert into sf_pond_day_feeds (tenant_id, day_id, item_id, kg) values (:t, :d, :i, :kg)")
                            .param("t", ctx.tenantId()).param("d", dayId).param("i", f.itemId()).param("kg", f.kg()).update();
                }
            }
            jdbc.sql("update sf_pond_days set feed_document_id = :doc where id = :id and tenant_id = :t")
                    .param("doc", newDoc).param("id", dayId).param("t", ctx.tenantId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "POND_DAY_RECORDED", "POND_CYCLE", cycleId, Map.of("day", date.toString()));
        return toDay(ctx, cycleId, date);
    }

    private static boolean sameFeeds(List<FeedLine> a, List<FeedLine> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).itemId().equals(b.get(i).itemId()) || a.get(i).kg().compareTo(b.get(i).kg()) != 0) return false;
        }
        return true;
    }

    // ---- Samples (K6) -----------------------------------------------------------------------------------------------------------

    @Transactional
    public Sample sample(TenantContext ctx, long cycleId, SampleRequest r) {
        notFuture(r.date());
        Cycle c = open(ctx, cycleId);
        if (c.stockedOn() == null || r.date().isBefore(c.stockedOn())) throw ApiException.badRequest("NOTHING_STOCKED", "No fish were stocked by this date.");
        String other = r.species() == null ? null : otherName(r.species(), r.otherName());
        long id = jdbc.sql("""
                        insert into sf_pond_samples (tenant_id, cycle_id, sampled_on, species, other_name, fish_weighed, kg, created_by)
                        values (:t, :c, :d, :s, :o, :n, :kg, :u) returning id""")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", r.date()).param("s", r.species()).param("o", other)
                .param("n", r.fishWeighed()).param("kg", r.kg()).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "POND_SAMPLE_RECORDED", "POND_CYCLE", cycleId, Map.of("date", r.date().toString()));
        return samples(ctx, cycleId).stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Sample> samples(TenantContext ctx, long cycleId) {
        cycle(ctx, cycleId);
        return jdbc.sql("select id, sampled_on, species, other_name, fish_weighed, kg from sf_pond_samples where tenant_id = :t and cycle_id = :c order by sampled_on desc, id desc")
                .param("t", ctx.tenantId()).param("c", cycleId)
                .query((rs, i) -> new Sample(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBigDecimal(6),
                        rs.getBigDecimal(6).multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(rs.getInt(5)), 1, RoundingMode.HALF_UP))).list();
    }

    @Transactional
    public void deleteSample(TenantContext ctx, long id) {
        Long cycleId = jdbc.sql("select cycle_id from sf_pond_samples where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("SAMPLE_NOT_FOUND", "Sample not found."));
        open(ctx, cycleId);
        jdbc.sql("delete from sf_pond_samples where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "POND_SAMPLE_DELETED", "POND_CYCLE", cycleId, Map.of("sample", id));
    }

    // ---- Harvests (K8) ------------------------------------------------------------------------------------------------------------

    @Transactional
    public Harvest harvest(TenantContext ctx, long cycleId, HarvestRequest r, String key) {
        notFuture(r.date());
        Cycle c = open(ctx, cycleId);
        if (c.stockedOn() == null || r.date().isBefore(c.stockedOn())) throw ApiException.badRequest("NOTHING_STOCKED", "No fish were stocked by this date.");
        Set<String> stocked = new HashSet<>();
        for (Stocking s : stockings(ctx, cycleId)) if (!s.reversed()) stocked.add(key(s.species(), s.otherName()));
        List<HarvestLineRequest> sold = new ArrayList<>();
        for (HarvestLineRequest l : r.lines()) {
            if (!stocked.contains(key(l.species(), otherName(l.species(), l.otherName())))) {
                throw ApiException.badRequest("SPECIES_NOT_STOCKED", "This species was not stocked in the pond.");
            }
            if ("SOLD".equals(l.use())) {
                if (l.rate() == null) throw ApiException.badRequest("RATE_REQUIRED", "Enter the price per kg for fish sold.");
                sold.add(l);
            }
        }
        List<Charge> charges = r.charges() == null ? List.of() : r.charges();
        PondRow p = pondRow(ctx, c.pondId());
        Long saleDoc = null, chargesDoc = null;
        if (!sold.isEmpty()) {
            if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a sale needs permission to record money.");
            if (r.partyId() == null && r.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose the buyer or the account the money came into.");
            long category = systemCategory("FISH_SALE");
            List<LineRequest> lines = sold.stream().map(l -> new LineRequest(category, describe(l), l.kg(), null, null,
                    l.kg().multiply(l.rate()).setScale(2, RoundingMode.HALF_UP), c.costObjectId(), p.farmId())).toList();
            saleDoc = documents.createInvoice(ctx, DocType.SALE, new InvoiceRequest(r.date(), null, r.partyId(), p.farmId(),
                    r.partyId() == null ? r.financialAccountId() : null, lines, trim(r.notes()), r.referenceNo(), true, r.paidNow()), key).summary().id();
            if (!charges.isEmpty()) {
                // The buyer's deductions are a bill to the same buyer (or paid from the same account), so the net is what was received
                List<LineRequest> chargeLines = charges.stream().map(ch -> new LineRequest(systemCategory(ch.category()),
                        trim(ch.description()) != null ? trim(ch.description()) : "Harvest charges, " + c.name(), null, null, null, ch.amount(),
                        c.costObjectId(), p.farmId())).toList();
                chargesDoc = documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(r.date(), null, r.partyId(), p.farmId(),
                        r.partyId() == null ? r.financialAccountId() : null, chargeLines, null, r.referenceNo(), true, null),
                        key == null ? null : key + ":charges").summary().id();
            }
        } else if (!charges.isEmpty()) {
            throw ApiException.badRequest("CHARGES_NEED_SALE", "Charges are taken off a sale; record the fish sold first.");
        }
        long id = jdbc.sql("""
                        insert into sf_pond_harvests (tenant_id, cycle_id, harvested_on, kind, sale_document_id, charges_document_id, notes, created_by)
                        values (:t, :c, :d, :k, :s, :ch, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", r.date()).param("k", r.kind()).param("s", saleDoc).param("ch", chargesDoc)
                .param("n", trim(r.notes())).param("u", ctx.userId()).query(Long.class).single();
        for (HarvestLineRequest l : r.lines()) {
            boolean isSold = "SOLD".equals(l.use());
            jdbc.sql("""
                            insert into sf_pond_harvest_lines (tenant_id, harvest_id, species, other_name, grade, kg, fish_count, use, rate, amount)
                            values (:t, :h, :s, :o, :g, :kg, :n, :use, :rate, :amt)""")
                    .param("t", ctx.tenantId()).param("h", id).param("s", l.species()).param("o", otherName(l.species(), l.otherName()))
                    .param("g", trim(l.grade())).param("kg", l.kg()).param("n", l.count()).param("use", l.use())
                    .param("rate", isSold ? l.rate() : null).param("amt", isSold ? l.kg().multiply(l.rate()).setScale(2, RoundingMode.HALF_UP) : null).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "POND_HARVESTED", "POND_CYCLE", cycleId, Map.of("kind", r.kind(), "date", r.date().toString()));
        return harvests(ctx, cycleId).stream().filter(h -> h.id() == id).findFirst().orElseThrow();
    }

    private static String describe(HarvestLineRequest l) {
        String grade = trim(l.grade());
        return label(l.species(), trim(l.otherName())) + (grade == null ? "" : ", " + grade);
    }

    @Transactional(readOnly = true)
    public List<Harvest> harvests(TenantContext ctx, long cycleId) {
        cycle(ctx, cycleId);
        List<Harvest> out = new ArrayList<>();
        for (Map<String, Object> h : jdbc.sql("""
                        select h.id, h.harvested_on, h.kind, h.sale_document_id, h.charges_document_id, h.notes,
                          h.sale_document_id is not null and d.status <> 'POSTED' as reversed
                        from sf_pond_harvests h left join sf_finance_documents d on d.id = h.sale_document_id and d.tenant_id = h.tenant_id
                        where h.tenant_id = :t and h.cycle_id = :c order by h.harvested_on, h.id""")
                .param("t", ctx.tenantId()).param("c", cycleId).query().listOfRows()) {
            long id = ((Number) h.get("id")).longValue();
            List<HarvestLine> lines = jdbc.sql("select species, other_name, grade, kg, fish_count, use, rate, amount from sf_pond_harvest_lines where tenant_id = :t and harvest_id = :h order by id")
                    .param("t", ctx.tenantId()).param("h", id)
                    .query((rs, i) -> new HarvestLine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), (Integer) rs.getObject(5),
                            rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8))).list();
            out.add(new Harvest(id, ((java.sql.Date) h.get("harvested_on")).toLocalDate(), (String) h.get("kind"), longOf(h.get("sale_document_id")),
                    longOf(h.get("charges_document_id")), (Boolean) h.get("reversed"), lines, (String) h.get("notes")));
        }
        return out;
    }

    private static Long longOf(Object o) { return o == null ? null : ((Number) o).longValue(); }

    /** Removes a harvest without a sale (home use or given away), or one whose sale was reversed. */
    @Transactional
    public void deleteHarvest(TenantContext ctx, long id) {
        Map<String, Object> h = jdbc.sql("""
                        select h.cycle_id, h.sale_document_id, d.status from sf_pond_harvests h
                        left join sf_finance_documents d on d.id = h.sale_document_id and d.tenant_id = h.tenant_id where h.id = :id and h.tenant_id = :t""")
                .param("id", id).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("HARVEST_NOT_FOUND", "Harvest not found."));
        if (h.get("sale_document_id") != null && "POSTED".equals(h.get("status"))) {
            throw ApiException.conflict("HARVEST_HAS_SALE", "This harvest has a sale; reverse the sale instead.");
        }
        long cycleId = ((Number) h.get("cycle_id")).longValue();
        jdbc.sql("delete from sf_pond_harvests where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "POND_HARVEST_DELETED", "POND_CYCLE", cycleId, Map.of("harvest", id));
    }
}
