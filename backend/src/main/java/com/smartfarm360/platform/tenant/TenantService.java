package com.smartfarm360.platform.tenant;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.membership.SfRoleRepository;
import com.smartfarm360.platform.membership.SfTenantMembership;
import com.smartfarm360.platform.membership.SfTenantMembershipRepository;
import com.smartfarm360.platform.rbac.RoleCode;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.TenantAccessService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.TenantDtos.*;
import com.smartfarm360.platform.user.SfUserAccountRepository;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

@Service
public class TenantService {
    private final SfTenantRepository tenants;
    private final SfTenantSettingsRepository settings;
    private final SfTenantModuleRepository modules;
    private final SfTenantMembershipRepository memberships;
    private final SfRoleRepository roles;
    private final SfUserAccountRepository users;
    private final TenantAccessService access;
    private final AuditService audit;

    public TenantService(SfTenantRepository tenants, SfTenantSettingsRepository settings, SfTenantModuleRepository modules,
                         SfTenantMembershipRepository memberships, SfRoleRepository roles, SfUserAccountRepository users,
                         TenantAccessService access, AuditService audit) {
        this.tenants = tenants;
        this.settings = settings;
        this.modules = modules;
        this.memberships = memberships;
        this.roles = roles;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    /** Creates a farm business; the creator becomes its owner. */
    @Transactional
    public TenantSummary create(CurrentUser user, CreateTenantRequest request) {
        Set<FarmModule> requested = request.modules() == null || request.modules().isEmpty()
                ? EnumSet.of(FarmModule.CROP, FarmModule.DAIRY, FarmModule.WORKFORCE, FarmModule.ASSETS) : EnumSet.copyOf(request.modules());
        requested.forEach(TenantService::requireAvailable);

        SfTenant tenant = new SfTenant();
        tenant.setName(request.name().trim());
        tenant.setSlug(uniqueSlug(request.name()));
        tenant.setPreferredLanguage(request.preferredLanguage() == null ? "en" : request.preferredLanguage());
        tenant = tenants.saveAndFlush(tenant);
        settings.save(new SfTenantSettings(tenant.getId()));
        for (FarmModule module : requested) modules.save(new SfTenantModule(tenant.getId(), module, true));

        SfTenantMembership membership = new SfTenantMembership();
        membership.setTenant(tenant);
        membership.setUser(users.getReferenceById(user.id()));
        membership.getRoles().addAll(roles.findByTenantIdIsNullAndCodeIn(List.of(RoleCode.TENANT_OWNER.name())));
        memberships.saveAndFlush(membership);
        modules.flush();

        audit.record(tenant.getId(), user.id(), "TENANT_CREATED", "TENANT", tenant.getId(),
                Map.of("name", tenant.getName(), "modules", requested.stream().map(Enum::name).sorted().toList()));
        return summary(tenant, access.resolve(user.id(), tenant.getId()).orElseThrow().context());
    }

    /** Every farm business the user is an active member of — the list a client offers for tenant switching. */
    @Transactional(readOnly = true)
    public List<TenantSummary> myTenants(CurrentUser user) {
        List<TenantSummary> result = new ArrayList<>();
        for (SfTenantMembership m : memberships.findActiveByUserId(user.id())) {
            access.resolve(user.id(), m.getTenant().getId()).ifPresent(a -> result.add(summary(m.getTenant(), a.context())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TenantDetail get(TenantContext ctx) {
        SfTenant tenant = tenant(ctx);
        return detail(tenant);
    }

    @Transactional
    public TenantDetail update(TenantContext ctx, UpdateTenantRequest request) {
        SfTenant tenant = tenant(ctx);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.name() != null) {
            if (request.name().isBlank()) throw ApiException.badRequest("VALIDATION_FAILED", "Name is required.");
            tenant.setName(request.name().trim());
            changes.put("name", tenant.getName());
        }
        if (request.preferredLanguage() != null) {
            tenant.setPreferredLanguage(request.preferredLanguage());
            changes.put("preferredLanguage", request.preferredLanguage());
        }
        audit.record(ctx.tenantId(), ctx.userId(), "TENANT_UPDATED", "TENANT", ctx.tenantId(), changes);
        return detail(tenant);
    }

    @Transactional(readOnly = true)
    public SettingsResponse settings(TenantContext ctx) { return toResponse(settingsFor(ctx.tenantId())); }

    @Transactional
    public SettingsResponse updateSettings(TenantContext ctx, SettingsRequest request) {
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "Unknown timezone.");
        }
        SfTenantSettings s = settingsFor(ctx.tenantId());
        s.setTimezone(request.timezone());
        s.setCurrencyCode(request.currencyCode());
        s.setAreaUnit(request.areaUnit());
        s.setWeightUnit(request.weightUnit());
        s.setVolumeUnit(request.volumeUnit());
        audit.record(ctx.tenantId(), ctx.userId(), "SETTINGS_UPDATED", "TENANT_SETTINGS", ctx.tenantId(),
                Map.of("timezone", s.getTimezone(), "areaUnit", s.getAreaUnit(), "weightUnit", s.getWeightUnit()));
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public List<ModuleStatus> modules(TenantContext ctx) { return moduleStatuses(ctx.tenantId()); }

    @Transactional
    public List<ModuleStatus> setModule(TenantContext ctx, FarmModule module, boolean enabled) {
        if (enabled) requireAvailable(module);
        Optional<SfTenantModule> existing = modules.findById(new SfTenantModule.Key(ctx.tenantId(), module));
        boolean changed = existing.map(m -> m.isEnabled() != enabled).orElse(enabled);
        SfTenantModule row = existing.orElseGet(() -> new SfTenantModule(ctx.tenantId(), module, enabled));
        row.setEnabled(enabled);
        modules.save(row);
        if (changed) {
            audit.record(ctx.tenantId(), ctx.userId(), enabled ? "MODULE_ENABLED" : "MODULE_DISABLED", "TENANT_MODULE", module.name(), Map.of());
        }
        modules.flush();
        return moduleStatuses(ctx.tenantId());
    }

    private static void requireAvailable(FarmModule module) {
        if (!module.available()) {
            throw ApiException.conflict("MODULE_NOT_AVAILABLE", "This module is not available yet.");
        }
    }

    private List<ModuleStatus> moduleStatuses(long tenantId) {
        Map<FarmModule, Boolean> enabled = new EnumMap<>(FarmModule.class);
        modules.findByTenantId(tenantId).forEach(m -> enabled.put(m.getModule(), m.isEnabled()));
        return Arrays.stream(FarmModule.values())
                .map(m -> new ModuleStatus(m.name(), enabled.getOrDefault(m, false), m.available())).toList();
    }

    private SfTenant tenant(TenantContext ctx) {
        return tenants.findById(ctx.tenantId()).orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Farm business not found."));
    }

    private SfTenantSettings settingsFor(long tenantId) {
        return settings.findById(tenantId).orElseGet(() -> settings.save(new SfTenantSettings(tenantId)));
    }

    private TenantDetail detail(SfTenant t) {
        return new TenantDetail(t.getId(), t.getName(), t.getSlug(), t.getPreferredLanguage(), t.getPlanCode(), t.isActive(),
                t.getCreatedAt(), toResponse(settingsFor(t.getId())), moduleStatuses(t.getId()));
    }

    private static SettingsResponse toResponse(SfTenantSettings s) {
        return new SettingsResponse(s.getTimezone(), s.getCurrencyCode(), s.getAreaUnit(), s.getWeightUnit(), s.getVolumeUnit());
    }

    private static TenantSummary summary(SfTenant t, TenantContext ctx) {
        return new TenantSummary(t.getId(), t.getName(), t.getSlug(), t.getPreferredLanguage(), t.isActive(),
                ctx.roleCodes().stream().sorted().toList(),
                ctx.permissions().stream().map(Enum::name).sorted().toList(),
                ctx.enabledModules().stream().map(Enum::name).sorted().toList(),
                t.getCreatedAt());
    }

    private String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) base = "farm-business";
        if (base.length() > 80) base = base.substring(0, 80).replaceAll("-$", "");
        String slug = base;
        int suffix = 2;
        while (tenants.existsBySlugIgnoreCase(slug)) slug = base + "-" + suffix++;
        return slug;
    }

    /** Exposed for the membership module, which needs the tenant name for invitation emails. */
    @Transactional(readOnly = true)
    public String tenantName(long tenantId) {
        return tenants.findById(tenantId).map(SfTenant::getName).orElse("");
    }
}
