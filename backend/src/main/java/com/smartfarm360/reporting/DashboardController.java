package com.smartfarm360.reporting;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.reporting.DashboardDtos.Dashboard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * The farm business dashboard (dashboard and reports design, H1, H2). Every member may open it; each tile inside checks
 * its own permission and module, so a reader sees only what their role allows.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class DashboardController {
    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) { this.dashboard = dashboard; }

    @GetMapping("/dashboard")
    @RequiresPermission(Permission.TENANT_READ)
    public Dashboard dashboard(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return dashboard.dashboard(ctx, asOf == null ? LocalDate.now() : asOf);
    }
}
