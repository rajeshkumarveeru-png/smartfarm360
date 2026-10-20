package com.smartfarm360.platform.security;

import com.smartfarm360.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Invitations, role changes, owner safeguards and tenant switching. */
class MembershipIntegrationTest extends ApiIntegrationTest {

    @Test
    void invitationIsAcceptedOnlyByTheInvitedAccount() throws Exception {
        User owner = newUser("inv-owner");
        long tenant = newTenant(owner, "Invite farm");
        User invitee = newUser("invitee");
        User stranger = newUser("stranger");

        String acceptUrl = body(post("/api/v1/tenants/" + tenant + "/invitations", owner,
                Map.of("email", invitee.email(), "roles", List.of("FIELD_SUPERVISOR")))).get("acceptUrl").asText();
        assertThat(acceptUrl).contains("#token=");
        String token = mail.lastToken(invitee.email(), "INVITATION");

        post("/api/v1/invitations/accept", stranger, Map.of("token", token)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        post("/api/v1/invitations/accept", invitee, Map.of("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant));
        post("/api/v1/invitations/accept", invitee, Map.of("token", token)).andExpect(status().isNotFound());

        get("/api/v1/tenants/" + tenant + "/members", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email").value(containsInAnyOrder(owner.email(), invitee.email())));
        get("/api/v1/tenants/" + tenant + "/farms", invitee).andExpect(status().isOk());
        get("/api/v1/tenants/" + tenant + "/farms", stranger).andExpect(status().isNotFound());
    }

    @Test
    void theLastOwnerCannotBeRemovedOrDemoted() throws Exception {
        User owner = newUser("last-owner");
        long tenant = newTenant(owner, "Owner farm");
        long ownerMembership = jdbc.queryForObject("select id from sf_tenant_memberships where tenant_id = ?", Long.class, tenant);

        delete("/api/v1/tenants/" + tenant + "/members/" + ownerMembership, owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_OWNER"));
        put("/api/v1/tenants/" + tenant + "/members/" + ownerMembership + "/roles", owner, Map.of("roles", List.of("FARM_MANAGER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_OWNER"));

        User coOwner = newUser("co-owner");
        addMember(owner, tenant, coOwner, "TENANT_OWNER");
        put("/api/v1/tenants/" + tenant + "/members/" + ownerMembership + "/roles", owner, Map.of("roles", List.of("FARM_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("FARM_MANAGER"));
    }

    @Test
    void onlyOwnersCanGrantOwnership() throws Exception {
        User owner = newUser("grant-owner");
        long tenant = newTenant(owner, "Grant farm");
        User admin = newUser("farm-admin");
        addMember(owner, tenant, admin, "FARM_ADMIN");
        User worker = newUser("grant-worker");
        long workerMembership = addMember(admin, tenant, worker, "WORKER");

        put("/api/v1/tenants/" + tenant + "/members/" + workerMembership + "/roles", admin, Map.of("roles", List.of("TENANT_OWNER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        post("/api/v1/tenants/" + tenant + "/invitations", admin, Map.of("email", uniqueEmail("x"), "roles", List.of("TENANT_OWNER")))
                .andExpect(status().isForbidden());
        put("/api/v1/tenants/" + tenant + "/members/" + workerMembership + "/roles", admin, Map.of("roles", List.of("FIELD_SUPERVISOR")))
                .andExpect(status().isOk());
    }

    @Test
    void removedMembersLoseAccessImmediately() throws Exception {
        User owner = newUser("rm-owner");
        long tenant = newTenant(owner, "Remove farm");
        User member = newUser("rm-member");
        long membership = addMember(owner, tenant, member, "FARM_MANAGER");
        get("/api/v1/tenants/" + tenant + "/farms", member).andExpect(status().isOk());

        delete("/api/v1/tenants/" + tenant + "/members/" + membership, owner).andExpect(status().isNoContent());
        get("/api/v1/tenants/" + tenant + "/farms", member).andExpect(status().isNotFound());
        get("/api/v1/tenants", member).andExpect(jsonPath("$.length()").value(0));
        get("/api/v1/tenants/" + tenant + "/audit", owner)
                .andExpect(jsonPath("$.items[?(@.action == 'MEMBER_REMOVED')]").isNotEmpty());
    }

    @Test
    void aUserCanSwitchBetweenTheirTenantsWithDifferentRoles() throws Exception {
        User user = newUser("switcher");
        long own = newTenant(user, "My own farm");
        User other = newUser("other-owner");
        long theirs = newTenant(other, "Neighbour farm");
        addMember(other, theirs, user, "VIEWER");

        get("/api/v1/tenants", user).andExpect(jsonPath("$.length()").value(2));
        post("/api/v1/tenants/" + own + "/farms", user, Map.of("name", "Mine")).andExpect(status().isCreated());
        post("/api/v1/tenants/" + theirs + "/farms", user, Map.of("name", "Not mine")).andExpect(status().isForbidden());
        get("/api/v1/tenants/" + theirs + "/farms", user).andExpect(status().isOk());
    }

    @Test
    void invitingAnExistingMemberIsRejectedAndRevokedInvitationsStopWorking() throws Exception {
        User owner = newUser("dup-owner");
        long tenant = newTenant(owner, "Dup farm");
        post("/api/v1/tenants/" + tenant + "/invitations", owner, Map.of("email", owner.email(), "roles", List.of("VIEWER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));

        User late = newUser("late");
        long invitationId = body(post("/api/v1/tenants/" + tenant + "/invitations", owner, Map.of("email", late.email(), "roles", List.of("VIEWER"))))
                .get("id").asLong();
        String token = mail.lastToken(late.email(), "INVITATION");
        delete("/api/v1/tenants/" + tenant + "/invitations/" + invitationId, owner).andExpect(status().isNoContent());
        post("/api/v1/invitations/accept", late, Map.of("token", token)).andExpect(status().isNotFound());
        get("/api/v1/tenants/" + tenant + "/invitations", owner).andExpect(jsonPath("$.length()").value(0));
    }
}
