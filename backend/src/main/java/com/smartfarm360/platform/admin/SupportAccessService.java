package com.smartfarm360.platform.admin;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Support access (SaaS operations design, O7): only when an owner grants it, read-only, for at most 72 hours, and every
 * page a platform administrator opens is recorded in the farm business's audit log. Never impersonation.
 */
@Service
public class SupportAccessService {
    public static final Duration MAX = Duration.ofHours(72);
    public static final String SUPPORT_ROLE = "PLATFORM_SUPPORT";
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PlatformAudit platformAudit;
    private final EmailSender email;
    private final Clock clock;

    public SupportAccessService(JdbcClient jdbc, AuditService audit, PlatformAudit platformAudit, EmailSender email, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.email = email;
        this.clock = clock;
    }

    public record Grant(long id, String grantedByEmail, String grantedToEmail, String reason, Instant startsAt, Instant endsAt, Instant endedAt, boolean active) {}

    public record GrantRequest(Integer hours, String reason, String platformAdminEmail) {}

    @Transactional(readOnly = true)
    public List<Grant> list(TenantContext ctx) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        select g.id, b.email, t.email, g.reason, g.starts_at, g.ends_at, g.ended_at from sf_support_grants g
                        join sf_users b on b.id = g.granted_by left join sf_users t on t.id = g.granted_to
                        where g.tenant_id = :t order by g.created_at desc limit 20""")
                .param("t", ctx.tenantId()).query((rs, i) -> {
                    Instant ends = rs.getTimestamp(6).toInstant();
                    Timestamp ended = rs.getTimestamp(7);
                    return new Grant(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), ends,
                            ended == null ? null : ended.toInstant(), ended == null && ends.isAfter(now));
                }).list();
    }

    /** An owner lets platform support read the farm business, with a reason, for up to 72 hours. */
    @Transactional
    public Grant grant(TenantContext ctx, GrantRequest r) {
        requireOwner(ctx);
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Say why support needs to look.");
        int hours = r.hours() == null ? 72 : r.hours();
        if (hours < 1 || hours > MAX.toHours()) throw ApiException.badRequest("INVALID_SUPPORT_PERIOD", "Support access lasts from 1 to 72 hours.");
        Long to = null;
        if (r.platformAdminEmail() != null && !r.platformAdminEmail().isBlank()) {
            to = jdbc.sql("select id from sf_users where lower(email) = lower(:e) and platform_role = 'PLATFORM_ADMIN' and active")
                    .param("e", r.platformAdminEmail().trim()).query(Long.class).optional()
                    .orElseThrow(() -> ApiException.badRequest("NOT_A_PLATFORM_ADMIN", "That address is not a SmartFarm360 support administrator."));
        }
        Instant now = clock.instant();
        long id = jdbc.sql("""
                        insert into sf_support_grants (tenant_id, granted_by, granted_to, reason, starts_at, ends_at)
                        values (:t, :b, :to, :r, :s, :e) returning id""")
                .param("t", ctx.tenantId()).param("b", ctx.userId()).param("to", to).param("r", r.reason().trim())
                .param("s", Timestamp.from(now)).param("e", Timestamp.from(now.plus(Duration.ofHours(hours)))).query(Long.class).single();
        Map<String, Object> details = Map.of("hours", hours, "reason", r.reason().trim());
        audit.record(ctx.tenantId(), ctx.userId(), "SUPPORT_ACCESS_GRANTED", "SUPPORT_GRANT", id, details);
        platformAudit.record(ctx.userId(), "SUPPORT_ACCESS_GRANTED", "TENANT", ctx.tenantId(), details);
        Map<String, Object> owner = jdbc.sql("""
                        select u.email, u.preferred_language, t.name from sf_users u, sf_tenants t where u.id = :u and t.id = :t""")
                .param("u", ctx.userId()).param("t", ctx.tenantId()).query().singleRow();
        String until = now.plus(Duration.ofHours(hours)).toString();
        AfterCommit.run(() -> email.sendNotice((String) owner.get("email"), (String) owner.get("preferred_language"), EmailSender.Notice.SUPPORT_ACCESS_GRANTED,
                Map.of("tenant", (String) owner.get("name"), "until", until)));
        return list(ctx).stream().filter(g -> g.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void end(TenantContext ctx, long grantId) {
        requireOwner(ctx);
        int n = jdbc.sql("update sf_support_grants set ended_at = now(), ended_by = :u where id = :id and tenant_id = :t and ended_at is null")
                .param("u", ctx.userId()).param("id", grantId).param("t", ctx.tenantId()).update();
        if (n == 0) throw ApiException.notFound("SUPPORT_GRANT_NOT_FOUND", "Support access not found or already ended.");
        audit.record(ctx.tenantId(), ctx.userId(), "SUPPORT_ACCESS_ENDED", "SUPPORT_GRANT", grantId, Map.of());
        platformAudit.record(ctx.userId(), "SUPPORT_ACCESS_ENDED", "TENANT", ctx.tenantId(), Map.of("grant", grantId));
    }

    /** The grant that lets this platform administrator read the farm business now, if any. */
    public Optional<Long> activeGrant(long tenantId, long adminUserId) {
        return jdbc.sql("""
                        select id from sf_support_grants where tenant_id = :t and ended_at is null and starts_at <= now() and ends_at > now()
                          and (granted_to is null or granted_to = :u) order by ends_at desc limit 1""")
                .param("t", tenantId).param("u", adminUserId).query(Long.class).optional();
    }

    /** Each page opened or export made under support access, in the farm business's audit log. */
    public void recordView(long tenantId, long adminUserId, long grantId, String path) {
        audit.record(tenantId, adminUserId, "SUPPORT_VIEW", "SUPPORT_GRANT", grantId, Map.of("path", path.length() > 300 ? path.substring(0, 300) : path));
    }

    private static void requireOwner(TenantContext ctx) {
        if (!ctx.hasRole("TENANT_OWNER")) throw ApiException.forbidden("OWNER_REQUIRED", "Only an owner can do this.");
    }
}
