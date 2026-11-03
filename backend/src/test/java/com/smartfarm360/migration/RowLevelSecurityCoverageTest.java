package com.smartfarm360.migration;

import com.smartfarm360.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table with a tenant_id has Row-Level Security turned on and forced, with the tenant isolation policy
 * (ADR 0011). A new tenant table without a policy fails here: add the policy in its migration.
 */
class RowLevelSecurityCoverageTest extends ApiIntegrationTest {
    @Test
    void everyTenantTableHasItsPolicyAndForcesIt() {
        List<String> tables = jdbc.queryForList("""
                select c.table_name from information_schema.columns c join information_schema.tables t
                  on t.table_schema = c.table_schema and t.table_name = c.table_name
                where c.table_schema = current_schema() and c.column_name = 'tenant_id' and t.table_type = 'BASE TABLE'""", String.class);
        assertThat(tables).hasSizeGreaterThan(80);
        List<String> unprotected = jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = current_schema() and c.relname = any (?::text[])
                  and not (c.relrowsecurity and c.relforcerowsecurity
                           and exists (select 1 from pg_policies p where p.schemaname = n.nspname and p.tablename = c.relname and p.policyname = 'sf_tenant_isolation'))""",
                String.class, (Object) tables.toArray(String[]::new));
        assertThat(unprotected).isEmpty();
    }
}
