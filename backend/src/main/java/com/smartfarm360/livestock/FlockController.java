package com.smartfarm360.livestock;

import com.smartfarm360.livestock.FlockDtos.*;
import com.smartfarm360.livestock.LivestockDtos.Group;
import com.smartfarm360.livestock.LivestockDtos.RecordRef;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.Idempotent;
import com.smartfarm360.platform.tenant.FarmModule;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Poultry batches and goat and sheep herds (poultry, goats and sheep design, approved 2026-10-21). */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/livestock")
@RequiresModule({FarmModule.POULTRY, FarmModule.GOAT_SHEEP})
public class FlockController {
    private static final String KEY = "Idempotency-Key";
    private static final String ISO = "yyyy-MM-dd";
    private final FlockService flocks;
    private final FlockReports reports;
    private final HerdService herd;
    private final EventService events;
    private final LivestockExports exports;
    private final ExportService export;

    public FlockController(FlockService flocks, FlockReports reports, HerdService herd, EventService events, LivestockExports exports, ExportService export) {
        this.flocks = flocks;
        this.reports = reports;
        this.herd = herd;
        this.events = events;
        this.exports = exports;
        this.export = export;
    }

    @GetMapping("/flocks")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public List<Group> flocks(TenantContext ctx) {
        return herd.groups(ctx).stream().filter(Group::counted).filter(g -> ctx.enabledModules().contains(HerdService.moduleFor(g.species()))).toList();
    }

    @PostMapping("/flocks")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Group place(TenantContext ctx, @Valid @RequestBody PlacementRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return flocks.place(ctx, r, key);
    }

    /** The batch's or herd's varieties with their heads today (bin and batch varieties design, V2). */
    @GetMapping("/flocks/{groupId}/lines")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public List<FlockLine> lines(TenantContext ctx, @PathVariable long groupId) { return flocks.lines(ctx, groupId); }

    @GetMapping("/flocks/{groupId}/movements")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public List<Movement> movements(TenantContext ctx, @PathVariable long groupId) { return flocks.movements(ctx, groupId); }

    @Idempotent
    @PostMapping("/flocks/{groupId}/movements")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Movement> move(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody MovementRequest r) { return flocks.move(ctx, groupId, r); }

    @DeleteMapping("/flock-movements/{movementId}")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMovement(TenantContext ctx, @PathVariable long movementId) { flocks.deleteMovement(ctx, movementId); }

    @PostMapping("/flocks/{groupId}/purchases")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Movement purchase(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody PurchaseRequest r,
                             @RequestHeader(value = KEY, required = false) String key) {
        return flocks.purchase(ctx, groupId, r, key);
    }

    @PostMapping("/flocks/{groupId}/sales")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Movement sell(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody SaleRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return flocks.sell(ctx, groupId, r, key);
    }

    @PostMapping("/flocks/{groupId}/births")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @RequiresModule(FarmModule.GOAT_SHEEP)
    @ResponseStatus(HttpStatus.CREATED)
    public LivestockDtos.Event births(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody BirthRequest r) {
        return flocks.births(ctx, groupId, r, events);
    }

    // ---- Daily batch record and eggs (poultry) ----

    @GetMapping("/flocks/{groupId}/days/{date}")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    @RequiresModule(FarmModule.POULTRY)
    public BatchDay day(TenantContext ctx, @PathVariable long groupId, @PathVariable @DateTimeFormat(pattern = ISO) LocalDate date) {
        return flocks.day(ctx, groupId, date);
    }

    @Idempotent
    @PutMapping("/flocks/{groupId}/days/{date}")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @RequiresModule(FarmModule.POULTRY)
    public BatchDay saveDay(TenantContext ctx, @PathVariable long groupId, @PathVariable @DateTimeFormat(pattern = ISO) LocalDate date,
                            @Valid @RequestBody BatchDayRequest r) {
        return flocks.saveDay(ctx, groupId, date, r);
    }

    @PostMapping("/egg-uses")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @RequiresModule(FarmModule.POULTRY)
    @ResponseStatus(HttpStatus.CREATED)
    public List<RecordRef> eggUses(TenantContext ctx, @Valid @RequestBody EggUseRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return flocks.eggUses(ctx, r, key);
    }

    @PostMapping("/egg-sales")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @RequiresModule(FarmModule.POULTRY)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordRef eggSale(TenantContext ctx, @Valid @RequestBody EggSaleRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return flocks.eggSale(ctx, r, key);
    }

    // ---- Health plans (J9) ----

    @GetMapping("/flocks/{groupId}/plan")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public List<PlanItem> plan(TenantContext ctx, @PathVariable long groupId) { return flocks.plan(ctx, groupId); }

    @PostMapping("/flocks/{groupId}/plan")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlanItem addPlanItem(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody PlanItemRequest r) { return flocks.addPlanItem(ctx, groupId, r); }

    @PostMapping("/flocks/{groupId}/plan/copy")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<PlanItem> copyPlan(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody CopyPlanRequest r) { return flocks.copyPlan(ctx, groupId, r); }

    // ---- Figures (J8, J14) ----

    @GetMapping("/flocks/{groupId}/report")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public FlockReport report(TenantContext ctx, @PathVariable long groupId, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                              @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to) {
        return reports.report(ctx, groupId, from, to);
    }

    @GetMapping("/reports/flocks")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public ResponseEntity<?> flockReports(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                          @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to, @RequestParam(required = false) String species,
                                          @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        List<FlockReport> list = reports.all(ctx, from, to, species);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        return export.export(ctx, exports.flocks(ReportLabels.language(lang), from, to, list), format, lang, "batches-and-herds", from, to);
    }
}
