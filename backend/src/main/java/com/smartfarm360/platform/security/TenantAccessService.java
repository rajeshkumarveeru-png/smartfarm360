package com.smartfarm360.platform.security;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.tenant.FarmModule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Loads a user's active membership in a tenant together with its roles, permissions and enabled modules. */
@Service
public class TenantAccessService {
    private final JdbcClient jdbc;

    public TenantAccessService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** {@code deletionPending}: the business is in its 30 days' grace before deletion and is read-only (O10). */
    public record Access(TenantContext context, boolean tenantActive, boolean deletionPending) {}

    @Transactional(readOnly = true)
    public Optional<Access> resolve(long userId, long tenantId) {
        Optional<MembershipRow> row = jdbc.sql("""
                        select m.id as membership_id, t.active as tenant_active, t.deletion_requested_at is not null as deletion_pending
                        from sf_tenant_memberships m join sf_tenants t on t.id = m.tenant_id
                        where m.tenant_id = :tenantId and m.user_id = :userId and m.active = true""")
                .param("tenantId", tenantId).param("userId", userId)
                .query(MembershipRow.class).optional();
        if (row.isEmpty()) return Optional.empty();
        long membershipId = row.get().membershipId();

        Set<String> roles = new HashSet<>(jdbc.sql("""
                        select r.code from sf_membership_roles mr join sf_roles r on r.id = mr.role_id
                        where mr.membership_id = :membershipId""")
                .param("membershipId", membershipId).query(String.class).list());

        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        jdbc.sql("""
                        select distinct rp.permission_code from sf_membership_roles mr
                        join sf_role_permissions rp on rp.role_id = mr.role_id
                        where mr.membership_id = :membershipId""")
                .param("membershipId", membershipId).query(String.class).list()
                .forEach(code -> permissions.add(Permission.valueOf(code)));

        Set<FarmModule> modules = EnumSet.noneOf(FarmModule.class);
        jdbc.sql("select module_code from sf_tenant_modules where tenant_id = :tenantId and enabled = true")
                .param("tenantId", tenantId).query(String.class).list()
                .forEach(code -> modules.add(FarmModule.valueOf(code)));

        TenantContext context = new TenantContext(tenantId, userId, membershipId, Set.copyOf(roles),
                Set.copyOf(permissions), Set.copyOf(modules));
        return Optional.of(new Access(context, row.get().tenantActive(), row.get().deletionPending()));
    }

    /**
     * A platform administrator's read-only view under an owner's support grant (O7): the permissions of a viewer, and the
     * business's enabled modules. Membership id -1: there is no membership.
     */
    @Transactional(readOnly = true)
    public Optional<Access> supportAccess(long userId, long tenantId) {
        Optional<Boolean[]> tenant = jdbc.sql("select active, deletion_requested_at is not null from sf_tenants where id = :t").param("t", tenantId)
                .query((rs, i) -> new Boolean[] {rs.getBoolean(1), rs.getBoolean(2)}).optional();
        if (tenant.isEmpty()) return Optional.empty();
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        jdbc.sql("""
                        select rp.permission_code from sf_role_permissions rp join sf_roles r on r.id = rp.role_id
                        where r.tenant_id is null and r.code = 'VIEWER'""").query(String.class).list()
                .forEach(code -> permissions.add(Permission.valueOf(code)));
        Set<FarmModule> modules = EnumSet.noneOf(FarmModule.class);
        jdbc.sql("select module_code from sf_tenant_modules where tenant_id = :tenantId and enabled = true")
                .param("tenantId", tenantId).query(String.class).list()
                .forEach(code -> modules.add(FarmModule.valueOf(code)));
        TenantContext context = new TenantContext(tenantId, userId, -1, Set.of("PLATFORM_SUPPORT"), Set.copyOf(permissions), Set.copyOf(modules));
        return Optional.of(new Access(context, tenant.get()[0], tenant.get()[1]));
    }

    record MembershipRow(long membershipId, boolean tenantActive, boolean deletionPending) {}
}
