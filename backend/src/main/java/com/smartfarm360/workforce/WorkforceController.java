package com.smartfarm360.workforce;

import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import com.smartfarm360.platform.web.Idempotent;
import com.smartfarm360.workforce.WorkforceDtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Workforce API (workforce and assets design, approved 2026-10-20). Recording money (advances, pay runs, payments) accepts
 * an {@code Idempotency-Key}; those records are reversed through the finance API.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/workforce")
@RequiresModule(FarmModule.WORKFORCE)
public class WorkforceController {
    private static final String KEY = "Idempotency-Key";
    private final WorkerService workers;
    private final PayrollService payroll;

    private final WorkforceExports exports;
    private final ExportService export;

    public WorkforceController(WorkerService workers, PayrollService payroll, WorkforceExports exports, ExportService export) {
        this.exports = exports;
        this.export = export;
        this.workers = workers;
        this.payroll = payroll;
    }

    @GetMapping("/workers")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public List<Worker> workers(TenantContext ctx, @RequestParam(defaultValue = "false") boolean includeLeft) { return workers.workers(ctx, includeLeft); }

    @PostMapping("/workers")
    @RequiresPermission(Permission.WORKFORCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Worker createWorker(TenantContext ctx, @Valid @RequestBody WorkerRequest r) { return workers.createWorker(ctx, r); }

    @GetMapping("/workers/{workerId}")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public Worker worker(TenantContext ctx, @PathVariable long workerId) { return workers.worker(ctx, workerId); }

    @PatchMapping("/workers/{workerId}")
    @RequiresPermission(Permission.WORKFORCE_WRITE)
    public Worker updateWorker(TenantContext ctx, @PathVariable long workerId, @Valid @RequestBody WorkerPatch r) { return workers.updateWorker(ctx, workerId, r); }

    /** Wage rates are money: changing them needs payroll permission. */
    @PostMapping("/workers/{workerId}/rates")
    @RequiresPermission(Permission.PAYROLL_MANAGE)
    public Worker addRate(TenantContext ctx, @PathVariable long workerId, @Valid @RequestBody RateRequest r) { return workers.addRate(ctx, workerId, r); }

    @GetMapping("/workers/{workerId}/statement")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public Statement statement(TenantContext ctx, @PathVariable long workerId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return workers.statement(ctx, workerId, from, to);
    }

    @GetMapping("/attendance/{date}")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public Muster muster(TenantContext ctx, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return workers.muster(ctx, date); }

    @Idempotent
    @PutMapping("/attendance/{date}")
    @RequiresPermission(Permission.WORKFORCE_WRITE)
    public Muster saveMuster(TenantContext ctx, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @Valid @RequestBody MusterRequest r) {
        return workers.saveMuster(ctx, date, r);
    }

    @GetMapping("/attendance-sheet")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public MonthSheet monthSheet(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) { return workers.monthSheet(ctx, month); }

    @GetMapping("/piece-work")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public List<PieceWork> pieceWork(TenantContext ctx, @RequestParam(required = false) Long workerId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return workers.pieceWork(ctx, workerId, from, to);
    }

    @PostMapping("/piece-work")
    @RequiresPermission(Permission.WORKFORCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PieceWork addPieceWork(TenantContext ctx, @Valid @RequestBody PieceWorkRequest r) { return workers.addPieceWork(ctx, r); }

    @DeleteMapping("/piece-work/{pieceWorkId}")
    @RequiresPermission(Permission.WORKFORCE_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePieceWork(TenantContext ctx, @PathVariable long pieceWorkId) { workers.deletePieceWork(ctx, pieceWorkId); }

    @PostMapping("/advances")
    @RequiresPermission(Permission.PAYROLL_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document advance(TenantContext ctx, @Valid @RequestBody AdvanceRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return payroll.advance(ctx, r, key);
    }

    @PostMapping("/advance-repayments")
    @RequiresPermission(Permission.PAYROLL_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document advanceRepayment(TenantContext ctx, @Valid @RequestBody AdvanceRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return payroll.advanceRepayment(ctx, r, key);
    }

    @PostMapping("/pay-runs/preview")
    @RequiresPermission(Permission.PAYROLL_MANAGE)
    public PayRunPreview previewPayRun(TenantContext ctx, @Valid @RequestBody PayRunRequest r) { return payroll.preview(ctx, r); }

    @PostMapping("/pay-runs")
    @RequiresPermission(Permission.PAYROLL_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document postPayRun(TenantContext ctx, @Valid @RequestBody PayRunRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return payroll.postPayRun(ctx, r, key);
    }

    @PostMapping("/wage-payments")
    @RequiresPermission(Permission.PAYROLL_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<RecordRef> pay(TenantContext ctx, @Valid @RequestBody WagePaymentRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return payroll.pay(ctx, r, key);
    }

    @GetMapping("/reports/labour")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public LabourByActivity labour(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return workers.labourByActivity(ctx, from, to);
    }

    /** Wages by worker and labour by crop; JSON, or a file with format=csv|xlsx (dashboard and reports design, H5, H7). */
    @GetMapping("/reports/wages")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public ResponseEntity<?> wages(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        WagesReport r = workers.wages(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(r);
        return export.export(ctx, exports.wages(ReportLabels.language(lang), r), format, lang, "wages", from, to);
    }

    @GetMapping("/reports/reconciliation")
    @RequiresPermission(Permission.WORKFORCE_READ)
    public Reconciliation reconciliation(TenantContext ctx) { return workers.reconciliation(ctx); }
}
