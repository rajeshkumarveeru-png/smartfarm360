package com.smartfarm360.platform.plan;

import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Plan limits (SaaS operations design, O8). No billing: every farm business is on {@code FREE}, and a platform
 * administrator can raise a business's limit. Limits only refuse new additions; nothing recorded is ever blocked or
 * hidden. At 80% the owner sees a notice ({@link #usage}).
 */
@Service
public class PlanLimits {
    public static final String TENANTS_PER_USER = "TENANTS_PER_USER";
    public static final String MEMBERS = "MEMBERS";
    public static final String FARMS = "FARMS";
    public static final String EXPORTS_PER_DAY = "EXPORTS_PER_DAY";
    public static final String STORAGE_MB = "STORAGE_MB";
    /** The limits shown to owners, in order. Storage waits for attachment storage (ADR 0009). */
    static final List<String> SHOWN = List.of(MEMBERS, FARMS, EXPORTS_PER_DAY);
    private static final String DEFAULT_PLAN = "FREE";

    private final JdbcClient jdbc;

    public PlanLimits(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Usage(String limit, long used, int max, boolean nearLimit, boolean overridden) {}

    /** The business's limit: its override if a platform administrator set one, else its plan's. */
    public int limit(long tenantId, String code) {
        Optional<Integer> override = jdbc.sql("select limit_value from sf_tenant_limit_overrides where tenant_id = :t and limit_code = :c")
                .param("t", tenantId).param("c", code).query(Integer.class).optional();
        return override.orElseGet(() -> jdbc.sql("""
                        select l.limit_value from sf_plan_limits l join sf_tenants t on t.plan_code = l.plan_code
                        where t.id = :t and l.limit_code = :c""")
                .param("t", tenantId).param("c", code).query(Integer.class).optional().orElse(Integer.MAX_VALUE));
    }

    private int planLimit(String code) {
        return jdbc.sql("select limit_value from sf_plan_limits where plan_code = :p and limit_code = :c")
                .param("p", DEFAULT_PLAN).param("c", code).query(Integer.class).optional().orElse(Integer.MAX_VALUE);
    }

    long used(long tenantId, String code) {
        return switch (code) {
            case MEMBERS -> jdbc.sql("select count(*) from sf_tenant_memberships where tenant_id = :t and active").param("t", tenantId).query(Long.class).single();
            case FARMS -> jdbc.sql("select count(*) from sf_farms where tenant_id = :t and active").param("t", tenantId).query(Long.class).single();
            case EXPORTS_PER_DAY -> jdbc.sql("select count(*) from sf_tenant_exports where tenant_id = :t and requested_at > now() - interval '1 day'")
                    .param("t", tenantId).query(Long.class).single();
            default -> 0L;
        };
    }

    /** A user who already owns {@code TENANTS_PER_USER} farm businesses cannot create another. */
    @Transactional(readOnly = true)
    public void requireTenantSlot(long userId) {
        long owned = jdbc.sql("""
                        select count(distinct m.tenant_id) from sf_tenant_memberships m
                        join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                        join sf_tenants t on t.id = m.tenant_id
                        where m.user_id = :u and m.active and r.code = 'TENANT_OWNER' and t.deletion_requested_at is null""")
                .param("u", userId).query(Long.class).single();
        if (owned >= planLimit(TENANTS_PER_USER)) throw reached(TENANTS_PER_USER, planLimit(TENANTS_PER_USER));
    }

    public void requireMemberSlot(long tenantId) { require(tenantId, MEMBERS); }

    public void requireFarmSlot(long tenantId) { require(tenantId, FARMS); }

    public void requireExportSlot(long tenantId) { require(tenantId, EXPORTS_PER_DAY); }

    private void require(long tenantId, String code) {
        int max = limit(tenantId, code);
        if (used(tenantId, code) >= max) throw reached(code, max);
    }

    @Transactional(readOnly = true)
    public List<Usage> usage(long tenantId) {
        List<String> overridden = jdbc.sql("select limit_code from sf_tenant_limit_overrides where tenant_id = :t").param("t", tenantId).query(String.class).list();
        return SHOWN.stream().map(code -> {
            int max = limit(tenantId, code);
            long used = used(tenantId, code);
            return new Usage(code, used, max, used * 5 >= (long) max * 4, overridden.contains(code));
        }).toList();
    }

    private static ApiException reached(String code, int max) {
        return ApiException.conflict("PLAN_LIMIT_REACHED", "The plan limit is reached: " + code + " (" + max + ").");
    }
}
