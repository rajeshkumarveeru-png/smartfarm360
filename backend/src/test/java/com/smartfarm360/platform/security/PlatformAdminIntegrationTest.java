package com.smartfarm360.platform.security;

import com.smartfarm360.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformAdminIntegrationTest extends ApiIntegrationTest {

    private User platformAdmin() throws Exception {
        User user = newUser("platform-admin");
        jdbc.update("update sf_users set platform_role = 'PLATFORM_ADMIN' where id = ?", user.id());
        return user;
    }

    @Test
    void ordinaryUsersAndTenantOwnersCannotUsePlatformAdministration() throws Exception {
        User owner = newUser("not-admin");
        newTenant(owner, "Owner only");
        get("/api/v1/platform/tenants", owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patch("/api/v1/platform/tenants/1", owner, Map.of("active", false)).andExpect(status().isForbidden());
    }

    @Test
    void suspendingATenantBlocksItsMembersAndIsAudited() throws Exception {
        User admin = platformAdmin();
        User owner = newUser("suspended-owner");
        long tenant = newTenant(owner, "To suspend");

        get("/api/v1/platform/tenants?size=100", admin).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        patch("/api/v1/platform/tenants/" + tenant, admin, Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        get("/api/v1/tenants/" + tenant + "/farms", owner).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
        assertThat(jdbc.queryForObject("select count(*) from sf_audit_history where tenant_id = ? and action = 'PLATFORM_TENANT_SUSPENDED' and actor_user_id = ?",
                Integer.class, tenant, admin.id())).isEqualTo(1);

        patch("/api/v1/platform/tenants/" + tenant, admin, Map.of("active", true)).andExpect(status().isOk());
        get("/api/v1/tenants/" + tenant + "/farms", owner).andExpect(status().isOk());
    }

    @Test
    void platformAdminsDoNotGetAccessToTenantFarmData() throws Exception {
        User admin = platformAdmin();
        User owner = newUser("private-owner");
        long tenant = newTenant(owner, "Private farm");
        get("/api/v1/tenants/" + tenant + "/farms", admin).andExpect(status().isNotFound());
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        User admin = platformAdmin();
        get("/api/v1/platform/tenants?size=1000", admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }
}
