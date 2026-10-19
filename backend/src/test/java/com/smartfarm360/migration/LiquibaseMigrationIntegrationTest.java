package com.smartfarm360.migration;

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
import java.util.List;
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
                "027-smartfarm360-finance-procurement");
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
                "sf_inventory_items", "sf_purchases");
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
