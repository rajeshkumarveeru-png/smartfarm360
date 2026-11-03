package com.smartfarm360.farm;

import com.smartfarm360.platform.bin.TableBinKind;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Farms and fields in the Bin. A farm in the Bin leaves every list and choice (it is inactive); its money stays in the
 * accounts. Delete forever erases the farm with its fields and weather notes, which the database refuses while crop
 * cycles, money or stock records still name the farm or a field.
 */
public final class FarmBinKinds {
    private FarmBinKinds() {}

    @Component
    public static class Farms extends TableBinKind {
        public Farms(JdbcClient jdbc) {
            super(jdbc, "FARM", Permission.FARM_WRITE, "sf_farms", "x.name", "concat_ws(', ', x.village, x.district)", "", "FARM_NOT_FOUND");
        }

        @Override protected String deleteSet() { return ", active = false"; }
        @Override protected String restoreSet() { return ", active = true"; }

        @Override
        protected void erase(TenantContext ctx, long id) {
            jdbc.sql("delete from sf_weather_notes where farm_id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
            jdbc.sql("delete from sf_land_parcels where farm_id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
            jdbc.sql("delete from sf_farms where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        }
    }

    @Component
    public static class Fields extends TableBinKind {
        public Fields(JdbcClient jdbc) {
            super(jdbc, "LAND_PARCEL", Permission.FARM_WRITE, "sf_land_parcels", "x.name", "f.name",
                    "join sf_farms f on f.id = x.farm_id and f.tenant_id = x.tenant_id", "LAND_PARCEL_NOT_FOUND");
        }

        @Override protected String deleteSet() { return ", active = false"; }
        @Override protected String restoreSet() { return ", active = true"; }

        @Override
        protected void beforeRestore(TenantContext ctx, long id) {
            jdbc.sql("""
                            select f.name, f.deleted_at is not null from sf_land_parcels p join sf_farms f on f.id = p.farm_id and f.tenant_id = p.tenant_id
                            where p.id = :id and p.tenant_id = :t""")
                    .param("id", id).param("t", ctx.tenantId()).query((rs, i) -> { requireParentKept(rs.getBoolean(2), rs.getString(1)); return 0; }).list();
        }

        @Override
        protected void erase(TenantContext ctx, long id) {
            jdbc.sql("delete from sf_land_parcels where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        }
    }
}
