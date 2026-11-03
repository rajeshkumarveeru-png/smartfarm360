package com.smartfarm360.platform.security;

import com.smartfarm360.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role-based access inside a tenant, enforced on the backend. */
class PermissionIntegrationTest extends ApiIntegrationTest {
    private User owner;
    private long tenant;
    private long farm;
    private long cycle;

    @BeforeEach
    void setUpTenant() throws Exception {
        owner = newUser("perm-owner");
        tenant = newTenant(owner, "Permissions farm");
        farm = newFarm(owner, tenant, "Main farm");
        long parcel = body(post(base() + "/farms/" + farm + "/land-parcels", owner, Map.of("name", "Field", "area", 1))).get("id").asLong();
        long crop = body(post(base() + "/crops", owner, Map.of("name", "Paddy"))).get("id").asLong();
        cycle = body(post(base() + "/cultivation-cycles", owner, Map.of("farmId", farm, "landParcelId", parcel, "cropId", crop,
                "name", "Samba", "plannedSowingDate", LocalDate.now().toString()))).get("id").asLong();
    }

    private String base() { return "/api/v1/tenants/" + tenant; }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        User viewer = newUser("viewer");
        addMember(owner, tenant, viewer, "VIEWER");
        get(base() + "/farms", viewer).andExpect(status().isOk());
        post(base() + "/farms", viewer, Map.of("name", "Nope")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        get(base() + "/finance/reports/summary", viewer).andExpect(status().isOk());
        post(base() + "/finance/bills", viewer, Map.of()).andExpect(status().isForbidden());
        get(base() + "/members", viewer).andExpect(status().isForbidden());
        get(base() + "/audit", viewer).andExpect(status().isForbidden());
    }

    @Test
    void workerCanRecordFieldWorkButNotChangeCropSetupOrFinance() throws Exception {
        User worker = newUser("worker");
        addMember(owner, tenant, worker, "WORKER");
        post(base() + "/activities", worker, Map.of("cycleId", cycle, "activityType", "WEEDING", "activityDate", LocalDate.now().toString()))
                .andExpect(status().isCreated());
        post(base() + "/crops", worker, Map.of("name", "Maize")).andExpect(status().isForbidden());
        get(base() + "/finance/documents", worker).andExpect(status().isForbidden());
    }

    @Test
    void accountantHandlesMoneyButNotFarmSetup() throws Exception {
        User accountant = newUser("accountant");
        addMember(owner, tenant, accountant, "ACCOUNTANT");
        post(base() + "/finance/bills", accountant, Map.of("docDate", LocalDate.now().toString(), "financialAccountId", cashAccountId(accountant, tenant),
                "lines", java.util.List.of(Map.of("categoryId", categoryId("SEEDS"), "amount", 1200.50)))).andExpect(status().isCreated());
        get(base() + "/audit", accountant).andExpect(status().isOk());
        post(base() + "/farms", accountant, Map.of("name", "Nope")).andExpect(status().isForbidden());
        post(base() + "/activities", accountant, Map.of("cycleId", cycle, "activityType", "WEEDING", "activityDate", LocalDate.now().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledModuleBlocksItsEndpointsAndUnavailableModulesCannotBeEnabled() throws Exception {
        put(base() + "/modules/CROP", owner, Map.of("enabled", false)).andExpect(status().isOk());
        get(base() + "/crops", owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        get("/api/v1/tenants", owner).andExpect(jsonPath("$[0].enabledModules").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("CROP"))));

        put(base() + "/modules/CROP", owner, Map.of("enabled", true)).andExpect(status().isOk());
        get(base() + "/crops", owner).andExpect(status().isOk());

        // With Processing (Phase 12) every module is available: each reports available and can be switched on.
        // The MODULE_NOT_AVAILABLE guard stays for future modules; no module is left to exercise it.
        for (com.smartfarm360.platform.tenant.FarmModule m : com.smartfarm360.platform.tenant.FarmModule.values()) {
            put(base() + "/modules/" + m.name(), owner, Map.of("enabled", true)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.code == '" + m.name() + "')].available").value(org.hamcrest.Matchers.contains(true)))
                    .andExpect(jsonPath("$[?(@.code == '" + m.name() + "')].enabled").value(org.hamcrest.Matchers.contains(true)));
        }
    }

    @Test
    void onlyManagersCanChangeTenantSettings() throws Exception {
        User manager = newUser("manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        Map<String, Object> settings = Map.of("timezone", "Asia/Kolkata", "currencyCode", "INR", "areaUnit", "HECTARE",
                "weightUnit", "QUINTAL", "volumeUnit", "LITRE");
        put(base() + "/settings", manager, settings).andExpect(status().isForbidden());
        put(base() + "/settings", owner, settings).andExpect(status().isOk()).andExpect(jsonPath("$.areaUnit").value("HECTARE"));
        put(base() + "/settings", owner, Map.of("timezone", "Mars/Olympus", "currencyCode", "INR", "areaUnit", "ACRE",
                "weightUnit", "KG", "volumeUnit", "LITRE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));
        get(base(), manager).andExpect(status().isOk()).andExpect(jsonPath("$.settings.weightUnit").value("QUINTAL"));
    }

    @Test
    void tenantListShowsTheCallersOwnRolesAndPermissions() throws Exception {
        User viewer = newUser("summary-viewer");
        addMember(owner, tenant, viewer, "VIEWER");
        get("/api/v1/tenants", viewer).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roles").value(org.hamcrest.Matchers.contains("VIEWER")))
                .andExpect(jsonPath("$[0].permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("FARM_WRITE"))))
                .andExpect(jsonPath("$[0].enabledModules").value(org.hamcrest.Matchers.containsInAnyOrder("CROP", "DAIRY", "WORKFORCE", "ASSETS")));
        get("/api/v1/roles", viewer).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));
    }
}
