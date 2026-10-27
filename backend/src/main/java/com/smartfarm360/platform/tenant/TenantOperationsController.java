package com.smartfarm360.platform.tenant;

import com.smartfarm360.platform.admin.SupportAccessService;
import com.smartfarm360.platform.plan.PlanLimits;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A farm business's own operations (SaaS operations design, sections 6–8): its plan usage, support access, the
 * export of everything, and deletion. Changes are for owners only (checked in the services).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class TenantOperationsController {
    private final PlanLimits limits;
    private final SupportAccessService support;
    private final TenantDataService data;

    public TenantOperationsController(PlanLimits limits, SupportAccessService support, TenantDataService data) {
        this.limits = limits;
        this.support = support;
        this.data = data;
    }

    public record GrantRequest(@Min(1) @Max(72) Integer hours, @NotBlank @Size(max = 300) String reason, @Size(max = 254) String platformAdminEmail) {}

    public record DeletionRequest(@NotBlank @Size(max = 140) String confirmName) {}

    @GetMapping("/usage")
    @RequiresPermission(Permission.TENANT_READ)
    public List<PlanLimits.Usage> usage(TenantContext ctx) { return limits.usage(ctx.tenantId()); }

    @GetMapping("/support-grants")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public List<SupportAccessService.Grant> grants(TenantContext ctx) { return support.list(ctx); }

    @PostMapping("/support-grants")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.TENANT_MANAGE)
    public SupportAccessService.Grant grant(TenantContext ctx, @Valid @RequestBody GrantRequest r) {
        return support.grant(ctx, new SupportAccessService.GrantRequest(r.hours(), r.reason(), r.platformAdminEmail()));
    }

    @PostMapping("/support-grants/{grantId}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.TENANT_MANAGE)
    public void endGrant(TenantContext ctx, @PathVariable long grantId) { support.end(ctx, grantId); }

    @GetMapping("/exports")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public List<TenantDataService.Export> exports(TenantContext ctx) { return data.exports(ctx); }

    /** Starts the export of the whole business; the owner gets an email when it is ready (O9). */
    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.TENANT_MANAGE)
    public TenantDataService.Export requestExport(TenantContext ctx) { return data.requestExport(ctx); }

    @GetMapping("/exports/{exportId}/download")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public ResponseEntity<Resource> download(TenantContext ctx, @PathVariable long exportId) {
        TenantDataService.Download d = data.download(ctx, exportId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(d.file());
    }

    @GetMapping("/deletion")
    @RequiresPermission(Permission.TENANT_READ)
    public TenantDataService.DeletionState deletion(TenantContext ctx) { return data.deletionState(ctx); }

    /** Asks for deletion after 30 days' grace; the business is read-only meanwhile (O10). */
    @PostMapping("/deletion")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public TenantDataService.DeletionState requestDeletion(TenantContext ctx, @Valid @RequestBody DeletionRequest r) { return data.requestDeletion(ctx, r.confirmName()); }

    @DeleteMapping("/deletion")
    @RequiresPermission(Permission.TENANT_MANAGE)
    public TenantDataService.DeletionState cancelDeletion(TenantContext ctx) { return data.cancelDeletion(ctx); }
}
