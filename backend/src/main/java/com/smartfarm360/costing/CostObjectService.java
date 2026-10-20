package com.smartfarm360.costing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Cost objects: the activities costs and revenue are assigned to (posting rules, section 5). Shared by the farming
 * modules that create activities (crop now; livestock, ponds and batches later) and finance, which posts against them.
 * Every tenant has exactly one OVERHEAD object for costs not tied to an activity.
 */
@Service
public class CostObjectService {
    private final JdbcClient jdbc;

    public CostObjectService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The tenant's overhead cost object, created on first use. */
    @Transactional
    public long overhead(long tenantId) {
        Optional<Long> existing = jdbc.sql("select id from sf_cost_objects where tenant_id = :t and kind = 'OVERHEAD'")
                .param("t", tenantId).query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        jdbc.sql("insert into sf_cost_objects (tenant_id, kind, name) values (:t, 'OVERHEAD', 'Farm overhead') on conflict do nothing")
                .param("t", tenantId).update();
        return jdbc.sql("select id from sf_cost_objects where tenant_id = :t and kind = 'OVERHEAD'").param("t", tenantId).query(Long.class).single();
    }

    /** Registers a crop cycle as a cost object (idempotent). */
    @Transactional
    public long forCropCycle(long tenantId, long cropCycleId, String name, Long farmId) {
        jdbc.sql("""
                        insert into sf_cost_objects (tenant_id, kind, name, crop_cycle_id, farm_id)
                        values (:t, 'CROP_CYCLE', :name, :cycle, :farm) on conflict (tenant_id, crop_cycle_id) do nothing""")
                .param("t", tenantId).param("name", name).param("cycle", cropCycleId).param("farm", farmId).update();
        return jdbc.sql("select id from sf_cost_objects where tenant_id = :t and crop_cycle_id = :cycle")
                .param("t", tenantId).param("cycle", cropCycleId).query(Long.class).single();
    }
}
