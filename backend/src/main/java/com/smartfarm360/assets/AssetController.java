package com.smartfarm360.assets;

import com.smartfarm360.assets.AssetDtos.*;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Assets API (workforce and assets design, approved 2026-10-20). Buying an asset also needs {@code FINANCE_WRITE};
 * depreciation runs and disposals need {@code FINANCE_MANAGE}; their records are reversed through the finance API.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/assets")
@RequiresModule(FarmModule.ASSETS)
public class AssetController {
    private static final String KEY = "Idempotency-Key";
    private final AssetService assets;

    public AssetController(AssetService assets) { this.assets = assets; }

    @GetMapping
    @RequiresPermission(Permission.ASSETS_READ)
    public List<Asset> assets(TenantContext ctx, @RequestParam(defaultValue = "false") boolean includeDisposed) { return assets.assets(ctx, includeDisposed); }

    @GetMapping("/{assetId}")
    @RequiresPermission(Permission.ASSETS_READ)
    public Asset asset(TenantContext ctx, @PathVariable long assetId) { return assets.asset(ctx, assetId); }

    @PostMapping("/purchases")
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Asset purchase(TenantContext ctx, @Valid @RequestBody PurchaseRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return assets.purchase(ctx, r, key);
    }

    @GetMapping("/unregistered")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<UnregisteredLine> unregistered(TenantContext ctx) { return assets.unregistered(ctx); }

    @PostMapping
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Asset register(TenantContext ctx, @Valid @RequestBody RegisterRequest r) { return assets.register(ctx, r); }

    @PatchMapping("/{assetId}")
    @RequiresPermission(Permission.ASSETS_WRITE)
    public Asset update(TenantContext ctx, @PathVariable long assetId, @Valid @RequestBody AssetPatch r) { return assets.update(ctx, assetId, r); }

    @DeleteMapping("/{assetId}")
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(TenantContext ctx, @PathVariable long assetId) { assets.remove(ctx, assetId); }

    @GetMapping("/{assetId}/schedule")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<ScheduleLine> schedule(TenantContext ctx, @PathVariable long assetId) { return assets.schedule(ctx, assetId); }

    @PostMapping("/{assetId}/disposal")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Asset dispose(TenantContext ctx, @PathVariable long assetId, @Valid @RequestBody DisposalRequest r,
                         @RequestHeader(value = KEY, required = false) String key) {
        return assets.dispose(ctx, assetId, r, key);
    }

    @GetMapping("/depreciation-runs")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<RunSummary> runs(TenantContext ctx) { return assets.runs(ctx); }

    @PostMapping("/depreciation-runs/preview")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    public RunPreview previewRun(TenantContext ctx, @Valid @RequestBody RunRequest r) { return assets.previewRun(ctx, r.month()); }

    @PostMapping("/depreciation-runs")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document run(TenantContext ctx, @Valid @RequestBody RunRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return assets.run(ctx, r, key);
    }

    @GetMapping("/{assetId}/usage")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<Usage> usage(TenantContext ctx, @PathVariable long assetId) { return assets.usage(ctx, assetId); }

    @PostMapping("/{assetId}/usage")
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Usage addUsage(TenantContext ctx, @PathVariable long assetId, @Valid @RequestBody UsageRequest r) { return assets.addUsage(ctx, assetId, r); }

    @DeleteMapping("/usage/{usageId}")
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUsage(TenantContext ctx, @PathVariable long usageId) { assets.deleteUsage(ctx, usageId); }

    @GetMapping("/maintenance-plans")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<Plan> duePlans(TenantContext ctx) { return assets.plans(ctx, null); }

    @GetMapping("/{assetId}/maintenance-plans")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<Plan> plans(TenantContext ctx, @PathVariable long assetId) { return assets.plans(ctx, assetId); }

    @PostMapping("/{assetId}/maintenance-plans")
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Plan addPlan(TenantContext ctx, @PathVariable long assetId, @Valid @RequestBody PlanRequest r) { return assets.addPlan(ctx, assetId, r); }

    @PutMapping("/maintenance-plans/{planId}")
    @RequiresPermission(Permission.ASSETS_WRITE)
    public Plan updatePlan(TenantContext ctx, @PathVariable long planId, @Valid @RequestBody PlanRequest r) { return assets.updatePlan(ctx, planId, r); }

    @GetMapping("/{assetId}/maintenance")
    @RequiresPermission(Permission.ASSETS_READ)
    public List<Maintenance> maintenance(TenantContext ctx, @PathVariable long assetId) { return assets.maintenance(ctx, assetId); }

    @PostMapping("/{assetId}/maintenance")
    @RequiresPermission(Permission.ASSETS_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Maintenance addMaintenance(TenantContext ctx, @PathVariable long assetId, @Valid @RequestBody MaintenanceRequest r) {
        return assets.addMaintenance(ctx, assetId, r);
    }

    @GetMapping("/{assetId}/costs")
    @RequiresPermission(Permission.ASSETS_READ)
    public Costs costs(TenantContext ctx, @PathVariable long assetId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return assets.costs(ctx, assetId, from, to);
    }

    @GetMapping("/reports/reconciliation")
    @RequiresPermission(Permission.ASSETS_READ)
    public Reconciliation reconciliation(TenantContext ctx) { return assets.reconciliation(ctx); }

    @GetMapping("/reports/register.csv")
    @RequiresPermission(Permission.ASSETS_READ)
    public ResponseEntity<String> registerCsv(TenantContext ctx) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"asset-register.csv\"").body("﻿" + assets.registerCsv(ctx));
    }
}
