package com.smartfarm360.platform.security;

import com.smartfarm360.support.ApiIntegrationTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every endpoint under /api/v1/tenants/{tenantId} is called by a member of another tenant and must answer
 * 404 TENANT_NOT_FOUND without touching data. The list comes from the live handler mapping, so a new endpoint is
 * covered as soon as it exists. Object-level tests below check ids from another tenant inside the caller's own tenant.
 */
class CrossTenantIsolationTest extends ApiIntegrationTest {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    record Endpoint(RequestMethod method, String pattern) {}

    private List<Endpoint> tenantEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.contains("{tenantId}")) continue;
                for (RequestMethod method : info.getMethodsCondition().getMethods()) endpoints.add(new Endpoint(method, pattern));
            }
        }
        return endpoints;
    }

    @TestFactory
    Stream<DynamicTest> everyTenantEndpointRejectsNonMembers() throws Exception {
        User ownerA = newUser("iso-owner-a");
        long tenantA = newTenant(ownerA, "Isolation A");
        User ownerB = newUser("iso-owner-b");
        newTenant(ownerB, "Isolation B");
        List<Endpoint> endpoints = tenantEndpoints();
        assertThat(endpoints).as("tenant endpoints discovered").hasSizeGreaterThan(30);

        return endpoints.stream().flatMap(e -> Stream.of(
                DynamicTest.dynamicTest(e.method() + " " + e.pattern() + " as member of another tenant", () -> expectTenantNotFound(e, tenantA, ownerB)),
                DynamicTest.dynamicTest(e.method() + " " + e.pattern() + " for a tenant that does not exist", () -> expectTenantNotFound(e, 987654321L, ownerB))));
    }

    private void expectTenantNotFound(Endpoint e, long tenantId, User caller) throws Exception {
        // Other path variables get a placeholder; {module} must be a valid enum value to reach the interceptor check.
        String url = e.pattern().replace("{tenantId}", String.valueOf(tenantId)).replace("{module}", "CROP")
                .replaceAll("\\{[^}]+}", "1");
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(e.method().name()), url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
        if (e.method() == RequestMethod.POST || e.method() == RequestMethod.PUT || e.method() == RequestMethod.PATCH) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        long auditBefore = jdbc.queryForObject("select count(*) from sf_audit_history", Long.class);
        mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from sf_audit_history", Long.class)).isEqualTo(auditBefore);
    }

    @Test
    void anotherTenantsFarmAndParcelAreInvisibleInsideMyTenant() throws Exception {
        User a = newUser("idor-a");
        long tenantA = newTenant(a, "IDOR A");
        User b = newUser("idor-b");
        long tenantB = newTenant(b, "IDOR B");
        long farmB = newFarm(b, tenantB, "B farm");
        long parcelB = body(post("/api/v1/tenants/" + tenantB + "/farms/" + farmB + "/land-parcels", b, Map.of("name", "B field", "area", 2)))
                .get("id").asLong();

        get("/api/v1/tenants/" + tenantA + "/farms/" + farmB, a).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FARM_NOT_FOUND"));
        patch("/api/v1/tenants/" + tenantA + "/farms/" + farmB, a, Map.of("name", "stolen")).andExpect(status().isNotFound());
        delete("/api/v1/tenants/" + tenantA + "/farms/" + farmB, a).andExpect(status().isNotFound());
        get("/api/v1/tenants/" + tenantA + "/farms/" + farmB + "/land-parcels", a).andExpect(status().isNotFound());
        post("/api/v1/tenants/" + tenantA + "/farms/" + farmB + "/land-parcels", a, Map.of("name", "x", "area", 1)).andExpect(status().isNotFound());
        get("/api/v1/tenants/" + tenantA + "/land-parcels/" + parcelB, a).andExpect(status().isNotFound());
        patch("/api/v1/tenants/" + tenantA + "/land-parcels/" + parcelB, a, Map.of("name", "stolen")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select name from sf_farms where id = ?", String.class, farmB)).isEqualTo("B farm");
        assertThat(jdbc.queryForObject("select active from sf_farms where id = ?", Boolean.class, farmB)).isTrue();
    }

    @Test
    void cropAndFinanceRecordsCannotReferenceAnotherTenantsRows() throws Exception {
        User a = newUser("ref-a");
        long tenantA = newTenant(a, "Ref A");
        long farmA = newFarm(a, tenantA, "A farm");
        long parcelA = body(post("/api/v1/tenants/" + tenantA + "/farms/" + farmA + "/land-parcels", a, Map.of("name", "A field", "area", 1))).get("id").asLong();
        long cropA = body(post("/api/v1/tenants/" + tenantA + "/crops", a, Map.of("name", "Paddy"))).get("id").asLong();

        User b = newUser("ref-b");
        long tenantB = newTenant(b, "Ref B");
        long farmB = newFarm(b, tenantB, "B farm");
        long cropB = body(post("/api/v1/tenants/" + tenantB + "/crops", b, Map.of("name", "Groundnut"))).get("id").asLong();
        String stockB = "/api/v1/tenants/" + tenantB + "/stock";
        long itemB = body(post(stockB + "/items", b, Map.of("name", "Urea", "kind", "INPUT", "baseUnit", "kg"))).get("id").asLong();
        post(stockB + "/produce", b, Map.of("docDate", LocalDate.now().toString(), "lines", List.of(Map.of("itemId", itemB, "quantity", 10))))
                .andExpect(status().isBadRequest()); // urea is not farm produce
        post("/api/v1/tenants/" + tenantB + "/finance/bills", b, Map.of("docDate", LocalDate.now().toString(), "financialAccountId", cashAccountId(b, tenantB),
                "lines", List.of(Map.of("itemId", itemB, "quantity", 10, "rate", 5)))).andExpect(status().isCreated());
        long locationB = body(get(stockB + "/locations", b)).get(0).get("id").asLong();
        String today = LocalDate.now().toString();

        // Cycle on A's farm with B's crop, and on B's farm with A's crop
        post("/api/v1/tenants/" + tenantA + "/cultivation-cycles", a, Map.of("farmId", farmA, "landParcelId", parcelA, "cropId", cropB,
                "name", "x", "plannedSowingDate", today)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CROP_NOT_FOUND"));
        post("/api/v1/tenants/" + tenantA + "/cultivation-cycles", a, Map.of("farmId", farmB, "landParcelId", parcelA, "cropId", cropA,
                "name", "x", "plannedSowingDate", today)).andExpect(status().isNotFound());
        get("/api/v1/tenants/" + tenantA + "/crops/" + cropB + "/varieties", a).andExpect(status().isNotFound());
        post("/api/v1/tenants/" + tenantA + "/finance/bills", a, Map.of("docDate", today, "farmId", farmB, "financialAccountId", cashAccountId(a, tenantA),
                "lines", List.of(Map.of("categoryId", categoryId("SEEDS"), "amount", 10)))).andExpect(status().isNotFound());
        // Stock: A cannot buy, sell, use, move, count or read B's item or B's location
        String stockA = "/api/v1/tenants/" + tenantA + "/stock";
        post("/api/v1/tenants/" + tenantA + "/finance/bills", a, Map.of("docDate", today, "financialAccountId", cashAccountId(a, tenantA),
                "lines", List.of(Map.of("itemId", itemB, "quantity", 1, "rate", 1)))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_ITEM_NOT_FOUND"));
        post("/api/v1/tenants/" + tenantA + "/finance/sales", a, Map.of("docDate", today, "financialAccountId", cashAccountId(a, tenantA),
                "lines", List.of(Map.of("itemId", itemB, "quantity", 1, "rate", 1, "categoryId", categoryId("CROP_SALE"))))).andExpect(status().isNotFound());
        post(stockA + "/issues", a, Map.of("docDate", today, "lines", List.of(Map.of("itemId", itemB, "quantity", 1)))).andExpect(status().isNotFound());
        post(stockA + "/wastage", a, Map.of("docDate", today, "reason", "x", "lines", List.of(Map.of("itemId", itemB, "quantity", 1))))
                .andExpect(status().isNotFound());
        post(stockA + "/counts", a, Map.of("docDate", today, "lines", List.of(Map.of("itemId", itemB, "countedQuantity", 0)))).andExpect(status().isNotFound());
        post(stockA + "/issues", a, Map.of("docDate", today, "locationId", locationB, "lines", List.of(Map.of("itemId", itemB, "quantity", 1))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
        get(stockA + "/items/" + itemB, a).andExpect(status().isNotFound());
        get(stockA + "/items/" + itemB + "/movements", a).andExpect(status().isNotFound());
        patch(stockA + "/items/" + itemB, a, Map.of("name", "Taken")).andExpect(status().isNotFound());
        patch(stockA + "/locations/" + locationB, a, Map.of("name", "Taken")).andExpect(status().isNotFound());
        assertThat(body(get(stockA + "/items", a)).size()).isZero();

        assertThat(jdbc.queryForObject("select on_hand_quantity from sf_stock_items where id = ?", java.math.BigDecimal.class, itemB))
                .isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select name from sf_stock_items where id = ?", String.class, itemB)).isEqualTo("Urea");
        assertThat(jdbc.queryForObject("select count(*) from sf_stock_movements where tenant_id = ?", Integer.class, tenantA)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_cultivation_cycles where tenant_id = ?", Integer.class, tenantA)).isZero();
    }

    @Test
    void membersAndInvitationsOfAnotherTenantCannotBeChanged() throws Exception {
        User a = newUser("mem-a");
        long tenantA = newTenant(a, "Mem A");
        User b = newUser("mem-b");
        long tenantB = newTenant(b, "Mem B");
        long membershipB = jdbc.queryForObject("select id from sf_tenant_memberships where tenant_id = ?", Long.class, tenantB);
        long invitationB = body(post("/api/v1/tenants/" + tenantB + "/invitations", b, Map.of("email", uniqueEmail("guest"), "roles", List.of("VIEWER"))))
                .get("id").asLong();

        put("/api/v1/tenants/" + tenantA + "/members/" + membershipB + "/roles", a, Map.of("roles", List.of("VIEWER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        delete("/api/v1/tenants/" + tenantA + "/members/" + membershipB, a).andExpect(status().isNotFound());
        delete("/api/v1/tenants/" + tenantA + "/invitations/" + invitationB, a).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select active from sf_tenant_memberships where id = ?", Boolean.class, membershipB)).isTrue();
    }

    @Test
    void listsOnlyContainTheCallersTenantData() throws Exception {
        User a = newUser("list-a");
        long tenantA = newTenant(a, "List A");
        newFarm(a, tenantA, "Only A");
        User b = newUser("list-b");
        long tenantB = newTenant(b, "List B");
        newFarm(b, tenantB, "Only B");

        get("/api/v1/tenants/" + tenantA + "/farms", a).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Only A"));
        get("/api/v1/tenants", a).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(tenantA));
        get("/api/v1/tenants/" + tenantA + "/audit", a).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.action == 'FARM_CREATED')].details.name").value(org.hamcrest.Matchers.contains("Only A")));
    }
}
