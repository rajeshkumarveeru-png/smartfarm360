package com.smartfarm360.aquaculture;

import com.smartfarm360.aquaculture.AquaDtos.*;
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
import java.util.Map;

/** Fish farming: ponds, culture cycles, water quality and harvests (aquaculture design, approved 2026-10-21). */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/aquaculture")
@RequiresModule(FarmModule.AQUACULTURE)
public class AquacultureController {
    private static final String KEY = "Idempotency-Key";
    private static final String ISO = "yyyy-MM-dd";
    private final PondService ponds;
    private final WaterService water;
    private final AquaReports reports;
    private final AquaExports exports;
    private final ExportService export;

    public AquacultureController(PondService ponds, WaterService water, AquaReports reports, AquaExports exports, ExportService export) {
        this.ponds = ponds;
        this.water = water;
        this.reports = reports;
        this.exports = exports;
        this.export = export;
    }

    // ---- Ponds (K1) ----

    @GetMapping("/ponds")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public List<Pond> ponds(TenantContext ctx) {
        Map<Long, Reading> latest = water.latest(ctx);
        List<Cycle> open = ponds.cycles(ctx, null).stream().filter(c -> !"HARVESTED".equals(c.status())).toList();
        return ponds.pondRows(ctx).stream().map(p -> new Pond(p.id(), p.farmId(), p.farmName(), p.parcelId(), p.name(), p.kind(), p.waterArea(), p.areaUnit(),
                p.areaAcres(), p.depthM(), p.active(), open.stream().filter(c -> c.pondId() == p.id()).findFirst().orElse(null), latest.get(p.id()))).toList();
    }

    @GetMapping("/ponds/{pondId}")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public Pond pond(TenantContext ctx, @PathVariable long pondId) {
        ponds.pondRow(ctx, pondId);
        return ponds(ctx).stream().filter(p -> p.id() == pondId).findFirst().orElseThrow();
    }

    @PostMapping("/ponds")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Pond createPond(TenantContext ctx, @Valid @RequestBody PondRequest r) { return pond(ctx, ponds.savePond(ctx, null, r)); }

    @PutMapping("/ponds/{pondId}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    public Pond updatePond(TenantContext ctx, @PathVariable long pondId, @Valid @RequestBody PondRequest r) { return pond(ctx, ponds.savePond(ctx, pondId, r)); }

    // ---- Culture cycles (K2) ----

    @GetMapping("/cycles")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public List<Cycle> cycles(TenantContext ctx, @RequestParam(required = false) Long pondId) {
        if (pondId != null) ponds.pondRow(ctx, pondId);
        return ponds.cycles(ctx, pondId);
    }

    @PostMapping("/ponds/{pondId}/cycles")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Cycle startCycle(TenantContext ctx, @PathVariable long pondId, @Valid @RequestBody CycleRequest r) { return ponds.startCycle(ctx, pondId, r); }

    @GetMapping("/cycles/{cycleId}")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public Cycle cycle(TenantContext ctx, @PathVariable long cycleId) { return ponds.cycle(ctx, cycleId); }

    @PutMapping("/cycles/{cycleId}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    public Cycle updateCycle(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody CycleRequest r) { return ponds.updateCycle(ctx, cycleId, r); }

    // ---- Stocking (K3, K4) ----

    @GetMapping("/cycles/{cycleId}/stockings")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public List<Stocking> stockings(TenantContext ctx, @PathVariable long cycleId) { return ponds.stockings(ctx, cycleId); }

    @PostMapping("/cycles/{cycleId}/stockings")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Stocking stock(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody StockingRequest r,
                          @RequestHeader(value = KEY, required = false) String key) {
        return ponds.stock(ctx, cycleId, r, key);
    }

    @DeleteMapping("/stockings/{stockingId}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStocking(TenantContext ctx, @PathVariable long stockingId) { ponds.deleteStocking(ctx, stockingId); }

    // ---- Daily pond record (K5) ----

    @GetMapping("/cycles/{cycleId}/days/{date}")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public Day day(TenantContext ctx, @PathVariable long cycleId, @PathVariable @DateTimeFormat(pattern = ISO) LocalDate date) {
        return ponds.day(ctx, cycleId, date);
    }

    @Idempotent
    @PutMapping("/cycles/{cycleId}/days/{date}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    public Day saveDay(TenantContext ctx, @PathVariable long cycleId, @PathVariable @DateTimeFormat(pattern = ISO) LocalDate date,
                       @Valid @RequestBody DayRequest r) {
        return ponds.saveDay(ctx, cycleId, date, r);
    }

    // ---- Samples (K6) ----

    @GetMapping("/cycles/{cycleId}/samples")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public List<Sample> samples(TenantContext ctx, @PathVariable long cycleId) { return ponds.samples(ctx, cycleId); }

    @Idempotent
    @PostMapping("/cycles/{cycleId}/samples")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Sample sample(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody SampleRequest r) { return ponds.sample(ctx, cycleId, r); }

    @DeleteMapping("/samples/{sampleId}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSample(TenantContext ctx, @PathVariable long sampleId) { ponds.deleteSample(ctx, sampleId); }

    // ---- Harvests (K8) ----

    @GetMapping("/cycles/{cycleId}/harvests")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public List<Harvest> harvests(TenantContext ctx, @PathVariable long cycleId) { return ponds.harvests(ctx, cycleId); }

    @PostMapping("/cycles/{cycleId}/harvests")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Harvest harvest(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody HarvestRequest r,
                           @RequestHeader(value = KEY, required = false) String key) {
        return ponds.harvest(ctx, cycleId, r, key);
    }

    @DeleteMapping("/harvests/{harvestId}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHarvest(TenantContext ctx, @PathVariable long harvestId) { ponds.deleteHarvest(ctx, harvestId); }

    // ---- Water quality (K7) ----

    @GetMapping("/readings")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public ResponseEntity<?> readings(TenantContext ctx, @RequestParam(required = false) Long pondId,
                                      @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to,
                                      @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        List<Reading> list = water.readings(ctx, pondId, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        return export.export(ctx, exports.water(ReportLabels.language(lang), from, to, list), format, lang, "water-quality", from, to);
    }

    @Idempotent
    @PostMapping("/ponds/{pondId}/readings")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Reading record(TenantContext ctx, @PathVariable long pondId, @Valid @RequestBody ReadingRequest r) { return water.record(ctx, pondId, r); }

    @DeleteMapping("/readings/{readingId}")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReading(TenantContext ctx, @PathVariable long readingId) { water.delete(ctx, readingId); }

    @GetMapping("/water-limits")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public Limits limits(TenantContext ctx, @RequestParam(required = false) Long pondId) { return water.limits(ctx, pondId); }

    @PutMapping("/water-limits")
    @RequiresPermission(Permission.AQUACULTURE_WRITE)
    public Limits saveLimits(TenantContext ctx, @Valid @RequestBody LimitsRequest r) { return water.saveLimits(ctx, r); }

    // ---- Figures and reports (K9, K14) ----

    @GetMapping("/cycles/{cycleId}/report")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public CycleReport report(TenantContext ctx, @PathVariable long cycleId, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                              @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to) {
        return reports.report(ctx, cycleId, from, to);
    }

    @GetMapping("/reports/cycles")
    @RequiresPermission(Permission.AQUACULTURE_READ)
    public ResponseEntity<?> cycleReports(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                          @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to,
                                          @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        if (to.isBefore(from)) throw com.smartfarm360.platform.web.ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        List<CycleReport> list = reports.all(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        return export.export(ctx, exports.cycles(ReportLabels.language(lang), from, to, list), format, lang, "pond-cycles", from, to);
    }
}
