package com.smartfarm360.processing;

import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.Idempotent;
import com.smartfarm360.processing.ProcessingDtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Processing batches and the lot trace (processing and trading design, approved 2026-10-21). */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/processing")
@RequiresModule(FarmModule.PROCESSING)
public class ProcessingController {
    private static final String KEY = "Idempotency-Key";
    private static final String ISO = "yyyy-MM-dd";
    private final ProcessingService processing;
    private final TraceService trace;
    private final ReportLabels labels;
    private final ExportService export;

    public ProcessingController(ProcessingService processing, TraceService trace, ReportLabels labels, ExportService export) {
        this.processing = processing;
        this.trace = trace;
        this.labels = labels;
        this.export = export;
    }

    @GetMapping("/batches")
    @RequiresPermission(Permission.PROCESSING_READ)
    public List<Batch> batches(TenantContext ctx) { return processing.batches(ctx); }

    @PostMapping("/batches")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Batch create(TenantContext ctx, @Valid @RequestBody BatchRequest r) { return processing.create(ctx, r); }

    @GetMapping("/batches/{batchId}")
    @RequiresPermission(Permission.PROCESSING_READ)
    public Batch batch(TenantContext ctx, @PathVariable long batchId) { return processing.batch(ctx, batchId); }

    @PutMapping("/batches/{batchId}")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    public Batch update(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody BatchRequest r) { return processing.update(ctx, batchId, r); }

    @PostMapping("/batches/{batchId}/finish")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    public Batch finish(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody FinishRequest r) { return processing.finish(ctx, batchId, r); }

    @PostMapping("/batches/{batchId}/reopen")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    public Batch reopen(TenantContext ctx, @PathVariable long batchId) { return processing.reopen(ctx, batchId); }

    @GetMapping("/batches/{batchId}/steps")
    @RequiresPermission(Permission.PROCESSING_READ)
    public List<Step> steps(TenantContext ctx, @PathVariable long batchId) { return processing.steps(ctx, batchId); }

    @Idempotent
    @PostMapping("/batches/{batchId}/steps")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Step addStep(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody StepRequest r) { return processing.addStep(ctx, batchId, r); }

    @DeleteMapping("/steps/{stepId}")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStep(TenantContext ctx, @PathVariable long stepId) { processing.deleteStep(ctx, stepId); }

    @GetMapping("/batches/{batchId}/outputs")
    @RequiresPermission(Permission.PROCESSING_READ)
    public List<Output> outputs(TenantContext ctx, @PathVariable long batchId) { return processing.outputs(ctx, batchId); }

    /** Product output moves stock, so it also needs permission to record stock; waste does not. */
    @PostMapping("/batches/{batchId}/outputs")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Output output(TenantContext ctx, @PathVariable long batchId, @Valid @RequestBody OutputRequest r,
                         @RequestHeader(value = KEY, required = false) String key) {
        if (!"WASTE".equals(r.role()) && !ctx.has(Permission.INVENTORY_WRITE)) {
            throw ApiException.forbidden("INVENTORY_PERMISSION_REQUIRED", "Putting output into stock needs permission to record stock.");
        }
        return processing.output(ctx, batchId, r, key);
    }

    @DeleteMapping("/outputs/{outputId}")
    @RequiresPermission(Permission.PROCESSING_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWaste(TenantContext ctx, @PathVariable long outputId) { processing.deleteWaste(ctx, outputId); }

    @GetMapping("/batches/{batchId}/report")
    @RequiresPermission(Permission.PROCESSING_READ)
    public BatchReport report(TenantContext ctx, @PathVariable long batchId, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                              @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to) {
        return processing.report(ctx, batchId, from, to);
    }

    @GetMapping("/trace")
    @RequiresPermission(Permission.PROCESSING_READ)
    public List<TracedLot> trace(TenantContext ctx, @RequestParam String lotNo) { return trace.trace(ctx, lotNo); }

    @GetMapping("/reports/batches")
    @RequiresPermission(Permission.PROCESSING_READ)
    public ResponseEntity<?> batchesReport(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                           @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to, @RequestParam(required = false) String format,
                                           @RequestParam(required = false) String lang) {
        if (to.isBefore(from)) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        List<BatchReport> list = processing.all(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        String l = ReportLabels.language(lang);
        ReportDocument d = new ReportDocument(labels.t(l, "processing.report.batches"));
        d.meta(labels.t(l, "reports.export.period"), from + " – " + to);
        d.meta(labels.t(l, "reports.export.figures"), labels.t(l, "reports.export.actualPosted"));
        d.text(labels.t(l, "processing.code")).text(labels.t(l, "processing.batch")).text(labels.t(l, "processing.kindLabel"))
                .text(labels.t(l, "processing.mainProduct")).column(labels.t(l, "processing.inputKg"), ReportDocument.Kind.NUMBER)
                .column(labels.t(l, "processing.mainKg"), ReportDocument.Kind.NUMBER).column(labels.t(l, "processing.mainYield"), ReportDocument.Kind.NUMBER)
                .column(labels.t(l, "processing.lossKg"), ReportDocument.Kind.NUMBER)
                .money(labels.t(l, "fin.costs")).money(labels.t(l, "fin.revenue")).money(labels.t(l, "reports.profit"))
                .money(labels.t(l, "processing.mainCostPerKg")).money(labels.t(l, "processing.rawCostPerKg"));
        for (BatchReport r : list) {
            Figures f = r.period();
            BigDecimal mainYield = f.outputs().stream().filter(o -> "MAIN".equals(o.role())).map(OutputYield::yieldPercent).filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal::add).orElse(null);
            d.row(r.batch().code(), r.batch().name(), labels.t(l, "processing.kind." + r.batch().kind()), r.batch().mainItemName(), f.inputKg(), f.mainKg(), mainYield,
                    f.lossKg(), f.costs(), f.revenue(), f.profit(), f.mainCostPerKg(), f.rawCostPerKg());
        }
        return export.export(ctx, d, format, lang, "processing-batches", from, to);
    }
}
