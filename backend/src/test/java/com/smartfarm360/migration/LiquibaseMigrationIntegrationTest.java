package com.smartfarm360.migration;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.rbac.RoleCode;
import com.smartfarm360.support.EmbeddedPostgresSupport;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Applies the full changelog to an empty PostgreSQL database. The Spring context only starts if
 * Hibernate's ddl-auto=validate accepts the resulting schema, so this also checks entity mappings.
 */
@SpringBootTest
class LiquibaseMigrationIntegrationTest extends EmbeddedPostgresSupport {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void appliesEveryChangesetOnACleanDatabase() {
        List<String> applied = jdbc.queryForList(
                "select id from databasechangelog order by orderexecuted", String.class);
        assertThat(applied).containsExactly(
                "025-smartfarm360-foundation",
                "026-smartfarm360-crop-operations",
                "027-smartfarm360-finance-procurement",
                "028-platform-schema",
                "029-platform-seed-rbac",
                "030-platform-backfill");
    }

    @Test
    void createsTheExpectedTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' and table_name like 'sf\\_%'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "sf_users", "sf_tenants", "sf_tenant_memberships", "sf_farms", "sf_land_parcels",
                "sf_crop_masters", "sf_crop_varieties", "sf_crop_seasons", "sf_cultivation_cycles",
                "sf_farm_activities", "sf_harvest_records", "sf_finance_entries", "sf_suppliers",
                "sf_inventory_items", "sf_purchases",
                "sf_permissions", "sf_roles", "sf_role_permissions", "sf_membership_roles", "sf_refresh_tokens",
                "sf_password_reset_tokens", "sf_invitations", "sf_invitation_roles", "sf_tenant_settings",
                "sf_tenant_modules", "sf_audit_history");
    }

    @Test
    void seededPermissionsAndRolesMatchTheJavaEnums() {
        assertThat(jdbc.queryForList("select code from sf_permissions", String.class))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Permission.values()).map(Enum::name).toList());
        assertThat(jdbc.queryForList("select code from sf_roles where tenant_id is null", String.class))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RoleCode.values()).map(Enum::name).toList());
        // Owners and farm administrators hold every permission; viewers hold only read permissions.
        assertThat(permissionsOf("TENANT_OWNER")).hasSize(Permission.values().length);
        assertThat(permissionsOf("VIEWER")).allMatch(p -> p.endsWith("_READ")).doesNotContain("AUDIT_READ", "MEMBERS_READ");
    }

    @Test
    void legacyMembershipRolesAreMappedWhenUpgradingAnExistingDatabase() throws Exception {
        DataSource legacy = newDatabase("legacy_" + UUID.randomUUID().toString().replace("-", ""));
        JdbcTemplate db = new JdbcTemplate(legacy);
        runChangelog(legacy, 3); // the schema as it was before Phase 2 (changesets 025-027)

        long tenant = db.queryForObject("insert into sf_tenants(name, slug) values ('Legacy', 'legacy') returning id", Long.class);
        Map<String, String> expected = Map.of("OWNER", "TENANT_OWNER", "ADMIN", "FARM_ADMIN", "FARM_MANAGER", "FARM_MANAGER", "SOMETHING_ELSE", "VIEWER");
        for (String legacyRole : expected.keySet()) {
            long user = db.queryForObject("insert into sf_users(email, password_hash, display_name) values (?, 'x', 'u') returning id",
                    Long.class, legacyRole.toLowerCase() + "@legacy.test");
            db.update("insert into sf_tenant_memberships(tenant_id, user_id, role) values (?, ?, ?)", tenant, user, legacyRole);
        }
        db.update("insert into sf_farms(tenant_id, name) values (?, 'Old farm')", tenant);

        runChangelog(legacy, null);

        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(db.queryForList("""
                    select r.code from sf_membership_roles mr join sf_roles r on r.id = mr.role_id
                    join sf_tenant_memberships m on m.id = mr.membership_id where m.role = ?""", String.class, e.getKey()))
                    .as("legacy role %s", e.getKey()).containsExactly(e.getValue());
        }
        assertThat(db.queryForObject("select count(*) from sf_tenant_settings where tenant_id = ?", Integer.class, tenant)).isEqualTo(1);
        assertThat(db.queryForObject("select enabled from sf_tenant_modules where tenant_id = ? and module_code = 'CROP'", Boolean.class, tenant)).isTrue();
        assertThat(db.queryForObject("select count(*) from sf_farms where tenant_id = ?", Integer.class, tenant)).as("data preserved").isEqualTo(1);
    }

    @Test
    void landParcelCannotPointAtAnotherTenantsFarm() {
        long tenantA = tenant();
        long tenantB = tenant();
        long farmA = jdbc.queryForObject("insert into sf_farms(tenant_id, name) values (?, 'Farm A') returning id", Long.class, tenantA);
        assertThatThrownBy(() -> jdbc.update("insert into sf_land_parcels(tenant_id, farm_id, name, area) values (?, ?, 'x', 1)", tenantB, farmA))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_sf_parcel_farm_tenant");
    }

    private List<String> permissionsOf(String role) {
        return jdbc.queryForList("select rp.permission_code from sf_role_permissions rp join sf_roles r on r.id = rp.role_id where r.code = ?",
                String.class, role);
    }

    /** Applies {@code count} changesets (all when null) to the given database. */
    private static void runChangelog(DataSource target, Integer count) throws Exception {
        try (Connection connection = target.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                if (count == null) liquibase.update(new Contexts(), new LabelExpression());
                else liquibase.update(count, new Contexts(), new LabelExpression());
            }
        }
    }

    @Test
    void compositeForeignKeysRejectCrossTenantReferences() {
        long tenantA = tenant();
        long tenantB = tenant();
        long farmA = jdbc.queryForObject(
                "insert into sf_farms(tenant_id, name) values (?, 'Farm A') returning id", Long.class, tenantA);
        long parcelA = jdbc.queryForObject(
                "insert into sf_land_parcels(tenant_id, farm_id, name, area) values (?, ?, 'Field 1', 1.5) returning id",
                Long.class, tenantA, farmA);
        long cropB = jdbc.queryForObject(
                "insert into sf_crop_masters(tenant_id, name) values (?, 'Paddy') returning id", Long.class, tenantB);

        // Tenant B cannot plant on tenant A's farm and parcel, even if service checks were bypassed.
        assertThatThrownBy(() -> jdbc.update(
                "insert into sf_cultivation_cycles(tenant_id, farm_id, land_parcel_id, crop_id, name, planned_sowing_date) "
                        + "values (?, ?, ?, ?, 'Cross-tenant cycle', current_date)",
                tenantB, farmA, parcelA, cropB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sf_cycle_farm_tenant");

        // Tenant B cannot attach a finance entry to tenant A's farm.
        assertThatThrownBy(() -> jdbc.update(
                "insert into sf_finance_entries(tenant_id, farm_id, entry_type, category, description, amount, occurred_on, created_by) "
                        + "values (?, ?, 'EXPENSE', 'Seeds', 'Cross-tenant', 10.00, current_date, 'test@example.com')",
                tenantB, farmA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sf_finance_farm_tenant");
    }

    @Test
    void rerunningMigrationsOnAPopulatedDatabaseIsANoOpAndKeepsData() throws Exception {
        long tenant = tenant();
        jdbc.update("insert into sf_farms(tenant_id, name) values (?, 'Existing farm')", tenant);
        int changesetsBefore = count("databasechangelog");
        int farmsBefore = count("sf_farms");

        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                assertThat(liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression())).isEmpty();
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }

        assertThat(count("databasechangelog")).isEqualTo(changesetsBefore);
        assertThat(count("sf_farms")).isEqualTo(farmsBefore);
    }

    private long tenant() {
        String slug = "t-" + UUID.randomUUID();
        return jdbc.queryForObject(
                "insert into sf_tenants(name, slug) values (?, ?) returning id", Long.class, slug, slug);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
