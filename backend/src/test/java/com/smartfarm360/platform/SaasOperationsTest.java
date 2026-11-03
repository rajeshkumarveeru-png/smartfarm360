package com.smartfarm360.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import com.smartfarm360.platform.auth.Totp;
import com.smartfarm360.platform.ratelimit.RateLimiter;
import com.smartfarm360.platform.tenant.TenantDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SaaS operations design (Phase 14): sign-in, support access, limits, maintenance, export, deletion, privacy, RLS. */
class SaasOperationsTest extends FinanceTestSupport {
    @Autowired private TenantDataService tenantData;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactions;

    private String t(long tenantId, String path) { return "/api/v1/tenants/" + tenantId + path; }

    // ---- Email verification (O5) -----------------------------------------------------------------------------------------

    @Test
    void anUnverifiedAccountSignsInButCannotCreateAFarmBusinessUntilItFollowsTheLink() throws Exception {
        String email = uniqueEmail("unverified");
        post("/api/v1/auth/register", null, Map.of("email", email, "password", PASSWORD, "displayName", "U")).andExpect(status().isAccepted());
        User u = login(email, PASSWORD);
        assertThat(body(get("/api/v1/auth/me", u)).get("emailVerified").asBoolean()).isFalse();
        post("/api/v1/tenants", u, Map.of("name", "Too early")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        post("/api/v1/auth/verify-email", null, Map.of("token", "not-a-token")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
        String token = mail.lastToken(email, "VERIFY_EMAIL");
        post("/api/v1/auth/verify-email", null, Map.of("token", token)).andExpect(status().isNoContent());
        post("/api/v1/auth/verify-email", null, Map.of("token", token)).andExpect(status().isBadRequest());   // once only
        assertThat(body(get("/api/v1/auth/me", u)).get("emailVerified").asBoolean()).isTrue();
        post("/api/v1/tenants", u, Map.of("name", "Now verified")).andExpect(status().isCreated());
        post("/api/v1/auth/me/verification", u, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_VERIFIED"));

        // Accepting an invitation sent to the address verifies it too
        newFinanceTenant("verify-" + UUID.randomUUID().toString().substring(0, 6));
        String invited = uniqueEmail("invited");
        post("/api/v1/auth/register", null, Map.of("email", invited, "password", PASSWORD, "displayName", "I")).andExpect(status().isAccepted());
        User inv = login(invited, PASSWORD);
        post(t(tenant, "/invitations"), owner, Map.of("email", invited, "roles", List.of("VIEWER"))).andExpect(status().isCreated());
        post("/api/v1/invitations/accept", inv, Map.of("token", mail.lastToken(invited, "INVITATION"))).andExpect(status().isOk());
        assertThat(body(get("/api/v1/auth/me", inv)).get("emailVerified").asBoolean()).isTrue();
    }

    // ---- Two-step sign-in (O6) -------------------------------------------------------------------------------------------

    @Test
    void twoStepSignInNeedsTheAppCodeOrARecoveryCodeAndTheChallengeIsNotASession() throws Exception {
        User u = newUser("two-step");
        post("/api/v1/auth/me/two-step/setup", u, Map.of("password", "wrong-password")).andExpect(status().isBadRequest());
        JsonNode setup = body(post("/api/v1/auth/me/two-step/setup", u, Map.of("password", PASSWORD)).andExpect(status().isOk()));
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUrl").asText()).startsWith("otpauth://totp/SmartFarm360").contains("secret=" + secret);
        long step = Totp.step(Instant.now().getEpochSecond());
        post("/api/v1/auth/me/two-step/enable", u, Map.of("code", "000000".equals(Totp.code(secret, step)) ? "111111" : "000000"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TWO_STEP_CODE"));
        JsonNode codes = body(post("/api/v1/auth/me/two-step/enable", u, Map.of("code", Totp.code(secret, step))).andExpect(status().isOk()));
        assertThat(codes.get("codes")).hasSize(10);
        assertThat(jdbc.queryForObject("select secret_enc from sf_user_mfa where user_id = ?", String.class, u.id())).doesNotContain(secret);

        JsonNode first = body(post("/api/v1/auth/login", null, Map.of("email", u.email(), "password", PASSWORD, "refreshTokenDelivery", "BODY"))
                .andExpect(status().isOk()));
        assertThat(first.get("accessToken").isNull()).isTrue();
        String challenge = first.get("secondStepChallenge").asText();
        // The challenge proves only the password: it is not an access token
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());
        post("/api/v1/auth/login/second-step", null, Map.of("challenge", challenge, "code", Totp.code(secret, step))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TWO_STEP_CODE"));   // the enabling code cannot be replayed
        String recovery = codes.get("codes").get(0).asText();
        JsonNode session = body(post("/api/v1/auth/login/second-step", null, Map.of("challenge", challenge, "recoveryCode", recovery, "refreshTokenDelivery", "BODY"))
                .andExpect(status().isOk()));
        assertThat(session.at("/user/twoStepEnabled").asBoolean()).isTrue();
        post("/api/v1/auth/login/second-step", null, Map.of("challenge", challenge, "recoveryCode", recovery)).andExpect(status().isUnauthorized());
        post("/api/v1/auth/login/second-step", null, Map.of("challenge", "garbage", "code", "123456")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SECOND_STEP_EXPIRED"));
        // A refreshed session keeps its second step
        String refreshed = body(post("/api/v1/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken").asText())).andExpect(status().isOk()))
                .get("accessToken").asText();
        assertThat(new String(Base64.getUrlDecoder().decode(refreshed.split("\\.")[1]), StandardCharsets.UTF_8)).contains("\"mfa\":true");
    }

    @Test
    void thePlatformConsoleNeedsTheSecondStepAndRecordsEveryChange() throws Exception {
        User plain = newUser("admin-without-two-step");
        jdbc.update("update sf_users set platform_role = 'PLATFORM_ADMIN' where id = ?", plain.id());
        get("/api/v1/platform/tenants", plain).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TWO_STEP_REQUIRED"));
        User admin = platformAdmin("console-admin");
        assertThat(mail.to(admin.email()).stream().anyMatch(m -> m.type().equals("ADMIN_SIGN_IN"))).isTrue();
        post("/api/v1/auth/me/two-step/disable", admin, Map.of("password", PASSWORD, "code", "123456")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TWO_STEP_REQUIRED"));

        newFinanceTenant("console-" + UUID.randomUUID().toString().substring(0, 6));
        JsonNode found = body(get("/api/v1/platform/tenants?q=" + owner.email(), admin).andExpect(status().isOk()));
        assertThat(found.get("items")).hasSize(1);
        assertThat(found.at("/items/0/id").asLong()).isEqualTo(tenant);
        assertThat(found.toString()).doesNotContain("monthRevenue").doesNotContain("balance");
        JsonNode detail = body(get("/api/v1/platform/tenants/" + tenant, admin).andExpect(status().isOk()));
        assertThat(detail.at("/owners/0/email").asText()).isEqualTo(owner.email());
        patch("/api/v1/platform/tenants/" + tenant, admin, Map.of("active", false, "reason", "Payment dispute")).andExpect(status().isOk());
        assertThat(body(get("/api/v1/tenants", owner)).get(0).get("suspendedReason").asText()).isEqualTo("Payment dispute");
        patch("/api/v1/platform/tenants/" + tenant, admin, Map.of("active", true)).andExpect(status().isOk());
        // Lock a user: signed out everywhere and cannot sign in
        User member = newUser("locked-member");
        patch("/api/v1/platform/users/" + member.id(), admin, Map.of("active", false)).andExpect(status().isBadRequest());
        patch("/api/v1/platform/users/" + member.id(), admin, Map.of("active", false, "reason", "Shared password")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        get("/api/v1/auth/me", member).andExpect(status().isUnauthorized());
        post("/api/v1/auth/login", null, Map.of("email", member.email(), "password", PASSWORD)).andExpect(status().isUnauthorized());
        patch("/api/v1/platform/users/" + admin.id(), admin, Map.of("active", false, "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_LOCK_SELF"));
        // Operations: a recorded restore test, and the alert path
        post("/api/v1/platform/ops/checks", admin, Map.of("kind", "RESTORE_TEST", "ok", true, "details", "All checks green", "durationMs", 160000))
                .andExpect(status().isCreated());
        assertThat(body(get("/api/v1/platform/ops", admin)).at("/lastRestoreTest/ok").asBoolean()).isTrue();
        post("/api/v1/platform/ops/test-alert", admin, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_EMAIL_MISSING"));
        JsonNode log = body(get("/api/v1/platform/audit?size=50", admin));
        List<String> actions = new ArrayList<>();
        log.get("items").forEach(i -> actions.add(i.get("action").asText()));
        assertThat(actions).contains("PLATFORM_TENANT_SUSPENDED", "PLATFORM_TENANT_ACTIVATED", "PLATFORM_USER_LOCKED", "PLATFORM_CHECK_RECORDED");
    }

    // ---- Support access (O7) ---------------------------------------------------------------------------------------------

    @Test
    void supportReadsAFarmBusinessOnlyUnderAnOwnersGrantReadOnlyAndRecorded() throws Exception {
        newFinanceTenant("support-" + UUID.randomUUID().toString().substring(0, 6));
        User admin = platformAdmin("support-admin");
        get(t(tenant, "/farms"), admin).andExpect(status().isNotFound());
        User manager = newUser("support-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        post(t(tenant, "/support-grants"), manager, Map.of("hours", 24, "reason", "Check")).andExpect(status().isForbidden());
        post(t(tenant, "/support-grants"), owner, Map.of("hours", 73, "reason", "Too long")).andExpect(status().isBadRequest());
        long grant = body(post(t(tenant, "/support-grants"), owner, Map.of("hours", 24, "reason", "October profit check")).andExpect(status().isCreated()))
                .get("id").asLong();
        assertThat(mail.to(owner.email()).stream().anyMatch(m -> m.type().equals("SUPPORT_ACCESS_GRANTED"))).isTrue();

        get(t(tenant, "/farms"), admin).andExpect(status().isOk());
        get(t(tenant, "/finance/reports/summary?asOf=2026-10-20"), admin).andExpect(status().isOk());
        post(t(tenant, "/farms"), admin, Map.of("name", "Not allowed")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPPORT_READ_ONLY"));
        get(t(tenant, "/members"), admin).andExpect(status().isForbidden());   // viewer permissions only
        // Another business stays closed to support
        long other = newTenant(newUser("other-owner"), "Other farm");
        get(t(other, "/farms"), admin).andExpect(status().isNotFound());
        // An administrator who did not pass the second step sees nothing even with a grant
        User plain = newUser("plain-admin");
        jdbc.update("update sf_users set platform_role = 'PLATFORM_ADMIN' where id = ?", plain.id());
        get(t(tenant, "/farms"), plain).andExpect(status().isNotFound());

        JsonNode audit = body(get(t(tenant, "/audit?size=50"), owner));
        assertThat(audit.toString()).contains("SUPPORT_VIEW").contains("/farms").contains("SUPPORT_ACCESS_GRANTED");
        post(t(tenant, "/support-grants/" + grant + "/end"), owner, Map.of()).andExpect(status().isNoContent());
        get(t(tenant, "/farms"), admin).andExpect(status().isNotFound());
        // An expired grant gives nothing either
        post(t(tenant, "/support-grants"), owner, Map.of("hours", 1, "reason", "Short")).andExpect(status().isCreated());
        jdbc.update("update sf_support_grants set starts_at = starts_at - interval '2 hours', ends_at = ends_at - interval '2 hours' where tenant_id = ?", tenant);
        get(t(tenant, "/farms"), admin).andExpect(status().isNotFound());
    }

    // ---- Plan limits and maintenance (O8) --------------------------------------------------------------------------------

    @Test
    void limitsRefuseOnlyNewAdditionsAndAPlatformAdministratorCanRaiseThem() throws Exception {
        newFinanceTenant("limits-" + UUID.randomUUID().toString().substring(0, 6));
        User admin = platformAdmin("limits-admin");
        newFarm(owner, tenant, "Farm 1");
        newFarm(owner, tenant, "Farm 2");
        put("/api/v1/platform/tenants/" + tenant + "/limits/FARMS", admin, Map.of("value", 2)).andExpect(status().isBadRequest());
        put("/api/v1/platform/tenants/" + tenant + "/limits/FARMS", admin, Map.of("value", 2, "reason", "Test")).andExpect(status().isOk());
        post(t(tenant, "/farms"), owner, Map.of("name", "Farm 3")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
        get(t(tenant, "/farms"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));   // nothing hidden
        JsonNode usage = body(get(t(tenant, "/usage"), owner));
        JsonNode farms = StreamSupportFind(usage, "FARMS");
        assertThat(farms.get("used").asInt()).isEqualTo(2);
        assertThat(farms.get("nearLimit").asBoolean()).isTrue();
        assertThat(farms.get("overridden").asBoolean()).isTrue();
        put("/api/v1/platform/tenants/" + tenant + "/limits/FARMS", admin, Map.of("value", 5, "reason", "Second farm block")).andExpect(status().isOk());
        post(t(tenant, "/farms"), owner, Map.of("name", "Farm 3")).andExpect(status().isCreated());
        put("/api/v1/platform/tenants/" + tenant + "/limits/MEMBERS", admin, Map.of("value", 1, "reason", "Test")).andExpect(status().isOk());
        post(t(tenant, "/invitations"), owner, Map.of("email", uniqueEmail("one-too-many"), "roles", List.of("VIEWER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
        // A user can own at most 3 farm businesses on FREE
        User founder = newUser("founder");
        for (int i = 1; i <= 3; i++) newTenant(founder, "Business " + i);
        post("/api/v1/tenants", founder, Map.of("name", "Business 4")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
    }

    private static JsonNode StreamSupportFind(JsonNode array, String limit) {
        for (JsonNode n : array) if (limit.equals(n.get("limit").asText())) return n;
        throw new AssertionError("no " + limit);
    }

    @Test
    void readOnlyMaintenanceRefusesChangesButNotReadsSignInOrTheConsole() throws Exception {
        newFinanceTenant("maint-" + UUID.randomUUID().toString().substring(0, 6));
        User admin = platformAdmin("maint-admin");
        post("/api/v1/platform/announcements", admin, Map.of("messageEn", "Maintenance", "messageTa", "பராமரிப்பு", "endsAt", Instant.now().minusSeconds(5).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        long id = body(post("/api/v1/platform/announcements", admin, Map.of("messageEn", "Upgrade until 07:00", "messageTa", "07:00 வரை மேம்படுத்தல்",
                "endsAt", Instant.now().plusSeconds(3600).toString(), "maintenance", true)).andExpect(status().isCreated())).get("id").asLong();
        try {
            assertThat(body(get("/api/v1/announcements", null)).get(0).get("messageTa").asText()).isEqualTo("07:00 வரை மேம்படுத்தல்");
            post(t(tenant, "/farms"), owner, Map.of("name", "During maintenance")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("MAINTENANCE"));
            get(t(tenant, "/farms"), owner).andExpect(status().isOk());
            login(owner.email(), PASSWORD);
            get("/api/v1/platform/ops", admin).andExpect(status().isOk());
        } finally {
            post("/api/v1/platform/announcements/" + id + "/end", admin, Map.of()).andExpect(status().isNoContent());
        }
        post(t(tenant, "/farms"), owner, Map.of("name", "After maintenance")).andExpect(status().isCreated());
    }

    // ---- Export and deletion (O9, O10) -----------------------------------------------------------------------------------

    private Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return files;
    }

    private JsonNode awaitExport(long tenantId, User user, long exportId) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode e : body(get(t(tenantId, "/exports"), user))) {
                if (e.get("id").asLong() == exportId && !List.of("QUEUED", "RUNNING").contains(e.get("status").asText())) return e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("export not finished");
    }

    @Test
    void anOwnerExportsTheWholeBusinessAsCsvAndJsonWithoutSecretsOrOtherBusinesses() throws Exception {
        newFinanceTenant("export-" + UUID.randomUUID().toString().substring(0, 6));
        long farm = newFarm(owner, tenant, "Export farm");
        invoice("bills", "2026-10-01", null, cash, line("OTHER_INPUTS", 1250.5, null));
        post(t(tenant, "/invitations"), owner, Map.of("email", uniqueEmail("pending"), "roles", List.of("VIEWER"))).andExpect(status().isCreated());
        long otherTenant = newTenant(newUser("export-other"), "Neighbour farm");
        User viewer = newUser("export-viewer");
        addMember(owner, tenant, viewer, "VIEWER");
        post(t(tenant, "/exports"), viewer, Map.of()).andExpect(status().isForbidden());

        long id = body(post(t(tenant, "/exports"), owner, Map.of()).andExpect(status().isAccepted())).get("id").asLong();
        JsonNode done = awaitExport(tenant, owner, id);
        assertThat(done.get("status").asText()).isEqualTo("READY");
        // The email follows the READY status on the export's own thread
        for (int i = 0; i < 50 && mail.to(owner.email()).stream().noneMatch(m -> m.type().equals("EXPORT_READY")); i++) Thread.sleep(100);
        assertThat(mail.to(owner.email()).stream().anyMatch(m -> m.type().equals("EXPORT_READY"))).isTrue();
        byte[] zip = get(t(tenant, "/exports/" + id + "/download"), owner).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip")).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> files = unzip(zip);
        assertThat(files).containsKeys("README.txt", "farm_business.csv", "members.csv", "sf_farms.csv", "sf_farms.json", "sf_journal_lines.csv",
                "sf_finance_documents.json", "sf_invitations.csv");
        assertThat(files).doesNotContainKey("sf_idempotent_requests.csv");
        assertThat(files.get("sf_farms.csv")).contains("Export farm").contains(String.valueOf(farm));
        assertThat(files.get("sf_invitations.csv")).doesNotContain("token_hash");
        assertThat(files.get("sf_finance_document_lines.csv")).contains("1250.50");
        assertThat(String.join("", files.values())).doesNotContain("Neighbour farm");
        assertThat(files.get("README.txt")).contains("sf_journal_lines (");
        // The journal in the export balances
        JsonNode lines = json.readTree(files.get("sf_journal_lines.json"));
        java.math.BigDecimal debit = java.math.BigDecimal.ZERO, credit = java.math.BigDecimal.ZERO;
        // Amounts are exact decimal strings in the export
        for (JsonNode l : lines) { debit = debit.add(new java.math.BigDecimal(l.get("debit").asText())); credit = credit.add(new java.math.BigDecimal(l.get("credit").asText())); }
        assertThat(debit).isEqualByComparingTo(credit).isPositive();
        // Other businesses' owners cannot reach the file
        get(t(tenant, "/exports/" + id + "/download"), newUser("stranger")).andExpect(status().isNotFound());
        get(t(otherTenant, "/exports/" + id + "/download"), owner).andExpect(status().isNotFound());
        // At most 3 a day
        post(t(tenant, "/exports"), owner, Map.of()).andExpect(status().isAccepted());
        post(t(tenant, "/exports"), owner, Map.of()).andExpect(status().isAccepted());
        post(t(tenant, "/exports"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
    }

    private long tenantRows(long tenantId) {
        long total = 0;
        for (String table : jdbc.queryForList("""
                select c.table_name from information_schema.columns c join information_schema.tables x on x.table_schema = c.table_schema and x.table_name = c.table_name
                where c.table_schema = current_schema() and c.column_name = 'tenant_id' and x.table_type = 'BASE TABLE'""", String.class)) {
            total += jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenantId);
        }
        return total;
    }

    @Test
    void deletionWaits30DaysReadOnlyCanBeCancelledThenRemovesEveryRowOfThatBusinessOnly() throws Exception {
        newFinanceTenant("delete-" + UUID.randomUUID().toString().substring(0, 6));
        String name = body(get(t(tenant, ""), owner)).get("name").asText();
        long farm = newFarm(owner, tenant, "Doomed farm");
        post(t(tenant, "/farms/" + farm + "/land-parcels"), owner, Map.of("name", "F", "area", 2)).andExpect(status().isCreated());
        invoice("bills", "2026-10-01", null, cash, line("OTHER_INPUTS", 500, null));
        User manager = newUser("delete-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        post(t(tenant, "/invitations"), owner, Map.of("email", uniqueEmail("never"), "roles", List.of("VIEWER"))).andExpect(status().isCreated());
        long keeper = newTenant(newUser("keeper"), "Keeper farm");
        newFarm(newUserOwning(keeper), keeper, "Keeper's farm");
        long keeperRows = tenantRows(keeper);

        post(t(tenant, "/deletion"), manager, Map.of("confirmName", name)).andExpect(status().isForbidden());
        post(t(tenant, "/deletion"), owner, Map.of("confirmName", "wrong name")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRM_NAME_MISMATCH"));
        JsonNode state = body(post(t(tenant, "/deletion"), owner, Map.of("confirmName", name)).andExpect(status().isOk()));
        assertThat(state.get("pending").asBoolean()).isTrue();
        assertThat(Duration.between(Instant.parse(state.get("requestedAt").asText()), Instant.parse(state.get("scheduledFor").asText())).toDays()).isEqualTo(30);
        assertThat(mail.to(owner.email()).stream().anyMatch(m -> m.type().equals("DELETION_SCHEDULED"))).isTrue();
        // Read-only meanwhile, but exports still work
        post(t(tenant, "/farms"), owner, Map.of("name", "New")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DELETION_PENDING"));
        get(t(tenant, "/farms"), manager).andExpect(status().isOk());
        post(t(tenant, "/exports"), owner, Map.of()).andExpect(status().isAccepted());
        assertThat(body(get("/api/v1/tenants", owner)).get(0).get("deletionScheduledFor").isNull()).isFalse();
        // Nothing happens before the 30 days are over; cancelling reopens it
        assertThat(tenantData.runDueDeletions(Instant.now().plus(Duration.ofDays(29)))).isZero();
        delete(t(tenant, "/deletion"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(false));
        post(t(tenant, "/farms"), owner, Map.of("name", "Reopened")).andExpect(status().isCreated());
        post(t(tenant, "/deletion"), owner, Map.of("confirmName", name.toUpperCase())).andExpect(status().isOk());

        long id = tenant;
        Thread.sleep(300);   // let the export finish so its file is removed too
        assertThat(tenantData.runDueDeletions(Instant.now().plus(Duration.ofDays(31)))).isGreaterThanOrEqualTo(1);
        assertThat(tenantRows(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_tenants where id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_membership_roles mr where not exists (select 1 from sf_tenant_memberships m where m.id = mr.membership_id)",
                Long.class)).isZero();
        assertThat(tenantRows(keeper)).isEqualTo(keeperRows);
        assertThat(jdbc.queryForObject("select count(*) from sf_platform_audit where action = 'TENANT_DELETED' and target_id = ?", Long.class, id)).isEqualTo(1);
        assertThat(body(get("/api/v1/tenants", owner))).isEmpty();
        assertThat(mail.to(owner.email()).stream().anyMatch(m -> m.type().equals("DELETION_DONE"))).isTrue();
        get(t(id, "/farms"), owner).andExpect(status().isNotFound());
    }

    private User newUserOwning(long tenantId) {
        long userId = jdbc.queryForObject("""
                select m.user_id from sf_tenant_memberships m join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                where m.tenant_id = ? and r.code = 'TENANT_OWNER' limit 1""", Long.class, tenantId);
        try {
            return login(jdbc.queryForObject("select email from sf_users where id = ?", String.class, userId), PASSWORD);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Accounts and consent (O11, O12) ---------------------------------------------------------------------------------

    @Test
    void deletingAnAccountReplacesThePersonButKeepsTheBooksAndIsRefusedForASoleOwner() throws Exception {
        newFinanceTenant("account-" + UUID.randomUUID().toString().substring(0, 6));
        post("/api/v1/me/deletion", owner, Map.of("password", PASSWORD)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLE_OWNER"));
        User partner = newUser("partner");
        addMember(owner, tenant, partner, "TENANT_OWNER");
        invoice("bills", "2026-10-02", null, cash, line("OTHER_INPUTS", 300, null));
        post("/api/v1/me/deletion", owner, Map.of("password", "wrong")).andExpect(status().isBadRequest());
        JsonNode mine = body(get("/api/v1/me/data", owner).andExpect(status().isOk()));
        assertThat(mine.get("email").asText()).isEqualTo(owner.email());
        assertThat(mine.at("/memberships/0/tenantId").asLong()).isEqualTo(tenant);

        post("/api/v1/me/deletion", owner, Map.of("password", PASSWORD)).andExpect(status().isNoContent());
        post("/api/v1/auth/login", null, Map.of("email", owner.email(), "password", PASSWORD)).andExpect(status().isUnauthorized());
        get("/api/v1/auth/me", owner).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select display_name from sf_users where id = ?", String.class, owner.id())).isEqualTo("Former member #" + owner.id());
        assertThat(jdbc.queryForObject("select count(*) from sf_users where lower(email) = lower(?)", Long.class, owner.email())).isZero();
        // The books are untouched and still balance; the partner sees the former member in the log
        assertThat(journalImbalance()).isZero();
        assertThat(body(get(t(tenant, "/members"), partner))).hasSize(1);
        assertThat(body(get(t(tenant, "/audit?size=100"), partner)).toString()).contains("Former member #" + owner.id());
        assertThat(mail.to(owner.email()).stream().anyMatch(m -> m.type().equals("ACCOUNT_DELETED"))).isTrue();
    }

    @Test
    void newTermsArePendingUntilAcceptedAndEachAcceptanceIsRecorded() throws Exception {
        User admin = platformAdmin("legal-admin");
        User u = newUser("consenting");
        int before = jdbc.queryForObject("select coalesce(max(version), 0) from sf_legal_documents where kind = 'TERMS'", Integer.class);
        post("/api/v1/platform/legal-documents", admin, Map.of("kind", "TERMS", "texts", List.of(Map.of("language", "en", "title", "Terms", "body", "Only English"))))
                .andExpect(status().isBadRequest());
        post("/api/v1/platform/legal-documents", admin, Map.of("kind", "TERMS", "changes", "First version", "texts", List.of(
                Map.of("language", "en", "title", "Terms of service", "body", "The terms."),
                Map.of("language", "ta", "title", "சேவை விதிமுறைகள்", "body", "விதிமுறைகள்.")))).andExpect(status().isCreated());
        String pending = "TERMS:" + (before + 1);
        assertThat(body(get("/api/v1/auth/me", u)).get("pendingConsents").toString()).contains(pending);
        assertThat(body(get("/api/v1/legal/current?language=ta", null)).toString()).contains("சேவை விதிமுறைகள்");
        post("/api/v1/me/consents", u, Map.of("documents", List.of("TERMS:999"), "client", "WEB")).andExpect(status().isBadRequest());
        post("/api/v1/me/consents", u, Map.of("documents", List.of(pending), "client", "WEB")).andExpect(status().isOk());
        assertThat(body(get("/api/v1/auth/me", u)).get("pendingConsents").toString()).doesNotContain("TERMS");
        put("/api/v1/me/marketing", u, Map.of("optIn", true, "client", "MOBILE")).andExpect(status().isOk());
        JsonNode consents = body(get("/api/v1/me/consents", u));
        assertThat(consents.toString()).contains("\"kind\":\"MARKETING\"").contains("\"client\":\"MOBILE\"").contains("\"kind\":\"TERMS\"");
        // A new version is pending again
        post("/api/v1/platform/legal-documents", admin, Map.of("kind", "TERMS", "texts", List.of(
                Map.of("language", "en", "title", "Terms of service", "body", "The new terms."),
                Map.of("language", "ta", "title", "சேவை விதிமுறைகள்", "body", "புதிய விதிமுறைகள்.")))).andExpect(status().isCreated());
        assertThat(body(get("/api/v1/auth/me", u)).get("pendingConsents").toString()).contains("TERMS:" + (before + 2));
    }

    // ---- Rate limits and Row-Level Security (O13) ------------------------------------------------------------------------

    @Test
    void rateLimitCountsSurviveARestart() {
        String key = "test:" + UUID.randomUUID();
        RateLimiter first = new RateLimiter(jdbcClient, transactions, Clock.systemUTC());
        for (int i = 0; i < 3; i++) first.check(key, 3, Duration.ofHours(1));
        RateLimiter afterRestart = new RateLimiter(jdbcClient, transactions, Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> afterRestart.check(key, 3, Duration.ofHours(1)))
                .hasMessageContaining("Too many attempts");
    }

    @Test
    void theDatabaseItselfHidesOtherBusinessesRowsInsideABusinessScope() throws Exception {
        newFinanceTenant("rls-a-" + UUID.randomUUID().toString().substring(0, 6));
        long a = tenant;
        newFarm(owner, a, "A's farm");
        User b = newUser("rls-b");
        long tb = newTenant(b, "B");
        newFarm(b, tb, "B's farm");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("select set_config('app.tenant_id', '" + a + "', false)");
            assertThat(count(s, "select count(*) from sf_farms where tenant_id = " + tb)).isZero();
            assertThat(count(s, "select count(*) from sf_farms where tenant_id = " + a)).isEqualTo(1);
            assertThat(count(s, "select count(*) from sf_finance_categories where tenant_id is null")).isPositive();   // shared seed rows stay visible
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.execute("insert into sf_farms (tenant_id, name, active) values (" + tb + ", 'Planted', true)"))
                    .hasMessageContaining("row-level security");
            assertThat(s.executeUpdate("update sf_farms set name = 'Taken' where tenant_id = " + tb)).isZero();
            s.execute("select set_config('app.tenant_id', 'all', false)");
            assertThat(count(s, "select count(*) from sf_farms where tenant_id = " + tb)).isEqualTo(1);
        }
        // The application's own connection is not a superuser, so the policies apply to it
        assertThat(jdbc.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class)).isFalse();
    }

    private static long count(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
