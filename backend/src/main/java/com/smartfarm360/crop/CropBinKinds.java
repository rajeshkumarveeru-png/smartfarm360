package com.smartfarm360.crop;

import com.smartfarm360.platform.bin.TableBinKind;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Crop cycles and work notes in the Bin. A cycle in the Bin leaves the lists and stops being offered for expenses (its
 * cost object is inactive); its money stays in the accounts. Delete forever erases the cycle with its notes,
 * observations and harvests, which the database refuses while money or stock records are booked to it.
 */
public final class CropBinKinds {
    private CropBinKinds() {}

    @Component
    public static class Cycles extends TableBinKind {
        public Cycles(JdbcClient jdbc) {
            super(jdbc, "CROP_CYCLE", Permission.CROP_WRITE, "sf_cultivation_cycles", "x.name", "concat_ws(' · ', cm.name, f.name)",
                    "join sf_crop_masters cm on cm.id = x.crop_id and cm.tenant_id = x.tenant_id join sf_farms f on f.id = x.farm_id and f.tenant_id = x.tenant_id",
                    "CYCLE_NOT_FOUND");
        }

        @Override protected String deleteSet() { return ", active = false, updated_at = now()"; }
        @Override protected String restoreSet() { return ", active = true, updated_at = now()"; }

        @Override
        protected void afterDelete(TenantContext ctx, long id) {
            jdbc.sql("update sf_cost_objects set active = false where crop_cycle_id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        }

        @Override
        protected void beforeRestore(TenantContext ctx, long id) {
            jdbc.sql("""
                            select f.name, f.deleted_at is not null, p.name, p.deleted_at is not null from sf_cultivation_cycles c
                            join sf_farms f on f.id = c.farm_id and f.tenant_id = c.tenant_id
                            join sf_land_parcels p on p.id = c.land_parcel_id and p.tenant_id = c.tenant_id
                            where c.id = :id and c.tenant_id = :t""")
                    .param("id", id).param("t", ctx.tenantId())
                    .query((rs, i) -> { requireParentKept(rs.getBoolean(2), rs.getString(1)); requireParentKept(rs.getBoolean(4), rs.getString(3)); return 0; }).list();
        }

        @Override
        protected void afterRestore(TenantContext ctx, long id) {
            // A cancelled cycle's cost object stays inactive, as before it went to the Bin
            jdbc.sql("""
                            update sf_cost_objects set active = (select c.status <> 'CANCELLED' from sf_cultivation_cycles c where c.id = :id and c.tenant_id = :t)
                            where crop_cycle_id = :id and tenant_id = :t""")
                    .param("id", id).param("t", ctx.tenantId()).update();
        }

        @Override
        protected void erase(TenantContext ctx, long id) {
            for (String sql : new String[] {
                    "delete from sf_crop_observations where cycle_id = :id and tenant_id = :t",
                    "delete from sf_farm_activities where cycle_id = :id and tenant_id = :t",
                    "delete from sf_harvest_records where cycle_id = :id and tenant_id = :t",
                    "delete from sf_cost_objects where crop_cycle_id = :id and tenant_id = :t",
                    "delete from sf_cultivation_cycles where id = :id and tenant_id = :t"}) {
                jdbc.sql(sql).param("id", id).param("t", ctx.tenantId()).update();
            }
        }
    }

    @Component
    public static class WorkNotes extends TableBinKind {
        public WorkNotes(JdbcClient jdbc) {
            super(jdbc, "CROP_ACTIVITY", Permission.CROP_ACTIVITY_WRITE, "sf_farm_activities", "concat(x.activity_type, ' · ', x.activity_date)", "c.name",
                    "join sf_cultivation_cycles c on c.id = x.cycle_id and c.tenant_id = x.tenant_id", "ACTIVITY_NOT_FOUND");
        }

        /** Only a note of work goes to the Bin; work that moved stock or money is reversed instead (principle 4). */
        @Override
        protected void beforeDelete(TenantContext ctx, long id) {
            jdbc.sql("select stock_document_id is not null or bill_document_id is not null or status <> 'ACTIVE', legacy_memo from sf_farm_activities where id = :id and tenant_id = :t")
                    .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> {
                        if (rs.getBoolean(1)) throw ApiException.conflict("ACTIVITY_HAS_RECORDS", "This activity moved stock or money; reverse it instead.");
                        if (rs.getBoolean(2)) throw ApiException.conflict("LEGACY_RECORD", "Records from before Phase 5 are kept as history.");
                        return 0;
                    }).list();
        }

        @Override
        protected void beforeRestore(TenantContext ctx, long id) {
            jdbc.sql("""
                            select c.name, c.deleted_at is not null from sf_farm_activities a join sf_cultivation_cycles c on c.id = a.cycle_id and c.tenant_id = a.tenant_id
                            where a.id = :id and a.tenant_id = :t""")
                    .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> { requireParentKept(rs.getBoolean(2), rs.getString(1)); return 0; }).list();
        }

        @Override
        protected void erase(TenantContext ctx, long id) {
            jdbc.sql("update sf_crop_observations set activity_id = null where activity_id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
            jdbc.sql("delete from sf_farm_activities where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        }
    }
}
