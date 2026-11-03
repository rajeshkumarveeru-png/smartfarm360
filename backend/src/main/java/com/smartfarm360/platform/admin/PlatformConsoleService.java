package com.smartfarm360.platform.admin;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.auth.SfRefreshToken;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.plan.PlanLimits;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.PageResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The platform console (SaaS operations design, section 6, O6): farm businesses and users by counts and status, never
 * by their books; suspension with a reason; limit overrides; operations checks; announcements and legal documents.
 * Every change is recorded in the platform audit log.
 */
@Service
public class PlatformConsoleService {
    private final JdbcClient jdbc;
    private final PlatformAudit platformAudit;
    private final AuditService audit;
    private final PlanLimits limits;
    private final EmailSender email;
    private final Clock clock;
    private final String alertEmail;
    private final String version;

    public PlatformConsoleService(JdbcClient jdbc, PlatformAudit platformAudit, AuditService audit, PlanLimits limits, EmailSender email, Clock clock,
                                  @Value("${app.ops.alert-email:}") String alertEmail, ObjectProvider<BuildProperties> build) {
        this.jdbc = jdbc;
        this.platformAudit = platformAudit;
        this.audit = audit;
        this.limits = limits;
        this.email = email;
        this.clock = clock;
        this.alertEmail = alertEmail == null ? "" : alertEmail.trim();
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "development" : b.getVersion();
    }

    // ---- Farm businesses ------------------------------------------------------------------------------------------------

    public record PlatformTenant(long id, String name, String slug, boolean active, String planCode, long memberCount, long farmCount,
                                 List<String> modules, Instant lastActivity, Instant createdAt, String suspendedReason, Instant deletionScheduledFor) {}

    public record Owner(long userId, String email, String displayName) {}

    public record HistoryEntry(Instant at, String action, Long actorUserId, String actorEmail, String details) {}

    public record GrantRow(long id, String grantedByEmail, String grantedToEmail, String reason, Instant startsAt, Instant endsAt, Instant endedAt) {}

    public record TenantDetail(PlatformTenant tenant, List<Owner> owners, List<PlanLimits.Usage> usage, List<HistoryEntry> history, List<GrantRow> supportGrants) {}

    private static final String TENANT_COLUMNS = """
            select t.id, t.name, t.slug, t.active, t.plan_code,
                   (select count(*) from sf_tenant_memberships m where m.tenant_id = t.id and m.active) as members,
                   (select count(*) from sf_farms f where f.tenant_id = t.id and f.active) as farms,
                   (select coalesce(string_agg(module_code, ',' order by module_code), '') from sf_tenant_modules x where x.tenant_id = t.id and x.enabled) as modules,
                   (select max(a.occurred_at) from sf_audit_history a where a.tenant_id = t.id) as last_activity,
                   t.created_at, t.suspended_reason, t.deletion_scheduled_for
            from sf_tenants t""";

    private static PlatformTenant tenantRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String modules = rs.getString(8);
        return new PlatformTenant(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getLong(6), rs.getLong(7),
                modules == null || modules.isEmpty() ? List.of() : Arrays.asList(modules.split(",")), instant(rs.getTimestamp(9)),
                instant(rs.getTimestamp(10)), rs.getString(11), instant(rs.getTimestamp(12)));
    }

    @Transactional(readOnly = true)
    public PageResponse<PlatformTenant> tenants(String query, int page, int size) {
        PageResponse.check(page, size);
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        String where = q == null ? "" : " " + """
                where lower(t.name) like :q or lower(t.slug) like :q or exists (select 1 from sf_tenant_memberships m join sf_users u on u.id = m.user_id
                   join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                   where m.tenant_id = t.id and m.active and r.code = 'TENANT_OWNER' and lower(u.email) like :q)""";
        var count = jdbc.sql("select count(*) from sf_tenants t" + where);
        var list = jdbc.sql(TENANT_COLUMNS + where + " order by t.created_at desc, t.id desc limit :limit offset :offset");
        if (q != null) { count = count.param("q", q); list = list.param("q", q); }
        long total = count.query(Long.class).single();
        List<PlatformTenant> items = list.param("limit", size).param("offset", (long) page * size).query((rs, i) -> tenantRow(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    private PlatformTenant tenant(long id) {
        return jdbc.sql(TENANT_COLUMNS + " where t.id = :id").param("id", id).query((rs, i) -> tenantRow(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Farm business not found."));
    }

    @Transactional(readOnly = true)
    public TenantDetail tenantDetail(long id) {
        PlatformTenant t = tenant(id);
        List<Owner> owners = jdbc.sql("""
                        select u.id, u.email, u.display_name from sf_tenant_memberships m join sf_users u on u.id = m.user_id
                        join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                        where m.tenant_id = :t and m.active and r.code = 'TENANT_OWNER' order by u.email""")
                .param("t", id).query((rs, i) -> new Owner(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
        List<HistoryEntry> history = jdbc.sql("""
                        select p.occurred_at, p.action, p.actor_user_id, u.email, p.details::text from sf_platform_audit p left join sf_users u on u.id = p.actor_user_id
                        where p.target_type = 'TENANT' and p.target_id = :t order by p.occurred_at desc limit 50""")
                .param("t", id).query((rs, i) -> new HistoryEntry(instant(rs.getTimestamp(1)), rs.getString(2), (Long) rs.getObject(3), rs.getString(4), rs.getString(5))).list();
        return new TenantDetail(t, owners, limits.usage(id), history, grants(id));
    }

    List<GrantRow> grants(long tenantId) {
        return jdbc.sql("""
                        select g.id, b.email, t.email, g.reason, g.starts_at, g.ends_at, g.ended_at from sf_support_grants g
                        join sf_users b on b.id = g.granted_by left join sf_users t on t.id = g.granted_to
                        where g.tenant_id = :t order by g.created_at desc limit 20""")
                .param("t", tenantId).query((rs, i) -> new GrantRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)))).list();
    }

    /** Suspends (with a reason, shown to the business's members) or reactivates a farm business. */
    @Transactional
    public PlatformTenant setTenantStatus(CurrentUser admin, long id, boolean active, String reason) {
        PlatformTenant t = tenant(id);
        if (!active && (reason == null || reason.isBlank())) throw ApiException.badRequest("REASON_REQUIRED", "Give the reason for the suspension.");
        if (t.active() != active) {
            jdbc.sql("update sf_tenants set active = :a, suspended_reason = :r, suspended_at = :at, updated_at = now() where id = :id")
                    .param("a", active).param("r", active ? null : reason.trim()).param("at", active ? null : Timestamp.from(clock.instant())).param("id", id).update();
            Map<String, Object> details = active ? Map.of() : Map.of("reason", reason.trim());
            audit.record(id, admin.id(), active ? "PLATFORM_TENANT_ACTIVATED" : "PLATFORM_TENANT_SUSPENDED", "TENANT", id, details);
            platformAudit.record(admin.id(), active ? "PLATFORM_TENANT_ACTIVATED" : "PLATFORM_TENANT_SUSPENDED", "TENANT", id, details);
        }
        return tenant(id);
    }

    /** Raises (or lowers) one limit for one farm business, with a reason; {@code value} null removes the override. */
    @Transactional
    public TenantDetail setLimit(CurrentUser admin, long id, String code, Integer value, String reason) {
        tenant(id);
        if (!List.of(PlanLimits.MEMBERS, PlanLimits.FARMS, PlanLimits.EXPORTS_PER_DAY, PlanLimits.STORAGE_MB).contains(code)) {
            throw ApiException.badRequest("UNKNOWN_LIMIT", "Unknown limit.");
        }
        if (value == null) {
            jdbc.sql("delete from sf_tenant_limit_overrides where tenant_id = :t and limit_code = :c").param("t", id).param("c", code).update();
        } else {
            if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give the reason for the change.");
            jdbc.sql("""
                            insert into sf_tenant_limit_overrides (tenant_id, limit_code, limit_value, reason, set_by) values (:t, :c, :v, :r, :u)
                            on conflict (tenant_id, limit_code) do update set limit_value = excluded.limit_value, reason = excluded.reason,
                              set_by = excluded.set_by, set_at = now()""")
                    .param("t", id).param("c", code).param("v", value).param("r", reason.trim()).param("u", admin.id()).update();
        }
        platformAudit.record(admin.id(), "PLATFORM_LIMIT_SET", "TENANT", id, value == null ? Map.of("limit", code, "removed", true)
                : Map.of("limit", code, "value", value, "reason", reason.trim()));
        return tenantDetail(id);
    }

    // ---- Users ---------------------------------------------------------------------------------------------------------

    public record Membership(long tenantId, String tenantName, List<String> roles) {}

    public record PlatformUser(long id, String email, String displayName, boolean active, boolean platformAdmin, boolean emailVerified,
                               boolean twoStep, Instant createdAt, List<Membership> memberships) {}

    @Transactional(readOnly = true)
    public PageResponse<PlatformUser> users(String query, int page, int size) {
        PageResponse.check(page, size);
        String q = query == null || query.isBlank() ? "%" : "%" + query.trim().toLowerCase() + "%";
        long total = jdbc.sql("select count(*) from sf_users where lower(email) like :q or lower(display_name) like :q").param("q", q).query(Long.class).single();
        List<Long> ids = jdbc.sql("select id from sf_users where lower(email) like :q or lower(display_name) like :q order by created_at desc, id desc limit :l offset :o")
                .param("q", q).param("l", size).param("o", (long) page * size).query(Long.class).list();
        return PageResponse.of(ids.stream().map(this::user).toList(), page, size, total);
    }

    private PlatformUser user(long id) {
        List<Membership> memberships = jdbc.sql("""
                        select t.id, t.name, coalesce(string_agg(r.code, ',' order by r.code), '') from sf_tenant_memberships m join sf_tenants t on t.id = m.tenant_id
                        left join sf_membership_roles mr on mr.membership_id = m.id left join sf_roles r on r.id = mr.role_id
                        where m.user_id = :u and m.active group by t.id, t.name order by t.name""")
                .param("u", id).query((rs, i) -> new Membership(rs.getLong(1), rs.getString(2),
                        rs.getString(3).isEmpty() ? List.of() : Arrays.asList(rs.getString(3).split(",")))).list();
        return jdbc.sql("""
                        select u.id, u.email, u.display_name, u.active, u.platform_role = 'PLATFORM_ADMIN', u.email_verified_at is not null,
                               exists (select 1 from sf_user_mfa f where f.user_id = u.id and f.enabled_at is not null), u.created_at
                        from sf_users u where u.id = :u""")
                .param("u", id).query((rs, i) -> new PlatformUser(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getBoolean(6), rs.getBoolean(7), instant(rs.getTimestamp(8)), memberships)).optional()
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found."));
    }

    /** Locks (signs out everywhere) or unlocks a user; acts on the account, never on farm data. */
    @Transactional
    public PlatformUser setUserActive(CurrentUser admin, long id, boolean active, String reason) {
        PlatformUser u = user(id);
        if (id == admin.id()) throw ApiException.badRequest("CANNOT_LOCK_SELF", "You cannot lock your own account.");
        if (!active && (reason == null || reason.isBlank())) throw ApiException.badRequest("REASON_REQUIRED", "Give the reason for locking the account.");
        if (u.active() != active) {
            jdbc.sql("update sf_users set active = :a, updated_at = now() where id = :id").param("a", active).param("id", id).update();
            if (!active) {
                jdbc.sql("update sf_refresh_tokens set revoked_at = now(), revoke_reason = :r where user_id = :u and revoked_at is null")
                        .param("r", SfRefreshToken.LOGOUT).param("u", id).update();
            }
            platformAudit.record(admin.id(), active ? "PLATFORM_USER_UNLOCKED" : "PLATFORM_USER_LOCKED", "USER", id,
                    active ? Map.of() : Map.of("reason", reason.trim()));
        }
        return user(id);
    }

    // ---- Operations ----------------------------------------------------------------------------------------------------

    public record Check(String kind, boolean ok, String details, Long durationMs, Instant recordedAt) {}

    public record Operations(String version, Check lastBackup, Check lastRestoreTest, Check lastAlertTest, Check lastLoadTest, boolean alertEmailSet) {}

    @Transactional(readOnly = true)
    public Operations operations() {
        return new Operations(version, lastCheck("BACKUP").orElse(null), lastCheck("RESTORE_TEST").orElse(null), lastCheck("ALERT_TEST").orElse(null),
                lastCheck("LOAD_TEST").orElse(null), !alertEmail.isEmpty());
    }

    private Optional<Check> lastCheck(String kind) {
        return jdbc.sql("select kind, ok, details, duration_ms, recorded_at from sf_ops_checks where kind = :k order by recorded_at desc limit 1")
                .param("k", kind).query((rs, i) -> new Check(rs.getString(1), rs.getBoolean(2), rs.getString(3), (Long) rs.getObject(4), instant(rs.getTimestamp(5))))
                .optional();
    }

    /** Records a check made outside the application (the backup job, the restore check, a load test). */
    @Transactional
    public Check recordCheck(CurrentUser admin, String kind, boolean ok, String details, Long durationMs) {
        if (!List.of("BACKUP", "RESTORE_TEST", "LOAD_TEST").contains(kind)) throw ApiException.badRequest("UNKNOWN_CHECK", "Unknown check.");
        jdbc.sql("insert into sf_ops_checks (kind, ok, details, duration_ms) values (:k, :ok, :d, :ms)")
                .param("k", kind).param("ok", ok).param("d", details).param("ms", durationMs).update();
        platformAudit.record(admin.id(), "PLATFORM_CHECK_RECORDED", "OPS", null, Map.of("kind", kind, "ok", ok));
        return lastCheck(kind).orElseThrow();
    }

    /** Sends a test alert to the operators' address, so the alert path is known to work (O4). */
    @Transactional
    public Check testAlert(CurrentUser admin) {
        if (alertEmail.isEmpty()) throw ApiException.conflict("ALERT_EMAIL_MISSING", "Set APP_OPS_ALERT_EMAIL first.");
        email.sendNotice(alertEmail, "en", EmailSender.Notice.OPS_ALERT, Map.of("alert", "Test alert from the platform console", "time", clock.instant().toString()));
        jdbc.sql("insert into sf_ops_checks (kind, ok, details) values ('ALERT_TEST', true, :d)").param("d", "Sent by user " + admin.id()).update();
        platformAudit.record(admin.id(), "PLATFORM_TEST_ALERT", "OPS", null, Map.of());
        return lastCheck("ALERT_TEST").orElseThrow();
    }

    // ---- Platform audit log --------------------------------------------------------------------------------------------

    public record PlatformAuditEntry(long id, Instant at, Long actorUserId, String actorEmail, String action, String targetType, Long targetId, String details) {}

    @Transactional(readOnly = true)
    public PageResponse<PlatformAuditEntry> auditLog(int page, int size) {
        PageResponse.check(page, size);
        long total = jdbc.sql("select count(*) from sf_platform_audit").query(Long.class).single();
        List<PlatformAuditEntry> items = jdbc.sql("""
                        select p.id, p.occurred_at, p.actor_user_id, u.email, p.action, p.target_type, p.target_id, p.details::text
                        from sf_platform_audit p left join sf_users u on u.id = p.actor_user_id order by p.occurred_at desc, p.id desc limit :l offset :o""")
                .param("l", size).param("o", (long) page * size)
                .query((rs, i) -> new PlatformAuditEntry(rs.getLong(1), instant(rs.getTimestamp(2)), (Long) rs.getObject(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), (Long) rs.getObject(7), rs.getString(8))).list();
        return PageResponse.of(items, page, size, total);
    }

    static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
