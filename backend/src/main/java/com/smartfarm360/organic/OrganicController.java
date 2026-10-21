package com.smartfarm360.organic;

import com.smartfarm360.organic.OrganicDtos.*;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import com.smartfarm360.platform.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Compost, vermicompost and liquid manure batches (organic resources design, approved 2026-10-21). */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/organic")
@RequiresModule(FarmModule.ORGANIC)
public class OrganicController {
    private static final String KEY = "Idempotency-Key";
    private static final String ISO = "yyyy-MM-dd";
    private final OrganicService organic;
    private final ReportLabels labels;
    private final ExportService export;

    public OrganicController(OrganicService organic, ReportLabels labels, ExportService export) {
        this.organic = organic;
        this.labels = labels;
        this.export = export;
    }

    @GetMapping("/batches")
    @RequiresPermission(Permission.ORGANIC_READ)
    public List<Batch> batches(TenantContext ctx) { return organic.batches(ctx); }

    @PostMapping("/batches")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Batch create(TenantContext ctx, @Valid @RequestBody BatchRequest r) { return organic.create(ctx, r); }

    @GetMapping("/batches/{batchId}")
    @RequiresPermission(Permission.ORGANIC_READ)
    public Batch batch(TenantContext ctx, @PathVariable long batchId) { return organic.batch(ctx, batchId); }

    @PutMapping("/batches/{batchId}")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    public Batch update(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody BatchRequest r) { return organic.update(ctx, batchId, r); }

    @PostMapping("/batches/{batchId}/finish")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    public Batch finish(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody FinishRequest r) { return organic.finish(ctx, batchId, r); }

    @PostMapping("/batches/{batchId}/reopen")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    public Batch reopen(TenantContext ctx, @PathVariable long batchId) { return organic.reopen(ctx, batchId); }

    @GetMapping("/batches/{batchId}/logs")
    @RequiresPermission(Permission.ORGANIC_READ)
    public List<Log> logs(TenantContext ctx, @PathVariable long batchId) { return organic.logs(ctx, batchId); }

    @PostMapping("/batches/{batchId}/logs")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Log addLog(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody LogRequest r) { return organic.addLog(ctx, batchId, r); }

    @DeleteMapping("/logs/{logId}")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLog(TenantContext ctx, @PathVariable long logId) { organic.deleteLog(ctx, logId); }

    @GetMapping("/batches/{batchId}/outputs")
    @RequiresPermission(Permission.ORGANIC_READ)
    public List<Output> outputs(TenantContext ctx, @PathVariable long batchId) { return organic.outputs(ctx, batchId); }

    /** Output into stock moves stock, so it also needs permission to record stock. */
    @PostMapping("/batches/{batchId}/outputs")
    @RequiresPermission(Permission.ORGANIC_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Output output(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody OutputRequest r,
                         @RequestHeader(value = KEY, required = false) String key) {
        if (!ctx.has(Permission.INVENTORY_WRITE)) {
            throw ApiException.forbidden("INVENTORY_PERMISSION_REQUIRED", "Putting output into stock needs permission to record stock.");
        }
        return organic.output(ctx, batchId, r, key);
    }

    @GetMapping("/batches/{batchId}/report")
    @RequiresPermission(Permission.ORGANIC_READ)
    public BatchReport report(TenantContext ctx, @PathVariable long batchId, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                              @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to) {
        return organic.report(ctx, batchId, from, to);
    }

    @GetMapping("/reports/batches")
    @RequiresPermission(Permission.ORGANIC_READ)
    public ResponseEntity<?> batchesReport(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                           @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to, @RequestParam(required = false) String format,
                                           @RequestParam(required = false) String lang) {
        if (to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        List<BatchReport> list = organic.all(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        String l = ReportLabels.language(lang);
        ReportDocument d = new ReportDocument(labels.t(l, "organic.report.batches"));
        d.meta(labels.t(l, "reports.export.period"), from + " – " + to);
        d.meta(labels.t(l, "reports.export.figures"), labels.t(l, "reports.export.actualPosted"));
        d.text(labels.t(l, "organic.batch")).text(labels.t(l, "organic.kindLabel")).text(labels.t(l, "organic.statusLabel"))
                .column(labels.t(l, "organic.days"), ReportDocument.Kind.NUMBER).column(labels.t(l, "organic.inputKg"), ReportDocument.Kind.NUMBER)
                .column(labels.t(l, "organic.output"), ReportDocument.Kind.NUMBER).text(labels.t(l, "organic.unitLabel"))
                .column(labels.t(l, "organic.yield"), ReportDocument.Kind.NUMBER)
                .money(labels.t(l, "fin.costs")).money(labels.t(l, "organic.movedOut")).money(labels.t(l, "fin.revenue")).money(labels.t(l, "reports.profit"))
                .money(labels.t(l, "organic.costPerUnit")).column(labels.t(l, "organic.inStock"), ReportDocument.Kind.NUMBER);
        for (BatchReport r : list) {
            Figures f = r.period();
            d.row(r.batch().name(), labels.t(l, "organic.kind." + r.batch().kind()), labels.t(l, "organic.status." + r.batch().status()),
                    f.days() == null ? null : BigDecimal.valueOf(f.days()), f.inputKg(), f.output(), f.outputUnit(), f.yieldPercent(), f.costs(), f.movedOut(),
                    f.revenue(), f.profit(), f.costPerUnit(), f.inStock());
        }
        return export.export(ctx, d, format, lang, "organic-batches", from, to);
    }
}
