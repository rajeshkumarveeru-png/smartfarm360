package com.smartfarm360.aquaculture;

import com.smartfarm360.aquaculture.AquaDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Water quality (aquaculture design, K7): readings per pond, compared with limits the farmer sets for the farm business or
 * for one pond. No limits are filled in by the app (principle 5).
 */
@Service
public class WaterService {
    static final ZoneId FARM_ZONE = ZoneId.of("Asia/Kolkata");
    static final List<String> PARAMETERS = List.of("DISSOLVED_OXYGEN", "PH", "TEMPERATURE", "AMMONIA", "TRANSPARENCY", "SALINITY");

    private final JdbcClient jdbc;
    private final PondService ponds;
    private final AuditService audit;

    public WaterService(JdbcClient jdbc, PondService ponds, AuditService audit) {
        this.jdbc = jdbc;
        this.ponds = ponds;
        this.audit = audit;
    }

    // ---- Limits --------------------------------------------------------------------------------------------------------------

    private List<Limit> limitsOf(long tenantId, Long pondId) {
        return jdbc.sql("select parameter, lowest, highest from sf_water_limits where tenant_id = :t and " + (pondId == null ? "pond_id is null" : "pond_id = :p")
                        + " order by parameter")
                .param("t", tenantId).param("p", pondId).query((rs, i) -> new Limit(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).list();
    }

    @Transactional(readOnly = true)
    public Limits limits(TenantContext ctx, Long pondId) {
        if (pondId != null) ponds.pondRow(ctx, pondId);
        return new Limits(limitsOf(ctx.tenantId(), null), pondId == null ? List.of() : limitsOf(ctx.tenantId(), pondId));
    }

    @Transactional
    public Limits saveLimits(TenantContext ctx, LimitsRequest r) {
        if (r.pondId() != null) ponds.pondRow(ctx, r.pondId());
        Set<String> seen = new HashSet<>();
        for (Limit l : r.limits()) {
            if (!seen.add(l.parameter())) throw ApiException.badRequest("DUPLICATE_LIMIT", "Each parameter can have one limit.");
            if (l.lowest() == null && l.highest() == null) throw ApiException.badRequest("LIMIT_REQUIRED", "Enter a lowest or highest value.");
            if (l.lowest() != null && l.highest() != null && l.lowest().compareTo(l.highest()) > 0) {
                throw ApiException.badRequest("INVALID_LIMIT", "The lowest value is above the highest.");
            }
        }
        jdbc.sql("delete from sf_water_limits where tenant_id = :t and " + (r.pondId() == null ? "pond_id is null" : "pond_id = :p"))
                .param("t", ctx.tenantId()).param("p", r.pondId()).update();
        for (Limit l : r.limits()) {
            jdbc.sql("insert into sf_water_limits (tenant_id, pond_id, parameter, lowest, highest) values (:t, :p, :k, :lo, :hi)")
                    .param("t", ctx.tenantId()).param("p", r.pondId()).param("k", l.parameter()).param("lo", l.lowest()).param("hi", l.highest()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "WATER_LIMITS_SAVED", r.pondId() == null ? "TENANT" : "POND", r.pondId() == null ? ctx.tenantId() : r.pondId(),
                Map.of("limits", r.limits().size()));
        return limits(ctx, r.pondId());
    }

    /** The limits in force for each pond: the pond's own, else the farm business's (K7). */
    private Map<Long, Map<String, Limit>> effectiveLimits(long tenantId) {
        Map<String, Limit> defaults = new HashMap<>();
        Map<Long, Map<String, Limit>> byPond = new HashMap<>();
        jdbc.sql("select pond_id, parameter, lowest, highest from sf_water_limits where tenant_id = :t").param("t", tenantId).query(rs -> {
            Limit l = new Limit(rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4));
            Long pond = (Long) rs.getObject(1);
            if (pond == null) defaults.put(l.parameter(), l);
            else byPond.computeIfAbsent(pond, k -> new HashMap<>()).put(l.parameter(), l);
        });
        Map<Long, Map<String, Limit>> out = new HashMap<>();
        out.put(0L, defaults);
        for (Map.Entry<Long, Map<String, Limit>> e : byPond.entrySet()) {
            Map<String, Limit> merged = new HashMap<>(defaults);
            merged.putAll(e.getValue());
            out.put(e.getKey(), merged);
        }
        return out;
    }

    private static List<String> outOfLimits(Map<String, Limit> limits, Map<String, BigDecimal> values) {
        List<String> out = new ArrayList<>();
        for (String p : PARAMETERS) {
            BigDecimal v = values.get(p);
            Limit l = limits.get(p);
            if (v == null || l == null) continue;
            if ((l.lowest() != null && v.compareTo(l.lowest()) < 0) || (l.highest() != null && v.compareTo(l.highest()) > 0)) out.add(p);
        }
        return out;
    }

    // ---- Readings ------------------------------------------------------------------------------------------------------------

    private static final String READING_SELECT = """
            select r.id, r.pond_id, p.name, r.taken_at, r.dissolved_oxygen, r.ph, r.temperature, r.ammonia, r.transparency, r.salinity, r.notes
            from sf_water_readings r join sf_ponds p on p.id = r.pond_id and p.tenant_id = r.tenant_id where r.tenant_id = :t""";

    private List<Reading> query(long tenantId, String where, Map<String, Object> params) {
        Map<Long, Map<String, Limit>> limits = effectiveLimits(tenantId);
        JdbcClient.StatementSpec spec = jdbc.sql(READING_SELECT + where).param("t", tenantId);
        for (Map.Entry<String, Object> e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query((rs, i) -> {
            long pond = rs.getLong(2);
            Map<String, BigDecimal> values = new HashMap<>();
            String[] cols = {"DISSOLVED_OXYGEN", "PH", "TEMPERATURE", "AMMONIA", "TRANSPARENCY", "SALINITY"};
            for (int c = 0; c < cols.length; c++) if (rs.getBigDecimal(5 + c) != null) values.put(cols[c], rs.getBigDecimal(5 + c));
            return new Reading(rs.getLong(1), pond, rs.getString(3), rs.getObject(4, OffsetDateTime.class), rs.getBigDecimal(5), rs.getBigDecimal(6),
                    rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11),
                    outOfLimits(limits.getOrDefault(pond, limits.get(0L)), values));
        }).list();
    }

    @Transactional
    public Reading record(TenantContext ctx, long pondId, ReadingRequest r) {
        PondService.PondRow p = ponds.pondRow(ctx, pondId);
        if (!p.active()) throw ApiException.conflict("POND_INACTIVE", "This pond is not in use.");
        if (r.takenAt().toInstant().isAfter(java.time.Instant.now().plusSeconds(300))) {
            throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        }
        if (r.dissolvedOxygen() == null && r.ph() == null && r.temperature() == null && r.ammonia() == null && r.transparency() == null && r.salinity() == null) {
            throw ApiException.badRequest("READING_EMPTY", "Enter at least one measurement.");
        }
        long id = jdbc.sql("""
                        insert into sf_water_readings (tenant_id, pond_id, taken_at, dissolved_oxygen, ph, temperature, ammonia, transparency, salinity, notes, created_by)
                        values (:t, :p, :at, :do, :ph, :temp, :nh, :tr, :sal, :n, :u) returning id""")
                .param("t", ctx.tenantId()).param("p", pondId).param("at", r.takenAt()).param("do", r.dissolvedOxygen()).param("ph", r.ph())
                .param("temp", r.temperature()).param("nh", r.ammonia()).param("tr", r.transparency()).param("sal", r.salinity())
                .param("n", PondService.trim(r.notes())).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "WATER_READING_RECORDED", "POND", pondId, Map.of("takenAt", r.takenAt().toString()));
        return query(ctx.tenantId(), " and r.id = :id", Map.of("id", id)).get(0);
    }

    /** Readings for a date range (farm dates), for one pond or all. */
    @Transactional(readOnly = true)
    public List<Reading> readings(TenantContext ctx, Long pondId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        if (pondId != null) ponds.pondRow(ctx, pondId);
        Map<String, Object> params = new HashMap<>();
        params.put("from", from.atStartOfDay(FARM_ZONE).toOffsetDateTime());
        params.put("to", to.plusDays(1).atStartOfDay(FARM_ZONE).toOffsetDateTime());
        String where = " and r.taken_at >= :from and r.taken_at < :to";
        if (pondId != null) { where += " and r.pond_id = :p"; params.put("p", pondId); }
        return query(ctx.tenantId(), where + " order by r.taken_at desc, r.id desc", params);
    }

    /** Each pond's latest reading. */
    @Transactional(readOnly = true)
    public Map<Long, Reading> latest(TenantContext ctx) {
        Map<Long, Reading> out = new HashMap<>();
        for (Reading r : query(ctx.tenantId(),
                " and r.id in (select distinct on (pond_id) id from sf_water_readings where tenant_id = :t order by pond_id, taken_at desc, id desc)", Map.of())) {
            out.put(r.pondId(), r);
        }
        return out;
    }

    @Transactional
    public void delete(TenantContext ctx, long id) {
        Long pond = jdbc.sql("select pond_id from sf_water_readings where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "Water reading not found."));
        jdbc.sql("delete from sf_water_readings where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "WATER_READING_DELETED", "POND", pond, Map.of("reading", id));
    }

    /** For the dashboard: ponds with a reading outside its limits on a farm date (K14). */
    public int pondsOutOfLimits(TenantContext ctx, LocalDate date) {
        return (int) readings(ctx, null, date, date).stream().filter(r -> !r.outOfLimits().isEmpty()).map(Reading::pondId).distinct().count();
    }
}
