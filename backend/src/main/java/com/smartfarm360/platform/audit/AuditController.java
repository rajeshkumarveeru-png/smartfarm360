package com.smartfarm360.platform.audit;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.PageResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/audit")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    @RequiresPermission(Permission.AUDIT_READ)
    public PageResponse<AuditService.AuditEntry> list(TenantContext ctx,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        PageResponse.check(page, size);
        return audit.list(ctx.tenantId(), page, size);
    }
}
