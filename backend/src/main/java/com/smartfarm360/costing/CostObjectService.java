package com.smartfarm360.costing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * Keeps a crop cycle's cost object in step with the cycle: its name, farm, area in acres and the dates it ran,
     * which overhead allocation uses (P16, crop design F6). {@code activeTo} null means still running.
     */
    @Transactional
    public long updateCropCycle(long tenantId, long cropCycleId, String name, Long farmId, BigDecimal areaAcres,
                                LocalDate activeFrom, LocalDate activeTo, boolean active) {
        long id = forCropCycle(tenantId, cropCycleId, name, farmId);
        jdbc.sql("""
                        update sf_cost_objects set name = :name, farm_id = :farm, area_acres = :area, active_from = :from, active_to = :to,
                          active = :active where id = :id and tenant_id = :t""")
                .param("name", name).param("farm", farmId).param("area", areaAcres).param("from", activeFrom).param("to", activeTo)
                .param("active", active).param("id", id).param("t", tenantId).update();
        return id;
    }

    /** A new animal group as a cost object (livestock design, I2); costs and revenue of the herd are assigned to it. */
    @Transactional
    public long createAnimalGroup(long tenantId, String name, Long farmId) {
        return jdbc.sql("insert into sf_cost_objects (tenant_id, kind, name, farm_id) values (:t, 'ANIMAL_GROUP', :name, :farm) returning id")
                .param("t", tenantId).param("name", name).param("farm", farmId).query(Long.class).single();
    }

    /** Renames, moves or retires an animal group's cost object. */
    @Transactional
    public void updateAnimalGroup(long tenantId, long costObjectId, String name, Long farmId, boolean active) {
        jdbc.sql("update sf_cost_objects set name = :name, farm_id = :farm, active = :active where id = :id and tenant_id = :t and kind = 'ANIMAL_GROUP'")
                .param("name", name).param("farm", farmId).param("active", active).param("id", costObjectId).param("t", tenantId).update();
    }

    /** A new pond culture cycle as a cost object (aquaculture design, K2); its area is the pond's water spread in acres. */
    @Transactional
    public long createPondCycle(long tenantId, String name, Long farmId, BigDecimal areaAcres, LocalDate activeFrom) {
        return jdbc.sql("""
                        insert into sf_cost_objects (tenant_id, kind, name, farm_id, area_acres, active_from)
                        values (:t, 'POND_CYCLE', :name, :farm, :area, :from) returning id""")
                .param("t", tenantId).param("name", name).param("farm", farmId).param("area", areaAcres).param("from", activeFrom).query(Long.class).single();
    }

    /** Renames a pond cycle's cost object. */
    @Transactional
    public void renamePondCycle(long tenantId, long costObjectId, String name) {
        jdbc.sql("update sf_cost_objects set name = :name where id = :id and tenant_id = :t and kind = 'POND_CYCLE'")
                .param("name", name).param("id", costObjectId).param("t", tenantId).update();
    }

    /** The cost object of a crop cycle, if it has one. */
    public Optional<Long> forCycle(long tenantId, long cropCycleId) {
        return jdbc.sql("select id from sf_cost_objects where tenant_id = :t and crop_cycle_id = :cycle")
                .param("t", tenantId).param("cycle", cropCycleId).query(Long.class).optional();
    }
}
