package com.smartfarm360.finance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Object-level isolation inside finance (ids from another tenant used in my tenant's requests) and role permissions.
 * Path-level isolation for every finance endpoint is covered by CrossTenantIsolationTest.
 */
class FinanceIsolationTest extends FinanceTestSupport {

    @Test
    void anotherTenantsFinanceIdsAreUnusable() throws Exception {
        newFinanceTenant("iso-b");
        long tenantB = tenant;
        User ownerB = owner;
        long cashB = cash;
        long partyB = party("B trader");
        long categoryB = id(post(f("/categories"), ownerB, Map.of("name", "B special", "kind", "EXPENSE", "accountCode", "5600")));
        long billB = invoice("bills", "2026-07-02", partyB, null, line("SEEDS", 100, null)).at("/summary/id").asLong();
        long costObjectB = body(get(f("/cost-objects"), ownerB)).get(0).get("id").asLong();

        newFinanceTenant("iso-a");
        long partyA = party("A trader");
        long billA = invoice("bills", "2026-07-02", partyA, null, line("SEEDS", 100, null)).at("/summary/id").asLong();

        post(f("/bills"), owner, Map.of("docDate", "2026-07-03", "partyId", partyB, "lines", List.of(line("SEEDS", 1, null))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PARTY_NOT_FOUND"));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-03", "financialAccountId", cashB, "lines", List.of(line("SEEDS", 1, null))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FINANCIAL_ACCOUNT_NOT_FOUND"));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-03", "financialAccountId", cash, "lines", List.of(Map.of("categoryId", categoryB, "amount", 1))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-03", "financialAccountId", cash, "lines", List.of(line("SEEDS", 1, costObjectB))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COST_OBJECT_NOT_FOUND"));
        get(f("/documents/" + billB), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        post(f("/documents/" + billB + "/reverse"), owner, Map.of()).andExpect(status().isNotFound());
        post(f("/payments"), owner, Map.of("docDate", "2026-07-03", "partyId", partyA, "financialAccountId", cash, "amount", 10,
                "allocations", List.of(Map.of("documentId", billB, "amount", 10)))).andExpect(status().isNotFound());
        get(f("/parties/" + partyB), owner).andExpect(status().isNotFound());

        // Tenant B's records are untouched and its reports do not see tenant A.
        assertThat(jdbc.queryForObject("select status from sf_finance_documents where id = ?", String.class, billB)).isEqualTo("POSTED");
        money(body(get("/api/v1/tenants/" + tenantB + "/finance/parties/" + partyB, ownerB)).get("payable"), "100");
        assertThat(billA).isNotEqualTo(billB);
    }

    @Test
    void financePermissionsFollowRoles() throws Exception {
        newFinanceTenant("roles");
        long trader = party("Trader");
        long bill = invoice("bills", "2026-07-02", null, cash, line("SEEDS", 100, null)).at("/summary/id").asLong();
        Map<String, Object> newBill = Map.of("docDate", "2026-07-03", "financialAccountId", cash, "lines", List.of(line("SEEDS", 5, null)));

        User viewer = newUser("fin-viewer");
        addMember(owner, tenant, viewer, "VIEWER");
        get(f("/reports/summary"), viewer).andExpect(status().isOk());
        post(f("/bills"), viewer, newBill).andExpect(status().isForbidden());

        User manager = newUser("fin-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        post(f("/bills"), manager, newBill).andExpect(status().isCreated());
        post(f("/documents/" + bill + "/reverse"), manager, Map.of()).andExpect(status().isForbidden());
        put(f("/settings"), manager, Map.of("fiscalYearStartMonth", 4)).andExpect(status().isForbidden());
        post(f("/opening-balances"), manager, Map.of("docDate", "2026-03-31", "lines", List.of(Map.of("kind", "ASSET", "amount", 1))))
                .andExpect(status().isForbidden());

        User accountant = newUser("fin-accountant");
        addMember(owner, tenant, accountant, "ACCOUNTANT");
        post(f("/documents/" + bill + "/reverse"), accountant, Map.of()).andExpect(status().isOk());
        put(f("/settings"), accountant, Map.of("fiscalYearStartMonth", 4)).andExpect(status().isOk());

        User worker = newUser("fin-worker");
        addMember(owner, tenant, worker, "WORKER");
        get(f("/documents"), worker).andExpect(status().isForbidden());
        post(f("/receipts"), worker, Map.of("docDate", "2026-07-03", "partyId", trader, "financialAccountId", cash, "amount", 1))
                .andExpect(status().isForbidden());

        // Every post and reversal is audited
        assertThat(jdbc.queryForObject("select count(*) from sf_audit_history where tenant_id = ? and action in ('FINANCE_DOCUMENT_POSTED', 'FINANCE_DOCUMENT_REVERSED')",
                Long.class, tenant)).isEqualTo(3);
    }
}
