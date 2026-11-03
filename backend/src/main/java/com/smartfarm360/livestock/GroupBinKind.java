package com.smartfarm360.livestock;

import com.smartfarm360.platform.bin.TableBinKind;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Poultry batches, goat and sheep herds and cattle groups in the Bin. In the Bin the group leaves every list and its cost
 * object stops being offered (reminders and the dashboard skip it); its money stays in the accounts. Delete forever
 * erases the group with its daily records, head changes, health plan and lines, which the database refuses while money
 * or stock records (purchase and sale bills, feed issues, eggs) are booked to it.
 */
@Component
public class GroupBinKind extends TableBinKind {
    public GroupBinKind(JdbcClient jdbc) {
        super(jdbc, "ANIMAL_GROUP", Permission.LIVESTOCK_WRITE, "sf_animal_groups", "co.name", "x.species",
                "join sf_cost_objects co on co.id = x.cost_object_id and co.tenant_id = x.tenant_id", "ANIMAL_GROUP_NOT_FOUND");
    }

    /** A cattle group still holding animals cannot go: move the animals first. */
    @Override
    protected void beforeDelete(TenantContext ctx, long id) {
        boolean animals = jdbc.sql("""
                        select exists (select 1 from sf_animals a where a.tenant_id = :t and a.status = 'ACTIVE'
                          and (select m.group_id from sf_animal_group_moves m where m.tenant_id = a.tenant_id and m.animal_id = a.id
                               order by m.from_date desc, m.id desc limit 1) = :id)""")
                .param("t", ctx.tenantId()).param("id", id).query(Boolean.class).single();
        if (animals) throw ApiException.conflict("GROUP_HAS_ANIMALS", "Move the animals to another group first.");
    }

    @Override
    protected void afterDelete(TenantContext ctx, long id) {
        jdbc.sql("update sf_cost_objects co set active = false from sf_animal_groups g where g.id = :id and g.tenant_id = :t and co.id = g.cost_object_id and co.tenant_id = g.tenant_id")
                .param("id", id).param("t", ctx.tenantId()).update();
    }

    /** Back as it was: open again unless the batch had been closed. */
    @Override
    protected void afterRestore(TenantContext ctx, long id) {
        jdbc.sql("update sf_cost_objects co set active = (g.closed_on is null) from sf_animal_groups g where g.id = :id and g.tenant_id = :t and co.id = g.cost_object_id and co.tenant_id = g.tenant_id")
                .param("id", id).param("t", ctx.tenantId()).update();
    }

    @Override
    protected void erase(TenantContext ctx, long id) {
        boolean animalHistory = jdbc.sql("select exists (select 1 from sf_animal_group_moves where group_id = :id and tenant_id = :t)")
                .param("id", id).param("t", ctx.tenantId()).query(Boolean.class).single();
        // Animals' own histories name the group: erasing it would rewrite where each animal was
        if (animalHistory) throw ApiException.conflict("BIN_KEPT", "Animals' histories name this group, so it is kept in the Bin.");
        Long costObject = jdbc.sql("select cost_object_id from sf_animal_groups where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query(Long.class).single();
        for (String sql : new String[] {
                "delete from sf_head_movements where group_id = :id and tenant_id = :t",
                "delete from sf_batch_days where group_id = :id and tenant_id = :t",
                "delete from sf_egg_postings where group_id = :id and tenant_id = :t",
                "delete from sf_milk_postings where group_id = :id and tenant_id = :t",
                "delete from sf_milk_sessions where group_id = :id and tenant_id = :t",
                "delete from sf_health_plan_items where group_id = :id and tenant_id = :t",
                "delete from sf_animal_events where group_id = :id and tenant_id = :t",
                "delete from sf_animal_groups where id = :id and tenant_id = :t"}) {
            jdbc.sql(sql).param("id", id).param("t", ctx.tenantId()).update();
        }
        jdbc.sql("delete from sf_cost_objects where id = :co and tenant_id = :t").param("co", costObject).param("t", ctx.tenantId()).update();
    }
}
