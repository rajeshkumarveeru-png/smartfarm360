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
                "034-finance-legacy-migration",
                "035-stock-schema",
                "036-stock-guards-and-seed",
                "037-stock-legacy-migration",
                "038-crop-v2-schema",
                "039-crop-v2-backfill");
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
                "sf_document_sequences",
                "sf_units", "sf_stock_locations", "sf_stock_items", "sf_item_units", "sf_stock_lots", "sf_stock_movements", "sf_stock_balances",
                "sf_crop_observations", "sf_weather_notes");
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

        runChangelog(legacy, 4); // the Phase 3 finance changesets (031-034); the Phase 4 stock migration is tested below

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
    void phaseOneStockBecomesItemsWithMovementsThatMatchTheLedger() throws Exception {
        DataSource legacy = newDatabase("legacy_stock_" + UUID.randomUUID().toString().replace("-", ""));
        JdbcTemplate db = new JdbcTemplate(legacy);
        runChangelog(legacy, 6); // up to Phase 2

        long tenant = db.queryForObject("insert into sf_tenants(name, slug) values ('Stock farm', 'stock-farm') returning id", Long.class);
        long supplier = db.queryForObject("insert into sf_suppliers(tenant_id, name) values (?, 'Agro depot') returning id", Long.class, tenant);
        // Urea: 20 kg typed in at Rs 5, then 100 kg bought at Rs 6. Phase 1 rounded the average to 5.83.
        long urea = db.queryForObject("insert into sf_inventory_items(tenant_id, name, category, unit, quantity_on_hand, average_unit_cost) " +
                "values (?, 'Urea', 'Fertilizer', ' Kg. ', 120, 5.83) returning id", Long.class, tenant);
        db.update("insert into sf_purchases(tenant_id, supplier_id, item_id, purchase_date, quantity, unit_cost, total_amount, paid_amount, created_by) " +
                "values (?, ?, ?, '2026-06-05', 100, 6, 600, 0, 'old@farm.test')", tenant, supplier, urea);
        // Seed in "bag", a unit the catalog does not know; nothing bought since it was typed in
        db.update("insert into sf_inventory_items(tenant_id, name, category, unit, quantity_on_hand, average_unit_cost) " +
                "values (?, 'Paddy seed', 'Seeds', 'bag', 5, 800)", tenant);
        // Neem oil: the Phase 1 quantity is lower than what was bought (data typed in by hand)
        long neem = db.queryForObject("insert into sf_inventory_items(tenant_id, name, category, unit, quantity_on_hand, average_unit_cost) " +
                "values (?, 'Neem oil', 'Pesticide', 'ltr', 2, 300) returning id", Long.class, tenant);
        db.update("insert into sf_purchases(tenant_id, supplier_id, item_id, purchase_date, quantity, unit_cost, total_amount, paid_amount, created_by) " +
                "values (?, ?, ?, '2026-06-07', 10, 300, 3000, 0, 'old@farm.test')", tenant, supplier, neem);
        long lowerUrea = db.queryForObject("insert into sf_inventory_items(tenant_id, name, category, unit) values (?, 'urea', 'Fertilizer', 'kg') returning id",
                Long.class, tenant);

        runChangelog(legacy, 4); // Phase 3 finance migration
        // A purchase made on the Phase 3 stock screen: 30 kg at Rs 7, posted as a bill to Stock (1200)
        db.update("update sf_inventory_items set quantity_on_hand = 150, average_unit_cost = 6.06 where id = ?", urea);
        db.update("insert into sf_purchases(tenant_id, supplier_id, item_id, purchase_date, quantity, unit_cost, total_amount, paid_amount, created_by) " +
                "values (?, ?, ?, '2026-07-01', 30, 7, 210, 0, 'old@farm.test')", tenant, supplier, urea);
        long party = db.queryForObject("select id from sf_parties where legacy_supplier_id = ?", Long.class, supplier);
        long bill = db.queryForObject("insert into sf_finance_documents(tenant_id, doc_type, status, number, doc_date, party_id, total_amount, posted_at) " +
                "values (?, 'BILL', 'POSTED', 'BILL/2026-27/0001', '2026-07-01', ?, 210, now()) returning id", Long.class, tenant, party);
        db.update("insert into sf_finance_document_lines(tenant_id, document_id, line_no, account_code, description, quantity, unit, rate, amount) " +
                "values (?, ?, 1, '1200', 'Urea', 30, ' Kg. ', 7, 210)", tenant, bill);
        long entry = db.queryForObject("insert into sf_journal_entries(tenant_id, document_id, entry_date, kind) values (?, ?, '2026-07-01', 'POSTING') returning id",
                Long.class, tenant, bill);
        db.update("insert into sf_journal_lines(tenant_id, entry_id, account_code, debit, credit, party_id) " +
                "values (?, ?, '1200', 210, 0, null), (?, ?, '2000', 0, 210, ?)", tenant, entry, tenant, entry, party);

        runChangelog(legacy, null);

        assertThat(db.queryForList("select name from sf_stock_items where tenant_id = ? order by legacy_item_id", String.class, tenant))
                .containsExactly("Urea", "Paddy seed", "Neem oil", "urea (" + lowerUrea + ")");
        Map<String, Object> u = db.queryForMap("select base_unit_code, on_hand_quantity, on_hand_value from sf_stock_items where legacy_item_id = ?", urea);
        assertThat(u.get("base_unit_code")).isEqualTo("kg");
        assertThat((BigDecimal) u.get("on_hand_quantity")).isEqualByComparingTo("150");
        // 150 x 6.06 = 909.00: purchases 600 + 210, and the 20 kg typed in Phase 1 open at 99.00 (from its rounded average)
        assertThat((BigDecimal) u.get("on_hand_value")).isEqualByComparingTo("909");
        assertThat(db.queryForList("select m.movement_type || ' ' || round(m.quantity) || ' ' || m.value from sf_stock_movements m " +
                "join sf_stock_items i on i.id = m.item_id where i.legacy_item_id = ? order by m.movement_date, m.id", String.class, urea))
                .containsExactly("PURCHASE 100 600.00", "OPENING 20 99.00", "PURCHASE 30 210.00");
        assertThat(db.queryForObject("select item_id is not null from sf_finance_document_lines where document_id = ?", Boolean.class, bill)).isTrue();

        Map<String, Object> seed = db.queryForMap("select base_unit_code, custom_unit_name, on_hand_value, needs_review from sf_stock_items where name = 'Paddy seed'");
        assertThat(seed.get("base_unit_code")).isEqualTo("piece");
        assertThat(seed.get("custom_unit_name")).isEqualTo("bag");
        assertThat(seed.get("needs_review")).isEqualTo(true);
        assertThat((BigDecimal) seed.get("on_hand_value")).isEqualByComparingTo("4000");

        // Neem oil: 10 bought, 2 left. The 8 missing litres become a stock loss to review, at their cost.
        Map<String, Object> n = db.queryForMap("select base_unit_code, on_hand_quantity, on_hand_value, needs_review from sf_stock_items where legacy_item_id = ?", neem);
        assertThat(n.get("base_unit_code")).isEqualTo("litre");
        assertThat((BigDecimal) n.get("on_hand_quantity")).isEqualByComparingTo("2");
        assertThat((BigDecimal) n.get("on_hand_value")).isEqualByComparingTo("600");
        assertThat(n.get("needs_review")).isEqualTo(true);
        assertThat(db.queryForObject("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = ? and account_code = '5980'",
                BigDecimal.class, tenant)).isEqualByComparingTo("2400");
        assertThat(db.queryForObject("select needs_review from sf_finance_documents where legacy_source = 'stock-shortfall'", Boolean.class)).isTrue();

        // The ledger agrees with the store: 909 + 4000 + 600 + 0
        assertThat(db.queryForObject("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = ? and account_code = '1200'",
                BigDecimal.class, tenant)).isEqualByComparingTo("5509");
        assertThat(db.queryForObject("select coalesce(sum(credit - debit), 0) from sf_journal_lines where tenant_id = ? and account_code = '3900'",
                BigDecimal.class, tenant)).isEqualByComparingTo("4099");
        assertThat(db.queryForObject("select count(*) from (select entry_id from sf_journal_lines group by entry_id having sum(debit) <> sum(credit)) x",
                Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from sf_stock_items i where on_hand_quantity <> " +
                "(select coalesce(sum(quantity), 0) from sf_stock_balances b where b.item_id = i.id)", Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from sf_stock_locations where tenant_id = ? and is_default", Integer.class, tenant)).isEqualTo(1);

        // The Phase 1 stock tables keep their rows and become read-only; movements are append-only
        assertThat(db.queryForObject("select count(*) from sf_purchases", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> db.update("update sf_inventory_items set quantity_on_hand = 0")).hasMessageContaining("read-only");
        assertThatThrownBy(() -> db.update("delete from sf_purchases")).hasMessageContaining("read-only");
        assertThatThrownBy(() -> db.update("insert into sf_suppliers(tenant_id, name) values (?, 'x')", tenant)).hasMessageContaining("read-only");
        assertThatThrownBy(() -> db.update("delete from sf_stock_movements")).hasMessageContaining("append-only");
    }

    @Test
    void phaseOneCropRecordsAreKeptAsHistoryWithStagesAndAllocationData() throws Exception {
        DataSource legacy = newDatabase("legacy_crop_" + UUID.randomUUID().toString().replace("-", ""));
        JdbcTemplate db = new JdbcTemplate(legacy);
        runChangelog(legacy, 13); // everything before Phase 5 (changesets 025-037)

        long tenant = db.queryForObject("insert into sf_tenants(name, slug) values ('Crop farm', 'crop-farm') returning id", Long.class);
        long farm = db.queryForObject("insert into sf_farms(tenant_id, name) values (?, 'Farm') returning id", Long.class, tenant);
        long parcel = db.queryForObject("insert into sf_land_parcels(tenant_id, farm_id, name, area, area_unit) values (?, ?, 'F1', 1, 'HECTARE') returning id",
                Long.class, tenant, farm);
        long crop = db.queryForObject("insert into sf_crop_masters(tenant_id, name) values (?, 'Paddy') returning id", Long.class, tenant);
        String insertCycle = "insert into sf_cultivation_cycles(tenant_id, farm_id, land_parcel_id, crop_id, name, planned_sowing_date, expected_harvest_date, " +
                "cultivated_area, area_unit, status) values (?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?) returning id";
        long done = db.queryForObject(insertCycle, Long.class, tenant, farm, parcel, crop, "Old samba", "2025-07-01", "2025-11-30", 50, "CENT", "COMPLETED");
        long growing = db.queryForObject(insertCycle, Long.class, tenant, farm, parcel, crop, "Thaladi", "2026-09-01", null, null, "ACRE", "IN_PROGRESS");
        long odd = db.queryForObject(insertCycle, Long.class, tenant, farm, parcel, crop, "Typo", "2026-09-01", null, null, "ACRE", "SOMETHING");
        db.update("insert into sf_farm_activities(tenant_id, cycle_id, activity_type, activity_date, input_name, quantity, quantity_unit, cost) " +
                "values (?, ?, 'FERTILIZER', '2025-08-01', 'Urea', 50, 'kg', 600)", tenant, done);
        db.update("insert into sf_harvest_records(tenant_id, cycle_id, harvest_date, quantity, quantity_unit, sale_value) " +
                "values (?, ?, '2025-11-30', 20, 'bags', 25000)", tenant, done);
        long linesBefore = db.queryForObject("select count(*) from sf_journal_lines", Long.class);

        runChangelog(legacy, null);

        assertThat(db.queryForObject("select status from sf_cultivation_cycles where id = ?", String.class, done)).isEqualTo("CLOSED");
        assertThat(db.queryForObject("select closed_on::text from sf_cultivation_cycles where id = ?", String.class, done)).isEqualTo("2025-11-30");
        assertThat(db.queryForObject("select status from sf_cultivation_cycles where id = ?", String.class, growing)).isEqualTo("GROWING");
        assertThat(db.queryForObject("select status from sf_cultivation_cycles where id = ?", String.class, odd)).isEqualTo("PLANNED");
        // Kept as history: memo values unchanged, nothing posted, nothing put into stock (D7, F4)
        assertThat(db.queryForObject("select legacy_memo from sf_farm_activities where cycle_id = ?", Boolean.class, done)).isTrue();
        assertThat(db.queryForObject("select cost from sf_farm_activities where cycle_id = ?", BigDecimal.class, done)).isEqualByComparingTo("600");
        assertThat(db.queryForObject("select legacy from sf_harvest_records where cycle_id = ?", Boolean.class, done)).isTrue();
        assertThat(db.queryForObject("select sale_value from sf_harvest_records where cycle_id = ?", BigDecimal.class, done)).isEqualByComparingTo("25000");
        assertThat(db.queryForObject("select count(*) from sf_journal_lines", Long.class)).isEqualTo(linesBefore);
        assertThat(db.queryForObject("select count(*) from sf_stock_movements", Long.class)).isZero();
        // Every cycle is a cost object with its area in acres and dates, for overhead allocation
        Map<String, Object> co = db.queryForMap("select area_acres, active_from::text as f, active_to::text as t from sf_cost_objects where crop_cycle_id = ?", done);
        assertThat((BigDecimal) co.get("area_acres")).isEqualByComparingTo("0.5");
        assertThat(co.get("f")).isEqualTo("2025-07-01");
        assertThat(co.get("t")).isEqualTo("2025-11-30");
        assertThat(db.queryForObject("select area_acres from sf_cost_objects where crop_cycle_id = ?", BigDecimal.class, growing))
                .isEqualByComparingTo("2.4711");
        assertThatThrownBy(() -> db.update("update sf_cultivation_cycles set status = 'IN_PROGRESS' where id = ?", growing))
                .hasMessageContaining("ck_sf_cycle_status");
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
