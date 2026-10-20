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
import java.math.BigDecimal;
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
                "030-platform-backfill",
                "031-finance-schema",
                "032-finance-journal-guards",
                "033-finance-seed",
                "034-finance-legacy-migration");
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
                "sf_tenant_modules", "sf_audit_history",
                "sf_ledger_accounts", "sf_finance_categories", "sf_financial_accounts", "sf_parties", "sf_cost_objects", "sf_loans",
                "sf_finance_documents", "sf_finance_document_lines", "sf_payment_allocations", "sf_journal_entries", "sf_journal_lines",
                "sf_document_sequences");
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
    void phaseOneMoneyRecordsBecomeBalancedFinanceDocuments() throws Exception {
        DataSource legacy = newDatabase("legacy_fin_" + UUID.randomUUID().toString().replace("-", ""));
        JdbcTemplate db = new JdbcTemplate(legacy);
        runChangelog(legacy, 6); // schema up to Phase 2 (changesets 025-030)

        long tenant = db.queryForObject("insert into sf_tenants(name, slug) values ('Old farm', 'old-farm') returning id", Long.class);
        long farm = db.queryForObject("insert into sf_farms(tenant_id, name) values (?, 'Farm') returning id", Long.class, tenant);
        long parcel = db.queryForObject("insert into sf_land_parcels(tenant_id, farm_id, name, area) values (?, ?, 'F1', 1) returning id", Long.class, tenant, farm);
        long crop = db.queryForObject("insert into sf_crop_masters(tenant_id, name) values (?, 'Paddy') returning id", Long.class, tenant);
        long cycle = db.queryForObject("insert into sf_cultivation_cycles(tenant_id, farm_id, land_parcel_id, crop_id, name, planned_sowing_date) " +
                "values (?, ?, ?, ?, 'Samba', current_date) returning id", Long.class, tenant, farm, parcel, crop);
        db.update("insert into sf_finance_entries(tenant_id, farm_id, entry_type, category, description, amount, occurred_on, payment_status, created_by) " +
                "values (?, ?, 'EXPENSE', 'seeds', 'Seed paddy', 1000, '2026-06-01', 'PAID', 'old@farm.test')", tenant, farm);
        db.update("insert into sf_finance_entries(tenant_id, entry_type, category, description, amount, occurred_on, payment_status, created_by) " +
                "values (?, 'INCOME', 'Crop sale', 'Paddy to trader', 5000, '2026-06-10', 'UNPAID', 'old@farm.test')", tenant);
        db.update("insert into sf_finance_entries(tenant_id, entry_type, category, description, amount, occurred_on, payment_status, created_by) " +
                "values (?, 'EXPENSE', 'Tractor diesel', 'Diesel', 300, '2026-06-12', 'PARTIAL', 'old@farm.test')", tenant);
        long supplier = db.queryForObject("insert into sf_suppliers(tenant_id, name) values (?, 'Agro depot') returning id", Long.class, tenant);
        long item = db.queryForObject("insert into sf_inventory_items(tenant_id, name, category, unit) values (?, 'Urea', 'Fertilizer', 'kg') returning id", Long.class, tenant);
        db.update("insert into sf_purchases(tenant_id, supplier_id, item_id, purchase_date, quantity, unit_cost, total_amount, paid_amount, created_by) " +
                "values (?, ?, ?, '2026-06-05', 100, 6, 600, 200, 'old@farm.test')", tenant, supplier, item);

        runChangelog(legacy, null);

        assertThat(db.queryForObject("select count(*) from sf_finance_documents where tenant_id = ? and legacy_source is not null", Integer.class, tenant)).isEqualTo(5);
        assertThat(db.queryForObject("select count(*) from (select entry_id from sf_journal_lines group by entry_id having sum(debit) <> sum(credit)) x", Integer.class)).isZero();
        assertThat(db.queryForObject("select needs_review from sf_finance_documents where legacy_source = 'sf_finance_entries' and total_amount = 300", Boolean.class)).isTrue();
        // "seeds" matched the system category; "Tractor diesel" became a tenant category under other expenses
        assertThat(db.queryForObject("select c.code from sf_finance_document_lines l join sf_finance_categories c on c.id = l.category_id " +
                "join sf_finance_documents d on d.id = l.document_id where d.total_amount = 1000 and d.legacy_source = 'sf_finance_entries'", String.class)).isEqualTo("SEEDS");
        assertThat(db.queryForObject("select account_code from sf_finance_categories where tenant_id = ? and name = 'Tractor diesel'", String.class, tenant)).isEqualTo("5600");
        // Balances: the unknown party holds the unpaid entries; the supplier is owed the unpaid part of the purchase
        assertThat(db.queryForObject("select coalesce(sum(debit - credit), 0) from sf_journal_lines l join sf_parties p on p.id = l.party_id " +
                "where p.name = 'Unknown (migrated)' and l.account_code = '1100'", BigDecimal.class)).isEqualByComparingTo("5000");
        assertThat(db.queryForObject("select coalesce(sum(credit - debit), 0) from sf_journal_lines l join sf_parties p on p.id = l.party_id " +
                "where p.name = 'Unknown (migrated)' and l.account_code = '2000'", BigDecimal.class)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("select coalesce(sum(credit - debit), 0) from sf_journal_lines l join sf_parties p on p.id = l.party_id " +
                "where p.legacy_supplier_id = ? and l.account_code = '2000'", BigDecimal.class, supplier)).isEqualByComparingTo("400");
        assertThat(db.queryForObject("select coalesce(sum(l.debit - l.credit), 0) from sf_journal_lines l join sf_financial_accounts f on f.id = l.financial_account_id " +
                "where f.name = 'Cash (migrated)'", BigDecimal.class)).isEqualByComparingTo("-1200");
        assertThat(db.queryForObject("select coalesce(sum(debit - credit), 0) from sf_journal_lines where account_code = '1200'", BigDecimal.class)).isEqualByComparingTo("600");
        // Defaults and cost objects
        assertThat(db.queryForObject("select count(*) from sf_cost_objects where tenant_id = ? and crop_cycle_id = ?", Integer.class, tenant, cycle)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from sf_cost_objects where tenant_id = ? and kind = 'OVERHEAD'", Integer.class, tenant)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from sf_financial_accounts where tenant_id = ? and name = 'Cash in hand'", Integer.class, tenant)).isEqualTo(1);
        // The old table keeps its rows and becomes read-only
        assertThat(db.queryForObject("select count(*) from sf_finance_entries", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> db.update("delete from sf_finance_entries")).hasMessageContaining("read-only");
    }

    @Test
    void ledgerAccountsMatchTheJavaEnum() {
        assertThat(jdbc.queryForList("select code from sf_ledger_accounts", String.class))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(com.smartfarm360.finance.LedgerAccount.values()).map(com.smartfarm360.finance.LedgerAccount::code).toList());
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

        // Tenant B cannot attach a finance record to tenant A's farm.
        assertThatThrownBy(() -> jdbc.update(
                "insert into sf_finance_documents(tenant_id, doc_type, status, doc_date, farm_id, total_amount) "
                        + "values (?, 'BILL', 'DRAFT', current_date, ?, 10.00)",
                tenantB, farmA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sf_doc_farm");
        // The Phase 1 finance table is read-only since the finance core migration.
        assertThatThrownBy(() -> jdbc.update(
                "insert into sf_finance_entries(tenant_id, entry_type, category, description, amount, occurred_on, created_by) "
                        + "values (?, 'EXPENSE', 'Seeds', 'x', 10.00, current_date, 'test@example.com')", tenantA))
                .hasMessageContaining("read-only");
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
