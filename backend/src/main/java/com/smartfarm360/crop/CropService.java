package com.smartfarm360.crop;

import com.smartfarm360.crop.CropDtos.*;
import com.smartfarm360.platform.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Crop master data, seasons, cultivation cycles, activities and harvests. Tenant access and permissions are checked
 * by the tenant interceptor; this class checks that every referenced row belongs to {@code ctx.tenantId()}.
 * (Typed responses, pagination and stock/cost integration are Phase 5 work.)
 */
@Service
public class CropService {
    private final JdbcTemplate jdbc;

    public CropService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private void farm(long tenantId, Long farmId) {
        if (count("select count(*) from sf_farms where id=? and tenant_id=? and active=true", farmId, tenantId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Farm not found in this tenant");
    }

    private void parcel(long tenantId, Long farmId, Long parcelId) {
        if (count("select count(*) from sf_land_parcels where id=? and farm_id=? and tenant_id=? and active=true", parcelId, farmId, tenantId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Land parcel not found in this farm");
    }

    /** {@code table} is always a constant from this class, never user input. */
    private void exists(String table, long tenantId, Long id, String label) {
        if (id != null && count("select count(*) from " + table + " where id=? and tenant_id=? and active=true", id, tenantId) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " does not belong to this tenant");
    }

    private void cycleExists(long tenantId, Long cycleId) {
        if (cycleId != null && count("select count(*) from sf_cultivation_cycles where id=? and tenant_id=? and active=true", cycleId, tenantId) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cultivation cycle does not belong to this tenant");
    }

    @Transactional
    public Map<String, Object> createCrop(TenantContext ctx, CropRequest b) {
        return insert("insert into sf_crop_masters(tenant_id,name,local_name,category,description) values(?,?,?,?,?) returning id",
                ctx.tenantId(), b.name().trim(), trim(b.localName()), trim(b.category()), trim(b.description()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> crops(TenantContext ctx) {
        return jdbc.queryForList("select id,tenant_id as \"tenantId\",name,local_name as \"localName\",category,description,active,created_at as \"createdAt\" from sf_crop_masters where tenant_id=? and active=true order by name", ctx.tenantId());
    }

    @Transactional
    public Map<String, Object> createVariety(TenantContext ctx, long cropId, VarietyRequest b) {
        exists("sf_crop_masters", ctx.tenantId(), cropId, "Crop");
        return insert("insert into sf_crop_varieties(tenant_id,crop_id,name,local_name,duration_days,notes) values(?,?,?,?,?,?) returning id",
                ctx.tenantId(), cropId, b.name().trim(), trim(b.localName()), b.durationDays(), trim(b.notes()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> varieties(TenantContext ctx, long cropId) {
        exists("sf_crop_masters", ctx.tenantId(), cropId, "Crop");
        return jdbc.queryForList("select id,tenant_id as \"tenantId\",crop_id as \"cropId\",name,local_name as \"localName\",duration_days as \"durationDays\",notes,active from sf_crop_varieties where tenant_id=? and crop_id=? and active=true order by name", ctx.tenantId(), cropId);
    }

    @Transactional
    public Map<String, Object> createSeason(TenantContext ctx, SeasonRequest b) {
        if (b.endDate().isBefore(b.startDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Season name and a valid date range are required");
        return insert("insert into sf_crop_seasons(tenant_id,name,start_date,end_date,season_type) values(?,?,?,?,?) returning id",
                ctx.tenantId(), b.name().trim(), b.startDate(), b.endDate(), trim(b.seasonType()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> seasons(TenantContext ctx) {
        return jdbc.queryForList("select id,tenant_id as \"tenantId\",name,start_date as \"startDate\",end_date as \"endDate\",season_type as \"seasonType\",active from sf_crop_seasons where tenant_id=? and active=true order by start_date desc", ctx.tenantId());
    }

    @Transactional
    public Map<String, Object> createCycle(TenantContext ctx, CycleRequest b) {
        long t = ctx.tenantId();
        farm(t, b.farmId());
        parcel(t, b.farmId(), b.landParcelId());
        exists("sf_crop_masters", t, b.cropId(), "Crop");
        if (b.varietyId() != null && count("select count(*) from sf_crop_varieties where id=? and tenant_id=? and crop_id=? and active=true", b.varietyId(), t, b.cropId()) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variety must belong to the selected crop and tenant");
        exists("sf_crop_seasons", t, b.seasonId(), "Season");
        if (b.expectedHarvestDate() != null && b.expectedHarvestDate().isBefore(b.plannedSowingDate()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected harvest cannot be before sowing");
        return insert("insert into sf_cultivation_cycles(tenant_id,farm_id,land_parcel_id,crop_id,variety_id,season_id,name,planned_sowing_date,expected_harvest_date,cultivated_area,area_unit,status) values(?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                t, b.farmId(), b.landParcelId(), b.cropId(), b.varietyId(), b.seasonId(), b.name().trim(), b.plannedSowingDate(),
                b.expectedHarvestDate(), b.cultivatedArea(), or(b.areaUnit(), "ACRE"), or(b.status(), "PLANNED"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> cycles(TenantContext ctx, Long farmId) {
        if (farmId != null) farm(ctx.tenantId(), farmId);
        String sql = "select cc.id,cc.tenant_id as \"tenantId\",cc.farm_id as \"farmId\",cc.land_parcel_id as \"landParcelId\",cc.crop_id as \"cropId\",cm.name as \"cropName\",cv.name as \"varietyName\",cs.name as \"seasonName\",cc.name,cc.planned_sowing_date as \"plannedSowingDate\",cc.expected_harvest_date as \"expectedHarvestDate\",cc.cultivated_area as \"cultivatedArea\",cc.area_unit as \"areaUnit\",cc.status from sf_cultivation_cycles cc join sf_crop_masters cm on cm.id=cc.crop_id and cm.tenant_id=cc.tenant_id left join sf_crop_varieties cv on cv.id=cc.variety_id and cv.tenant_id=cc.tenant_id left join sf_crop_seasons cs on cs.id=cc.season_id and cs.tenant_id=cc.tenant_id where cc.tenant_id=?";
        return farmId == null ? jdbc.queryForList(sql + " order by cc.created_at desc", ctx.tenantId())
                : jdbc.queryForList(sql + " and cc.farm_id=? order by cc.created_at desc", ctx.tenantId(), farmId);
    }

    @Transactional
    public Map<String, Object> createActivity(TenantContext ctx, ActivityRequest b) {
        cycleExists(ctx.tenantId(), b.cycleId());
        return insert("insert into sf_farm_activities(tenant_id,cycle_id,activity_type,activity_date,notes,input_name,quantity,quantity_unit,cost) values(?,?,?,?,?,?,?,?,?) returning id",
                ctx.tenantId(), b.cycleId(), b.activityType(), b.activityDate(), trim(b.notes()), trim(b.inputName()), b.quantity(),
                trim(b.quantityUnit()), zero(b.cost()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> activities(TenantContext ctx, Long cycleId) {
        cycleExists(ctx.tenantId(), cycleId);
        String sql = "select id,tenant_id as \"tenantId\",cycle_id as \"cycleId\",activity_type as \"activityType\",activity_date as \"activityDate\",notes,input_name as \"inputName\",quantity,quantity_unit as \"quantityUnit\",cost,created_at as \"createdAt\" from sf_farm_activities where tenant_id=?";
        return cycleId == null ? jdbc.queryForList(sql + " order by activity_date desc", ctx.tenantId())
                : jdbc.queryForList(sql + " and cycle_id=? order by activity_date desc", ctx.tenantId(), cycleId);
    }

    @Transactional
    public Map<String, Object> createHarvest(TenantContext ctx, HarvestRequest b) {
        cycleExists(ctx.tenantId(), b.cycleId());
        return insert("insert into sf_harvest_records(tenant_id,cycle_id,harvest_date,quantity,quantity_unit,sale_value,notes) values(?,?,?,?,?,?,?) returning id",
                ctx.tenantId(), b.cycleId(), b.harvestDate(), b.quantity(), b.quantityUnit().trim(), zero(b.saleValue()), trim(b.notes()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> harvests(TenantContext ctx, Long cycleId) {
        cycleExists(ctx.tenantId(), cycleId);
        String sql = "select id,tenant_id as \"tenantId\",cycle_id as \"cycleId\",harvest_date as \"harvestDate\",quantity,quantity_unit as \"quantityUnit\",sale_value as \"saleValue\",notes,created_at as \"createdAt\" from sf_harvest_records where tenant_id=?";
        return cycleId == null ? jdbc.queryForList(sql + " order by harvest_date desc", ctx.tenantId())
                : jdbc.queryForList(sql + " and cycle_id=? order by harvest_date desc", ctx.tenantId(), cycleId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private Map<String, Object> insert(String sql, Object... args) {
        return Map.of("id", jdbc.queryForObject(sql, Long.class, args));
    }

    private static String trim(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static BigDecimal zero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String or(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }
}
