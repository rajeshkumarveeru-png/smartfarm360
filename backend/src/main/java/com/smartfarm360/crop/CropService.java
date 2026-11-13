package com.smartfarm360.crop;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.crop.CropDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Crops, seasons, cultivation cycles and their stages, field records, observations and weather (crop management
 * design, sections 3–5 and 8). Recording activities and harvests that move stock or money happens in the operations
 * module, which uses the helpers at the end of this class; this module never touches finance or stock directly.
 */
@Service
public class CropService {
    /** Area units to acres (1 hectare = 2.4710538147 acres, 1 acre = 100 cents = 43,560 sq ft). */
    static final Map<String, BigDecimal> ACRES_PER_UNIT = Map.of("ACRE", BigDecimal.ONE, "HECTARE", new BigDecimal("2.4710538147"),
            "CENT", new BigDecimal("0.01"), "SQFT", BigDecimal.ONE.divide(new BigDecimal("43560"), 12, RoundingMode.HALF_UP));
    private static final Set<String> SOWING_TYPES = Set.of("SOWING", "TRANSPLANTING");

    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final AuditService audit;

    public CropService(JdbcClient jdbc, CostObjectService costObjects, AuditService audit) {
        this.jdbc = jdbc;
        this.costObjects = costObjects;
        this.audit = audit;
    }

    // ---- Crops, varieties, seasons -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Crop> crops(TenantContext ctx, boolean includeInactive) {
        return jdbc.sql("select id, name, local_name, category, description, produce_item_id, active from sf_crop_masters " +
                        "where tenant_id = :t and (active or :all) order by lower(name)")
                .param("t", ctx.tenantId()).param("all", includeInactive).query((rs, i) -> crop(rs)).list();
    }

    @Transactional(readOnly = true)
    public Crop crop(TenantContext ctx, long id) {
        return jdbc.sql("select id, name, local_name, category, description, produce_item_id, active from sf_crop_masters where id = :id and tenant_id = :t")
                .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> crop(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("CROP_NOT_FOUND", "Crop not found."));
    }

    @Transactional
    public Crop createCrop(TenantContext ctx, CropRequest b) {
        long id;
        try {
            id = jdbc.sql("insert into sf_crop_masters (tenant_id, name, local_name, category, description) values (:t, :n, :l, :c, :d) returning id")
                    .param("t", ctx.tenantId()).param("n", b.name().trim()).param("l", trim(b.localName())).param("c", trim(b.category()))
                    .param("d", trim(b.description())).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CROP_EXISTS", "A crop with this name already exists.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_CREATED", "CROP", id, Map.of("name", b.name().trim()));
        return crop(ctx, id);
    }

    @Transactional
    public Crop updateCrop(TenantContext ctx, long id, CropPatch p) {
        crop(ctx, id);
        try {
            jdbc.sql("""
                            update sf_crop_masters set name = coalesce(:n, name), local_name = coalesce(:l, local_name), category = coalesce(:c, category),
                              description = coalesce(:d, description), active = coalesce(:a, active) where id = :id and tenant_id = :t""")
                    .param("n", p.name() == null ? null : p.name().trim()).param("l", trim(p.localName())).param("c", trim(p.category()))
                    .param("d", trim(p.description())).param("a", p.active()).param("id", id).param("t", ctx.tenantId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CROP_EXISTS", "A crop with this name already exists.");
        }
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_UPDATED", "CROP", id, Map.of());
        return crop(ctx, id);
    }

    @Transactional
    public Variety createVariety(TenantContext ctx, long cropId, VarietyRequest b) {
        crop(ctx, cropId);
        long id;
        try {
            id = jdbc.sql("insert into sf_crop_varieties (tenant_id, crop_id, name, local_name, duration_days, notes) values (:t, :c, :n, :l, :d, :notes) returning id")
                    .param("t", ctx.tenantId()).param("c", cropId).param("n", b.name().trim()).param("l", trim(b.localName()))
                    .param("d", b.durationDays()).param("notes", trim(b.notes())).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("VARIETY_EXISTS", "This crop already has a variety with this name.");
        }
        return varieties(ctx, cropId).stream().filter(v -> v.id() == id).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Variety> varieties(TenantContext ctx, long cropId) {
        crop(ctx, cropId);
        return jdbc.sql("select id, crop_id, name, local_name, duration_days, notes, active from sf_crop_varieties " +
                        "where tenant_id = :t and crop_id = :c and active order by lower(name)")
                .param("t", ctx.tenantId()).param("c", cropId)
                .query((rs, i) -> new Variety(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), (Integer) rs.getObject(5),
                        rs.getString(6), rs.getBoolean(7))).list();
    }

    @Transactional
    public Season createSeason(TenantContext ctx, SeasonRequest b) {
        if (b.endDate().isBefore(b.startDate())) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        long id = jdbc.sql("insert into sf_crop_seasons (tenant_id, name, start_date, end_date, season_type) values (:t, :n, :s, :e, :type) returning id")
                .param("t", ctx.tenantId()).param("n", b.name().trim()).param("s", b.startDate()).param("e", b.endDate())
                .param("type", trim(b.seasonType())).query(Long.class).single();
        return seasons(ctx).stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Season> seasons(TenantContext ctx) {
        return jdbc.sql("select id, name, start_date, end_date, season_type, active from sf_crop_seasons where tenant_id = :t and active order by start_date desc")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new Season(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        rs.getString(5), rs.getBoolean(6))).list();
    }

    // ---- Cycles ----------------------------------------------------------------------------------------------------

    private static final String CYCLE_SELECT = """
            select cc.id, cc.farm_id, f.name as farm_name, cc.land_parcel_id, p.name as parcel_name, p.area as parcel_area, p.area_unit as parcel_unit,
                   cc.crop_id, cm.name as crop_name, cc.variety_id, cv.name as variety_name, cc.season_id, cs.name as season_name, cc.name,
                   cc.planned_sowing_date, cc.actual_sowing_date, cc.expected_harvest_date, cc.closed_on, cc.cultivated_area, cc.area_unit,
                   cc.expected_yield, cc.status, co.id as cost_object_id, co.area_acres, cc.harvest_count
            from sf_cultivation_cycles cc
            join sf_farms f on f.id = cc.farm_id and f.tenant_id = cc.tenant_id
            join sf_land_parcels p on p.id = cc.land_parcel_id and p.tenant_id = cc.tenant_id
            join sf_crop_masters cm on cm.id = cc.crop_id and cm.tenant_id = cc.tenant_id
            left join sf_crop_varieties cv on cv.id = cc.variety_id and cv.tenant_id = cc.tenant_id
            left join sf_crop_seasons cs on cs.id = cc.season_id and cs.tenant_id = cc.tenant_id
            left join sf_cost_objects co on co.crop_cycle_id = cc.id and co.tenant_id = cc.tenant_id""";

    @Transactional(readOnly = true)
    public PageResponse<Cycle> cycles(TenantContext ctx, Long farmId, Long cropId, Long seasonId, String status, int page, int size) {
        PageResponse.check(page, size);
        String where = """
                 where cc.tenant_id = :t and cc.active and (cast(:farm as bigint) is null or cc.farm_id = :farm)
                   and (cast(:crop as bigint) is null or cc.crop_id = :crop) and (cast(:season as bigint) is null or cc.season_id = :season)
                   and (cast(:status as varchar) is null or cc.status = :status)""";
        Map<String, Object> params = new HashMap<>();
        params.put("t", ctx.tenantId());
        params.put("farm", farmId);
        params.put("crop", cropId);
        params.put("season", seasonId);
        params.put("status", status);
        long total = jdbc.sql("select count(*) from sf_cultivation_cycles cc " + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Cycle> items = jdbc.sql(CYCLE_SELECT + " " + where + " order by cc.planned_sowing_date desc, cc.id desc limit :limit offset :offset")
                .params(params).query((rs, i) -> cycle(rs, List.of())).list();
        return PageResponse.of(items.stream().map(c -> withWarnings(ctx.tenantId(), c)).toList(), page, size, total);
    }

    @Transactional(readOnly = true)
    public Cycle cycle(TenantContext ctx, long id) {
        return withWarnings(ctx.tenantId(), jdbc.sql(CYCLE_SELECT + " where cc.id = :id and cc.tenant_id = :t and cc.active")
                .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> cycle(rs, List.of())).optional()
                .orElseThrow(() -> ApiException.notFound("CYCLE_NOT_FOUND", "Crop cycle not found.")));
    }

    // ---- Farmer-managed field action reminders ---------------------------------------------------------------

    private static final String ACTION_PLAN_SELECT = """
            select p.id, p.cycle_id, c.name, cm.name, p.action_type, p.description, p.due_on, p.completed_on, p.next_item_id
            from sf_crop_action_plan_items p
            join sf_cultivation_cycles c on c.id = p.cycle_id and c.tenant_id = p.tenant_id
            join sf_crop_masters cm on cm.id = c.crop_id and cm.tenant_id = c.tenant_id
            """;

    @Transactional(readOnly = true)
    public List<ActionPlanItem> actionPlan(TenantContext ctx, long cycleId) {
        cycle(ctx, cycleId);
        return jdbc.sql(ACTION_PLAN_SELECT + " where p.tenant_id = :t and p.cycle_id = :c order by p.completed_on nulls first, p.due_on, p.id")
                .param("t", ctx.tenantId()).param("c", cycleId).query((rs, i) -> new ActionPlanItem(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class), (Long) rs.getObject(9))).list();
    }

    @Transactional
    public ActionPlanItem addActionPlan(TenantContext ctx, long cycleId, ActionPlanRequest request) {
        requireOpenCycle(ctx.tenantId(), cycleId);
        long id = jdbc.sql("insert into sf_crop_action_plan_items (tenant_id, cycle_id, action_type, description, due_on, created_by) " +
                        "values (:t, :c, :kind, :description, :due, :u) returning id")
                .param("t", ctx.tenantId()).param("c", cycleId).param("kind", request.actionType())
                .param("description", request.description().trim()).param("due", request.dueOn()).param("u", ctx.userId())
                .query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_ACTION_PLANNED", "CROP_CYCLE", cycleId,
                Map.of("actionType", request.actionType(), "dueOn", request.dueOn().toString()));
        return actionPlan(ctx, cycleId).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public List<ActionPlanItem> completeActionPlan(TenantContext ctx, long itemId, CompleteActionRequest request) {
        ActionPlanItem item = jdbc.sql(ACTION_PLAN_SELECT + " where p.id = :id and p.tenant_id = :t for update of p")
                .param("id", itemId).param("t", ctx.tenantId()).query((rs, i) -> new ActionPlanItem(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class), (Long) rs.getObject(9))).optional()
                .orElseThrow(() -> ApiException.notFound("CROP_ACTION_NOT_FOUND", "Field action reminder not found."));
        if (item.completedOn() != null) throw ApiException.conflict("CROP_ACTION_ALREADY_COMPLETED", "This field action is already completed.");
        if (request.nextDue() != null && !request.nextDue().isAfter(request.completedOn()))
            throw ApiException.badRequest("INVALID_NEXT_DUE", "The next action date must be after the completion date.");
        jdbc.sql("update sf_crop_action_plan_items set completed_on = :done, completed_by = :u where id = :id and tenant_id = :t and completed_on is null")
                .param("done", request.completedOn()).param("u", ctx.userId()).param("id", itemId).param("t", ctx.tenantId()).update();
        if (request.nextDue() != null) {
            long nextId = jdbc.sql("insert into sf_crop_action_plan_items (tenant_id, cycle_id, action_type, description, due_on, created_by) " +
                            "values (:t, :c, :kind, :description, :due, :u) returning id")
                    .param("t", ctx.tenantId()).param("c", item.cycleId()).param("kind", item.actionType())
                    .param("description", item.description()).param("due", request.nextDue()).param("u", ctx.userId()).query(Long.class).single();
            jdbc.sql("update sf_crop_action_plan_items set next_item_id = :next where id = :id and tenant_id = :t")
                    .param("next", nextId).param("id", itemId).param("t", ctx.tenantId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_ACTION_COMPLETED", "CROP_CYCLE", item.cycleId(),
                Map.of("actionType", item.actionType(), "completedOn", request.completedOn().toString()));
        return actionPlan(ctx, item.cycleId());
    }

    @Transactional(readOnly = true)
    public List<CropActionDue> actionPlanDue(TenantContext ctx, LocalDate until) {
        return jdbc.sql("""
                        select p.cycle_id, c.name, cm.name, p.action_type, p.description, p.due_on
                        from sf_crop_action_plan_items p
                        join sf_cultivation_cycles c on c.id = p.cycle_id and c.tenant_id = p.tenant_id
                        join sf_crop_masters cm on cm.id = c.crop_id and cm.tenant_id = c.tenant_id
                        where p.tenant_id = :t and p.completed_on is null and p.due_on <= :until
                          and c.active and c.status not in ('CLOSED','CANCELLED')
                        order by p.due_on, p.id
                        """)
                .param("t", ctx.tenantId()).param("until", until)
                .query((rs, i) -> new CropActionDue(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDate.class))).list();
    }

    @Transactional
    public Cycle createCycle(TenantContext ctx, CycleRequest b) {
        long t = ctx.tenantId();
        requireFarm(t, b.farmId());
        Parcel parcel = parcel(t, b.farmId(), b.landParcelId());
        crop(ctx, b.cropId());
        requireVariety(t, b.cropId(), b.varietyId());
        requireSeason(t, b.seasonId());
        if (b.expectedHarvestDate() != null && b.expectedHarvestDate().isBefore(b.plannedSowingDate())) {
            throw ApiException.badRequest("INVALID_HARVEST_DATE", "Expected harvest cannot be before sowing.");
        }
        String unit = or(b.areaUnit(), "ACRE");
        requireAreaFits(parcel, b.cultivatedArea(), unit);
        long id = jdbc.sql("""
                        insert into sf_cultivation_cycles (tenant_id, farm_id, land_parcel_id, crop_id, variety_id, season_id, name, planned_sowing_date,
                          expected_harvest_date, cultivated_area, area_unit, expected_yield, status)
                        values (:t, :farm, :parcel, :crop, :variety, :season, :name, :sowing, :harvest, :area, :unit, :yield, 'PLANNED') returning id""")
                .param("t", t).param("farm", b.farmId()).param("parcel", b.landParcelId()).param("crop", b.cropId()).param("variety", b.varietyId())
                .param("season", b.seasonId()).param("name", b.name().trim()).param("sowing", b.plannedSowingDate())
                .param("harvest", b.expectedHarvestDate()).param("area", b.cultivatedArea()).param("unit", unit).param("yield", b.expectedYield())
                .query(Long.class).single();
        syncCostObject(t, id);
        audit.record(t, ctx.userId(), "CROP_CYCLE_CREATED", "CROP_CYCLE", id, Map.of("name", b.name().trim()));
        return cycle(ctx, id);
    }

    @Transactional
    public Cycle updateCycle(TenantContext ctx, long id, CyclePatch p) {
        long t = ctx.tenantId();
        CycleRow c = lockCycle(t, id);
        requireVariety(t, c.cropId(), p.varietyId());
        requireSeason(t, p.seasonId());
        Cycle current = cycle(ctx, id);
        LocalDate sowing = p.plannedSowingDate() != null ? p.plannedSowingDate() : current.plannedSowingDate();
        LocalDate harvest = p.expectedHarvestDate() != null ? p.expectedHarvestDate() : current.expectedHarvestDate();
        if (harvest != null && harvest.isBefore(sowing)) throw ApiException.badRequest("INVALID_HARVEST_DATE", "Expected harvest cannot be before sowing.");
        BigDecimal area = p.cultivatedArea() != null ? p.cultivatedArea() : current.cultivatedArea();
        String unit = p.areaUnit() != null ? p.areaUnit() : current.areaUnit();
        requireAreaFits(parcel(t, current.farmId(), current.landParcelId()), area, unit);
        jdbc.sql("""
                        update sf_cultivation_cycles set name = coalesce(:name, name), variety_id = coalesce(:variety, variety_id),
                          season_id = coalesce(:season, season_id), planned_sowing_date = :sowing, expected_harvest_date = :harvest,
                          cultivated_area = :area, area_unit = :unit, expected_yield = coalesce(:yield, expected_yield), updated_at = now()
                        where id = :id and tenant_id = :t""")
                .param("name", p.name() == null ? null : p.name().trim()).param("variety", p.varietyId()).param("season", p.seasonId())
                .param("sowing", sowing).param("harvest", harvest).param("area", area).param("unit", unit).param("yield", p.expectedYield())
                .param("id", id).param("t", t).update();
        syncCostObject(t, id);
        audit.record(t, ctx.userId(), "CROP_CYCLE_UPDATED", "CROP_CYCLE", id, Map.of());
        return cycle(ctx, id);
    }

    /** Stage changes by hand (F1). Sowing and harvests also move the stage; see {@link #recordedSowing}, {@link #recordedHarvest}. */
    @Transactional
    public Cycle changeStatus(TenantContext ctx, long id, StatusRequest r) {
        long t = ctx.tenantId();
        CycleRow c = lockCycle(t, id);
        LocalDate date = r.date() != null ? r.date() : LocalDate.now();
        String from = c.status();
        switch (r.action()) {
            case "START" -> {
                if (!"PLANNED".equals(from)) throw invalidStage();
                jdbc.sql("update sf_cultivation_cycles set status = 'GROWING', actual_sowing_date = coalesce(actual_sowing_date, :d), updated_at = now() where id = :id")
                        .param("d", date).param("id", id).update();
            }
            case "CLOSE" -> {
                if (!Set.of("PLANNED", "GROWING", "HARVESTING").contains(from)) throw invalidStage();
                LocalDate started = c.actualSowingDate() != null ? c.actualSowingDate() : c.plannedSowingDate();
                if (date.isBefore(started) && !"PLANNED".equals(from)) {
                    throw ApiException.badRequest("INVALID_CLOSE_DATE", "A cycle cannot close before it was sown.");
                }
                jdbc.sql("update sf_cultivation_cycles set status = 'CLOSED', closed_on = :d, updated_at = now() where id = :id")
                        .param("d", date).param("id", id).update();
            }
            case "REOPEN" -> {
                if (!"CLOSED".equals(from)) throw invalidStage();
                if (!ctx.hasRole("TENANT_OWNER") && !ctx.hasRole("FARM_ADMIN")) {
                    throw ApiException.forbidden("OWNER_OR_ADMIN_REQUIRED", "Only an owner or farm administrator can reopen a closed cycle.");
                }
                String to = c.harvestCount() > 0 ? "HARVESTING" : c.actualSowingDate() != null ? "GROWING" : "PLANNED";
                jdbc.sql("update sf_cultivation_cycles set status = :s, closed_on = null, updated_at = now() where id = :id")
                        .param("s", to).param("id", id).update();
            }
            default -> {
                if (!Set.of("PLANNED", "GROWING").contains(from)) throw invalidStage();
                boolean hasRecords = jdbc.sql("""
                                select exists (select 1 from sf_farm_activities where cycle_id = :id and tenant_id = :t and status = 'ACTIVE' and deleted_at is null)
                                    or exists (select 1 from sf_harvest_records where cycle_id = :id and tenant_id = :t and status = 'ACTIVE')""")
                        .param("id", id).param("t", t).query(Boolean.class).single();
                if (hasRecords) throw ApiException.conflict("CYCLE_HAS_RECORDS", "This cycle has field records; close it instead of cancelling.");
                jdbc.sql("update sf_cultivation_cycles set status = 'CANCELLED', updated_at = now() where id = :id").param("id", id).update();
            }
        }
        syncCostObject(t, id);
        audit.record(t, ctx.userId(), "CROP_CYCLE_STATUS_CHANGED", "CROP_CYCLE", id, Map.of("action", r.action(), "date", date.toString()));
        return cycle(ctx, id);
    }

    private static ApiException invalidStage() {
        return ApiException.conflict("INVALID_CYCLE_STAGE", "This change is not possible at the cycle's current stage.");
    }

    // ---- Activities and harvests: reading and notes-only changes ----------------------------------------------------

    private static final String ACTIVITY_SELECT = """
            select a.id, a.cycle_id, c.name as cycle_name, a.activity_type, a.activity_date, a.notes, a.workers, a.hours, a.input_name, a.quantity,
                   a.quantity_unit, a.cost, a.legacy_memo, a.status, a.stock_document_id, a.bill_document_id, a.created_at
            from sf_farm_activities a join sf_cultivation_cycles c on c.id = a.cycle_id and c.tenant_id = a.tenant_id""";

    @Transactional(readOnly = true)
    public PageResponse<Activity> activities(TenantContext ctx, Long cycleId, int page, int size) {
        PageResponse.check(page, size);
        if (cycleId != null) cycle(ctx, cycleId);
        String where = " where a.tenant_id = :t and a.deleted_at is null and (cast(:c as bigint) is null or a.cycle_id = :c)";
        long total = jdbc.sql("select count(*) from sf_farm_activities a" + where).param("t", ctx.tenantId()).param("c", cycleId).query(Long.class).single();
        List<Activity> items = jdbc.sql(ACTIVITY_SELECT + where + " order by a.activity_date desc, a.id desc limit :limit offset :offset")
                .param("t", ctx.tenantId()).param("c", cycleId).param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> activity(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Activity activity(TenantContext ctx, long id) {
        return jdbc.sql(ACTIVITY_SELECT + " where a.id = :id and a.tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> activity(rs)).optional().orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "Activity not found."));
    }

    /** Only notes-only activities change in place; activities that moved stock or money are reversed (principle 4). */
    @Transactional
    public Activity updateActivity(TenantContext ctx, long id, ActivityPatch p) {
        Activity a = requireNotesOnly(ctx, id);
        if (p.activityDate() != null) requireOpenCycle(ctx.tenantId(), a.cycleId());
        jdbc.sql("""
                        update sf_farm_activities set activity_date = coalesce(:d, activity_date), notes = coalesce(:n, notes),
                          workers = coalesce(:w, workers), hours = coalesce(:h, hours), updated_at = now(), updated_by = :u where id = :id and tenant_id = :t""")
                .param("d", p.activityDate()).param("n", trim(p.notes())).param("w", p.workers()).param("h", p.hours()).param("u", ctx.userId())
                .param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_ACTIVITY_UPDATED", "CROP_ACTIVITY", id, Map.of("cycleId", a.cycleId()));
        return activity(ctx, id);
    }

    private Activity requireNotesOnly(TenantContext ctx, long id) {
        Activity a = activity(ctx, id);
        if (a.stockDocumentId() != null || a.billDocumentId() != null || !"ACTIVE".equals(a.status())) {
            throw ApiException.conflict("ACTIVITY_HAS_RECORDS", "This activity moved stock or money; reverse it instead.");
        }
        return a;
    }

    private static final String HARVEST_SELECT = """
            select h.id, h.cycle_id, c.name as cycle_name, h.harvest_date, h.quantity, h.quantity_unit, h.item_id, h.base_quantity, h.lot_id,
                   l.lot_no, h.grade, h.moisture_pct, h.sale_value, h.legacy, h.status, h.document_id, h.notes
            from sf_harvest_records h join sf_cultivation_cycles c on c.id = h.cycle_id and c.tenant_id = h.tenant_id
            left join sf_stock_lots l on l.id = h.lot_id and l.tenant_id = h.tenant_id""";

    @Transactional(readOnly = true)
    public PageResponse<Harvest> harvests(TenantContext ctx, Long cycleId, int page, int size) {
        PageResponse.check(page, size);
        if (cycleId != null) cycle(ctx, cycleId);
        String where = " where h.tenant_id = :t and (cast(:c as bigint) is null or h.cycle_id = :c)";
        long total = jdbc.sql("select count(*) from sf_harvest_records h" + where).param("t", ctx.tenantId()).param("c", cycleId).query(Long.class).single();
        List<Harvest> items = jdbc.sql(HARVEST_SELECT + where + " order by h.harvest_date desc, h.id desc limit :limit offset :offset")
                .param("t", ctx.tenantId()).param("c", cycleId).param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> harvest(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Harvest harvest(TenantContext ctx, long id) {
        return jdbc.sql(HARVEST_SELECT + " where h.id = :id and h.tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> harvest(rs)).optional().orElseThrow(() -> ApiException.notFound("HARVEST_NOT_FOUND", "Harvest not found."));
    }

    // ---- Observations (F9) -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Observation> observations(TenantContext ctx, long cycleId) {
        cycle(ctx, cycleId);
        return jdbc.sql("""
                        select id, cycle_id, observed_on, kind, severity, field_part, notes, action_taken, activity_id, created_at
                        from sf_crop_observations where tenant_id = :t and cycle_id = :c order by observed_on desc, id desc""")
                .param("t", ctx.tenantId()).param("c", cycleId).query((rs, i) -> observation(rs)).list();
    }

    @Transactional
    public Observation createObservation(TenantContext ctx, long cycleId, ObservationRequest r) {
        cycle(ctx, cycleId);
        requireActivityOfCycle(ctx, cycleId, r.activityId());
        long id = jdbc.sql("""
                        insert into sf_crop_observations (tenant_id, cycle_id, observed_on, kind, severity, field_part, notes, action_taken, activity_id, created_by)
                        values (:t, :c, :d, :k, :s, :p, :n, :a, :act, :u) returning id""")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", r.observedOn()).param("k", r.kind()).param("s", r.severity())
                .param("p", trim(r.fieldPart())).param("n", trim(r.notes())).param("a", trim(r.actionTaken())).param("act", r.activityId())
                .param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_OBSERVATION_SAVED", "CROP_OBSERVATION", id, Map.of("cycleId", cycleId, "kind", r.kind()));
        return observation(ctx, id);
    }

    @Transactional
    public Observation updateObservation(TenantContext ctx, long id, ObservationRequest r) {
        Observation o = observation(ctx, id);
        requireActivityOfCycle(ctx, o.cycleId(), r.activityId());
        jdbc.sql("""
                        update sf_crop_observations set observed_on = :d, kind = :k, severity = :s, field_part = :p, notes = :n, action_taken = :a,
                          activity_id = :act, updated_at = now() where id = :id and tenant_id = :t""")
                .param("d", r.observedOn()).param("k", r.kind()).param("s", r.severity()).param("p", trim(r.fieldPart())).param("n", trim(r.notes()))
                .param("a", trim(r.actionTaken())).param("act", r.activityId()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_OBSERVATION_SAVED", "CROP_OBSERVATION", id, Map.of("cycleId", o.cycleId(), "kind", r.kind()));
        return observation(ctx, id);
    }

    @Transactional
    public void deleteObservation(TenantContext ctx, long id) {
        Observation o = observation(ctx, id);
        jdbc.sql("delete from sf_crop_observations where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_OBSERVATION_DELETED", "CROP_OBSERVATION", id, Map.of("cycleId", o.cycleId(), "kind", o.kind()));
    }

    private Observation observation(TenantContext ctx, long id) {
        return jdbc.sql("""
                        select id, cycle_id, observed_on, kind, severity, field_part, notes, action_taken, activity_id, created_at
                        from sf_crop_observations where id = :id and tenant_id = :t""")
                .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> observation(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("OBSERVATION_NOT_FOUND", "Observation not found."));
    }

    private void requireActivityOfCycle(TenantContext ctx, long cycleId, Long activityId) {
        if (activityId == null) return;
        if (activity(ctx, activityId).cycleId() != cycleId) {
            throw ApiException.badRequest("ACTIVITY_NOT_IN_CYCLE", "Choose an activity of this cycle.");
        }
    }

    // ---- Weather (F10) ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Weather weather(TenantContext ctx, long farmId, LocalDate from, LocalDate to) {
        requireFarm(ctx.tenantId(), farmId);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        List<WeatherNote> days = jdbc.sql("""
                        select id, farm_id, note_date, rainfall_mm, notes from sf_weather_notes
                        where tenant_id = :t and farm_id = :f and note_date between :from and :to order by note_date""")
                .param("t", ctx.tenantId()).param("f", farmId).param("from", from).param("to", to)
                .query((rs, i) -> new WeatherNote(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4), rs.getString(5)))
                .list();
        BigDecimal rain = days.stream().map(WeatherNote::rainfallMm).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Weather(farmId, from, to, rain, days);
    }

    @Transactional
    public WeatherNote saveWeather(TenantContext ctx, long farmId, LocalDate date, WeatherRequest r) {
        requireFarm(ctx.tenantId(), farmId);
        if (r.rainfallMm() == null && trim(r.notes()) == null) throw ApiException.badRequest("WEATHER_EMPTY", "Enter rainfall or a note.");
        if (date.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "Weather can only be recorded for today or earlier.");
        long id = jdbc.sql("""
                        insert into sf_weather_notes (tenant_id, farm_id, note_date, rainfall_mm, notes, created_by) values (:t, :f, :d, :r, :n, :u)
                        on conflict (tenant_id, farm_id, note_date) do update set rainfall_mm = excluded.rainfall_mm, notes = excluded.notes, updated_at = now()
                        returning id""")
                .param("t", ctx.tenantId()).param("f", farmId).param("d", date).param("r", r.rainfallMm()).param("n", trim(r.notes()))
                .param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "WEATHER_NOTE_SAVED", "FARM", farmId, Map.of("date", date.toString()));
        return new WeatherNote(id, farmId, date, r.rainfallMm(), trim(r.notes()));
    }

    @Transactional
    public void deleteWeather(TenantContext ctx, long farmId, LocalDate date) {
        requireFarm(ctx.tenantId(), farmId);
        int n = jdbc.sql("delete from sf_weather_notes where tenant_id = :t and farm_id = :f and note_date = :d")
                .param("t", ctx.tenantId()).param("f", farmId).param("d", date).update();
        if (n == 0) throw ApiException.notFound("WEATHER_NOTE_NOT_FOUND", "No weather note for that day.");
        audit.record(ctx.tenantId(), ctx.userId(), "WEATHER_NOTE_DELETED", "FARM", farmId, Map.of("date", date.toString()));
    }

    // ---- For the operations module ------------------------------------------------------------------------------

    /** What recording a field activity or harvest needs to know about a cycle (locked for the transaction). */
    public record CycleRow(long id, long cropId, long farmId, String name, String status, LocalDate plannedSowingDate,
                           LocalDate actualSowingDate, int harvestCount) {}

    public CycleRow lockCycle(long tenantId, long id) {
        return jdbc.sql("""
                        select id, crop_id, farm_id, name, status, planned_sowing_date, actual_sowing_date, harvest_count
                        from sf_cultivation_cycles where id = :id and tenant_id = :t and active for update""")
                .param("id", id).param("t", tenantId)
                .query((rs, i) -> new CycleRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getInt(8)))
                .optional().orElseThrow(() -> ApiException.notFound("CYCLE_NOT_FOUND", "Crop cycle not found."));
    }

    /** Closed and cancelled cycles take no new field records (F1); money can still be assigned through finance. */
    public CycleRow requireOpenCycle(long tenantId, long id) {
        CycleRow c = lockCycle(tenantId, id);
        if ("CLOSED".equals(c.status()) || "CANCELLED".equals(c.status())) {
            throw ApiException.conflict("CYCLE_CLOSED", "This cycle is closed. Reopen it to add field records.");
        }
        return c;
    }

    /** Stores a new activity; the operations module links the stock and money records it created. */
    public long insertActivity(TenantContext ctx, long cycleId, String type, LocalDate date, String notes, Integer workers, BigDecimal hours,
                               String key, String requestHash) {
        long id = jdbc.sql("""
                        insert into sf_farm_activities (tenant_id, cycle_id, activity_type, activity_date, notes, workers, hours, cost, idempotency_key,
                          request_hash, created_by) values (:t, :c, :type, :d, :n, :w, :h, 0, :key, :hash, :u) returning id""")
                .param("t", ctx.tenantId()).param("c", cycleId).param("type", type).param("d", date).param("n", trim(notes)).param("w", workers)
                .param("h", hours).param("key", key).param("hash", requestHash).param("u", ctx.userId()).query(Long.class).single();
        if (SOWING_TYPES.contains(type)) recordedSowing(ctx.tenantId(), cycleId, date);
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_ACTIVITY_RECORDED", "CROP_ACTIVITY", id, Map.of("cycleId", cycleId, "type", type));
        return id;
    }

    public void linkActivity(long tenantId, long activityId, Long stockDocumentId, Long billDocumentId) {
        jdbc.sql("update sf_farm_activities set stock_document_id = :s, bill_document_id = :b where id = :id and tenant_id = :t")
                .param("s", stockDocumentId).param("b", billDocumentId).param("id", activityId).param("t", tenantId).update();
    }

    public void markActivityReversed(TenantContext ctx, long activityId) {
        jdbc.sql("update sf_farm_activities set status = 'REVERSED', updated_at = now(), updated_by = :u where id = :id and tenant_id = :t")
                .param("u", ctx.userId()).param("id", activityId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_ACTIVITY_REVERSED", "CROP_ACTIVITY", activityId, Map.of());
    }

    /** An earlier request with this key: its id and request hash. */
    public Optional<Map.Entry<Long, String>> activityByKey(long tenantId, String key) {
        return jdbc.sql("select id, request_hash from sf_farm_activities where tenant_id = :t and idempotency_key = :k").param("t", tenantId).param("k", key)
                .query((rs, i) -> Map.entry(rs.getLong(1), Objects.toString(rs.getString(2), ""))).optional();
    }

    public Optional<Map.Entry<Long, String>> harvestByKey(long tenantId, String key) {
        return jdbc.sql("select id, request_hash from sf_harvest_records where tenant_id = :t and idempotency_key = :k").param("t", tenantId).param("k", key)
                .query((rs, i) -> Map.entry(rs.getLong(1), Objects.toString(rs.getString(2), ""))).optional();
    }

    /** A sowing or transplanting record moves a planned cycle to Growing and sets the actual sowing date. */
    void recordedSowing(long tenantId, long cycleId, LocalDate date) {
        jdbc.sql("""
                        update sf_cultivation_cycles set actual_sowing_date = coalesce(actual_sowing_date, :d),
                          status = case when status = 'PLANNED' then 'GROWING' else status end, updated_at = now() where id = :id and tenant_id = :t""")
                .param("d", date).param("id", cycleId).param("t", tenantId).update();
        syncCostObject(tenantId, cycleId);
    }

    /** The first harvest moves the cycle to Harvesting; returns the harvest's sequence number for its lot name. */
    public int recordedHarvest(long tenantId, long cycleId) {
        int n = jdbc.sql("""
                        update sf_cultivation_cycles set harvest_count = harvest_count + 1,
                          status = case when status in ('PLANNED', 'GROWING') then 'HARVESTING' else status end, updated_at = now()
                        where id = :id and tenant_id = :t returning harvest_count""")
                .param("id", cycleId).param("t", tenantId).query(Integer.class).single();
        syncCostObject(tenantId, cycleId);
        return n;
    }

    public long insertHarvest(TenantContext ctx, long cycleId, LocalDate date, BigDecimal quantity, String unit, long itemId, BigDecimal baseQuantity,
                              Long lotId, long documentId, String grade, BigDecimal moisture, String notes, String key, String requestHash) {
        long id = jdbc.sql("""
                        insert into sf_harvest_records (tenant_id, cycle_id, harvest_date, quantity, quantity_unit, sale_value, notes, item_id, base_quantity,
                          lot_id, document_id, grade, moisture_pct, idempotency_key, request_hash, created_by)
                        values (:t, :c, :d, :q, :unit, 0, :n, :item, :base, :lot, :doc, :g, :m, :key, :hash, :u) returning id""")
                .param("t", ctx.tenantId()).param("c", cycleId).param("d", date).param("q", quantity).param("unit", unit).param("n", trim(notes))
                .param("item", itemId).param("base", baseQuantity).param("lot", lotId).param("doc", documentId).param("g", trim(grade))
                .param("m", moisture).param("key", key).param("hash", requestHash).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "HARVEST_RECORDED", "HARVEST", id, Map.of("cycleId", cycleId, "quantity", quantity, "unit", unit));
        return id;
    }

    public void markHarvestReversed(TenantContext ctx, long harvestId, long cycleId) {
        jdbc.sql("update sf_harvest_records set status = 'REVERSED' where id = :id and tenant_id = :t").param("id", harvestId).param("t", ctx.tenantId()).update();
        jdbc.sql("""
                        update sf_cultivation_cycles set status = 'GROWING', updated_at = now() where id = :id and tenant_id = :t and status = 'HARVESTING'
                          and not exists (select 1 from sf_harvest_records where cycle_id = :id and tenant_id = :t and status = 'ACTIVE')""")
                .param("id", cycleId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "HARVEST_REVERSED", "HARVEST", harvestId, Map.of("cycleId", cycleId));
    }

    public void setProduceItem(TenantContext ctx, long cropId, long itemId) {
        jdbc.sql("update sf_crop_masters set produce_item_id = :i where id = :id and tenant_id = :t")
                .param("i", itemId).param("id", cropId).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "CROP_UPDATED", "CROP", cropId, Map.of("produceItemId", itemId));
    }

    /** Area of a cycle in acres: its cultivated area, or its field's. */
    public BigDecimal areaAcres(long tenantId, long cycleId) {
        return jdbc.sql("select area_acres from sf_cost_objects where tenant_id = :t and crop_cycle_id = :c")
                .param("t", tenantId).param("c", cycleId).query(BigDecimal.class).optional().orElse(null);
    }

    // ---- Internals -------------------------------------------------------------------------------------------------

    /** Keeps the cycle's cost object (name, area, dates) in step with the cycle, for overhead allocation (F6). */
    private void syncCostObject(long tenantId, long cycleId) {
        Map<String, Object> c = jdbc.sql("""
                        select cc.name, cc.farm_id, coalesce(cc.cultivated_area, p.area) as area,
                               case when cc.cultivated_area is not null then cc.area_unit else p.area_unit end as unit,
                               coalesce(cc.actual_sowing_date, cc.planned_sowing_date) as active_from, cc.closed_on, cc.status
                        from sf_cultivation_cycles cc join sf_land_parcels p on p.id = cc.land_parcel_id and p.tenant_id = cc.tenant_id
                        where cc.id = :id and cc.tenant_id = :t""")
                .param("id", cycleId).param("t", tenantId).query().singleRow();
        BigDecimal acres = toAcres((BigDecimal) c.get("area"), (String) c.get("unit"));
        costObjects.updateCropCycle(tenantId, cycleId, (String) c.get("name"), ((Number) c.get("farm_id")).longValue(), acres,
                ((java.sql.Date) c.get("active_from")).toLocalDate(),
                c.get("closed_on") == null ? null : ((java.sql.Date) c.get("closed_on")).toLocalDate(), !"CANCELLED".equals(c.get("status")));
    }

    static BigDecimal toAcres(BigDecimal area, String unit) {
        if (area == null || area.signum() <= 0) return null;
        BigDecimal acres = area.multiply(ACRES_PER_UNIT.getOrDefault(unit, BigDecimal.ONE)).setScale(4, RoundingMode.HALF_UP);
        return acres.signum() > 0 ? acres : null;
    }

    private record Parcel(long id, BigDecimal area, String unit) {}

    private Parcel parcel(long tenantId, long farmId, long parcelId) {
        return jdbc.sql("select id, area, area_unit from sf_land_parcels where id = :id and farm_id = :f and tenant_id = :t and active")
                .param("id", parcelId).param("f", farmId).param("t", tenantId)
                .query((rs, i) -> new Parcel(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3))).optional()
                .orElseThrow(() -> ApiException.notFound("LAND_PARCEL_NOT_FOUND", "Land parcel not found in this farm."));
    }

    /** A cycle cannot use more land than its field has (section 3). */
    private static void requireAreaFits(Parcel parcel, BigDecimal area, String unit) {
        if (area == null) return;
        BigDecimal cycleAcres = toAcres(area, unit), fieldAcres = toAcres(parcel.area(), parcel.unit());
        if (cycleAcres != null && fieldAcres != null && cycleAcres.compareTo(fieldAcres) > 0) {
            throw ApiException.badRequest("AREA_EXCEEDS_FIELD", "The cultivated area is more than the field's area.");
        }
    }

    private void requireFarm(long tenantId, long farmId) {
        boolean ok = jdbc.sql("select exists (select 1 from sf_farms where id = :id and tenant_id = :t and active)")
                .param("id", farmId).param("t", tenantId).query(Boolean.class).single();
        if (!ok) throw ApiException.notFound("FARM_NOT_FOUND", "Farm not found.");
    }

    private void requireVariety(long tenantId, long cropId, Long varietyId) {
        if (varietyId == null) return;
        boolean ok = jdbc.sql("select exists (select 1 from sf_crop_varieties where id = :id and tenant_id = :t and crop_id = :c and active)")
                .param("id", varietyId).param("t", tenantId).param("c", cropId).query(Boolean.class).single();
        if (!ok) throw ApiException.badRequest("VARIETY_NOT_FOUND", "Choose a variety of this crop.");
    }

    private void requireSeason(long tenantId, Long seasonId) {
        if (seasonId == null) return;
        boolean ok = jdbc.sql("select exists (select 1 from sf_crop_seasons where id = :id and tenant_id = :t and active)")
                .param("id", seasonId).param("t", tenantId).query(Boolean.class).single();
        if (!ok) throw ApiException.notFound("SEASON_NOT_FOUND", "Season not found.");
    }

    /** Fields shared with another cycle growing at the same time: warn when together they need more than the field (F11). */
    private Cycle withWarnings(long tenantId, Cycle c) {
        if (Set.of("CLOSED", "CANCELLED").contains(c.status()) || c.areaAcres() == null) return c;
        BigDecimal fieldAcres = toAcres(c.parcelArea(), c.parcelAreaUnit());
        BigDecimal used = jdbc.sql("""
                        select coalesce(sum(co.area_acres), 0) from sf_cultivation_cycles cc
                        join sf_cost_objects co on co.crop_cycle_id = cc.id and co.tenant_id = cc.tenant_id
                        where cc.tenant_id = :t and cc.land_parcel_id = :p and cc.active and cc.status not in ('CLOSED', 'CANCELLED')
                          and coalesce(cc.expected_harvest_date, date '9999-12-31') >= :from
                          and cc.planned_sowing_date <= coalesce(cast(:to as date), date '9999-12-31')""")
                .param("t", tenantId).param("p", c.landParcelId())
                .param("from", c.actualSowingDate() != null ? c.actualSowingDate() : c.plannedSowingDate())
                .param("to", c.expectedHarvestDate()).query(BigDecimal.class).single();
        if (fieldAcres == null || used.compareTo(fieldAcres) <= 0) return c;
        return new Cycle(c.id(), c.farmId(), c.farmName(), c.landParcelId(), c.parcelName(), c.parcelArea(), c.parcelAreaUnit(), c.cropId(), c.cropName(),
                c.varietyId(), c.varietyName(), c.seasonId(), c.seasonName(), c.name(), c.plannedSowingDate(), c.actualSowingDate(),
                c.expectedHarvestDate(), c.closedOn(), c.cultivatedArea(), c.areaUnit(), c.areaAcres(), c.expectedYield(), c.status(),
                c.costObjectId(), c.daysSinceSowing(), c.harvestCount(), List.of("FIELD_OVERCOMMITTED"));
    }

    private static Crop crop(ResultSet rs) throws SQLException {
        return new Crop(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), (Long) rs.getObject(6), rs.getBoolean(7));
    }

    private static Cycle cycle(ResultSet rs, List<String> warnings) throws SQLException {
        LocalDate actual = rs.getObject("actual_sowing_date", LocalDate.class);
        String status = rs.getString("status");
        LocalDate end = rs.getObject("closed_on", LocalDate.class);
        Long days = actual == null || "CANCELLED".equals(status) ? null : ChronoUnit.DAYS.between(actual, end != null ? end : LocalDate.now());
        return new Cycle(rs.getLong("id"), rs.getLong("farm_id"), rs.getString("farm_name"), rs.getLong("land_parcel_id"), rs.getString("parcel_name"),
                rs.getBigDecimal("parcel_area"), rs.getString("parcel_unit"), rs.getLong("crop_id"), rs.getString("crop_name"),
                (Long) rs.getObject("variety_id"), rs.getString("variety_name"), (Long) rs.getObject("season_id"), rs.getString("season_name"),
                rs.getString("name"), rs.getObject("planned_sowing_date", LocalDate.class), actual, rs.getObject("expected_harvest_date", LocalDate.class),
                end, rs.getBigDecimal("cultivated_area"), rs.getString("area_unit"), rs.getBigDecimal("area_acres"), rs.getBigDecimal("expected_yield"),
                status, (Long) rs.getObject("cost_object_id"), days, rs.getInt("harvest_count"), warnings);
    }

    private static Activity activity(ResultSet rs) throws SQLException {
        return new Activity(rs.getLong("id"), rs.getLong("cycle_id"), rs.getString("cycle_name"), rs.getString("activity_type"),
                rs.getObject("activity_date", LocalDate.class), rs.getString("notes"), (Integer) rs.getObject("workers"), rs.getBigDecimal("hours"),
                rs.getString("input_name"), rs.getBigDecimal("quantity"), rs.getString("quantity_unit"),
                rs.getBoolean("legacy_memo") ? rs.getBigDecimal("cost") : null, rs.getBoolean("legacy_memo"), rs.getString("status"),
                (Long) rs.getObject("stock_document_id"), (Long) rs.getObject("bill_document_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Harvest harvest(ResultSet rs) throws SQLException {
        boolean legacy = rs.getBoolean("legacy");
        return new Harvest(rs.getLong("id"), rs.getLong("cycle_id"), rs.getString("cycle_name"), rs.getObject("harvest_date", LocalDate.class),
                rs.getBigDecimal("quantity"), rs.getString("quantity_unit"), (Long) rs.getObject("item_id"), rs.getBigDecimal("base_quantity"),
                (Long) rs.getObject("lot_id"), rs.getString("lot_no"), rs.getString("grade"), rs.getBigDecimal("moisture_pct"),
                legacy ? rs.getBigDecimal("sale_value") : null, legacy, rs.getString("status"), (Long) rs.getObject("document_id"), rs.getString("notes"));
    }

    private static Observation observation(ResultSet rs) throws SQLException {
        Instant created = rs.getTimestamp("created_at").toInstant();
        return new Observation(rs.getLong("id"), rs.getLong("cycle_id"), rs.getObject("observed_on", LocalDate.class), rs.getString("kind"),
                rs.getString("severity"), rs.getString("field_part"), rs.getString("notes"), rs.getString("action_taken"),
                (Long) rs.getObject("activity_id"), created);
    }

    private static String trim(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private static String or(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }
}
