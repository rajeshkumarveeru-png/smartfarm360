package com.smartfarm360.platform.tenant;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.TenantDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService tenants;

    public TenantController(TenantService tenants) { this.tenants = tenants; }

    @GetMapping
    public List<TenantSummary> mine(CurrentUser user) { return tenants.myTenants(user); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantSummary create(CurrentUser user, @Valid @RequestBody CreateTenantRequest request) {
        return tenants.create(user, request);
    }

    @GetMapping("/{tenantId}")
    @RequiresPermission(Permission.TENANT_READ)
    public TenantDetail get(TenantContext ctx) { return tenants.get(ctx); }

    @PatchMapping("/{tenantId}")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public TenantDetail update(TenantContext ctx, @Valid @RequestBody UpdateTenantRequest request) {
        return tenants.update(ctx, request);
    }

    @GetMapping("/{tenantId}/settings")
    @RequiresPermission(Permission.TENANT_READ)
    public SettingsResponse settings(TenantContext ctx) { return tenants.settings(ctx); }

    @PutMapping("/{tenantId}/settings")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public SettingsResponse updateSettings(TenantContext ctx, @Valid @RequestBody SettingsRequest request) {
        return tenants.updateSettings(ctx, request);
    }

    @GetMapping("/{tenantId}/modules")
    @RequiresPermission(Permission.TENANT_READ)
    public List<ModuleStatus> modules(TenantContext ctx) { return tenants.modules(ctx); }

    @PutMapping("/{tenantId}/modules/{module}")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public List<ModuleStatus> setModule(TenantContext ctx, @PathVariable FarmModule module, @Valid @RequestBody UpdateModuleRequest request) {
        return tenants.setModule(ctx, module, request.enabled());
    }
}
