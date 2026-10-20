package com.smartfarm360.platform.admin;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.tenant.SfTenant;
import com.smartfarm360.platform.tenant.SfTenantRepository;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Platform administration (requires ROLE_PLATFORM_ADMIN, enforced in SecurityConfig). Platform admins manage tenant
 * status only; they do not get access to a tenant's farm data. Paths use {id}, not {tenantId}, on purpose.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {
    private final JdbcClient jdbc;
    private final SfTenantRepository tenants;
    private final AuditService audit;

    public PlatformAdminController(JdbcClient jdbc, SfTenantRepository tenants, AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.audit = audit;
    }

    public record PlatformTenant(long id, String name, String slug, boolean active, String planCode, long memberCount, Instant createdAt) {}

    public record TenantStatusRequest(@NotNull Boolean active) {}

    @GetMapping("/tenants")
    @Transactional(readOnly = true)
    public PageResponse<PlatformTenant> tenants(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        PageResponse.check(page, size);
        long total = jdbc.sql("select count(*) from sf_tenants").query(Long.class).single();
        List<PlatformTenant> items = jdbc.sql("""
                        select t.id, t.name, t.slug, t.active, t.plan_code,
                               (select count(*) from sf_tenant_memberships m where m.tenant_id = t.id and m.active) as member_count,
                               t.created_at
                        from sf_tenants t order by t.created_at desc, t.id desc limit :limit offset :offset""")
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> new PlatformTenant(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getString(5), rs.getLong(6), rs.getTimestamp(7).toInstant()))
                .list();
        return PageResponse.of(items, page, size, total);
    }

    @PatchMapping("/tenants/{id}")
    @Transactional
    public PlatformTenant setStatus(CurrentUser admin, @PathVariable long id, @Valid @RequestBody TenantStatusRequest request) {
        SfTenant tenant = tenants.findById(id).orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Farm business not found."));
        if (tenant.isActive() != request.active()) {
            tenant.setActive(request.active());
            tenants.saveAndFlush(tenant);
            audit.record(tenant.getId(), admin.id(), request.active() ? "PLATFORM_TENANT_ACTIVATED" : "PLATFORM_TENANT_SUSPENDED",
                    "TENANT", tenant.getId(), Map.of());
        }
        long members = jdbc.sql("select count(*) from sf_tenant_memberships where tenant_id = :t and active").param("t", id)
                .query(Long.class).single();
        return new PlatformTenant(tenant.getId(), tenant.getName(), tenant.getSlug(), tenant.isActive(), tenant.getPlanCode(),
                members, tenant.getCreatedAt());
    }
}
