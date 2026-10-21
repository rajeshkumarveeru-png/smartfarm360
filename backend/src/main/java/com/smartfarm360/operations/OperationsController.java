package com.smartfarm360.operations;

import com.smartfarm360.crop.CropDtos.Activity;
import com.smartfarm360.crop.CropDtos.Harvest;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.operations.OperationsDtos.*;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportLabels;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Field operations (crop design, sections 4, 5 and 7): activities and harvests that move stock and money, and the crop
 * cycle reports. Recording accepts an {@code Idempotency-Key}. Using stock also needs {@code INVENTORY_WRITE}, paid work
 * {@code FINANCE_WRITE} and reversals {@code FINANCE_REVERSE}; these are checked in the service.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@RequiresModule(FarmModule.CROP)
public class OperationsController {
    private static final String KEY = "Idempotency-Key";
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private final FieldOperationsService operations;
    private final CropReportService reports;

    private final CropExports exports;
    private final ExportService export;

    public OperationsController(FieldOperationsService operations, CropReportService reports, CropExports exports, ExportService export) {
        this.exports = exports;
        this.export = export;
        this.operations = operations;
        this.reports = reports;
    }

    @PostMapping("/activities")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResult activity(TenantContext ctx, @Valid @RequestBody ActivityRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return operations.recordActivity(ctx, r, key);
    }

    @PostMapping("/activities/{activityId}/reverse")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    public Activity reverseActivity(TenantContext ctx, @PathVariable long activityId, @RequestBody(required = false) ReverseRequest r) {
        return operations.reverseActivity(ctx, activityId, r);
    }

    @PostMapping("/harvests")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public HarvestResult harvest(TenantContext ctx, @Valid @RequestBody HarvestRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return operations.recordHarvest(ctx, r, key);
    }

    @PostMapping("/harvests/{harvestId}/reverse")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    public Harvest reverseHarvest(TenantContext ctx, @PathVariable long harvestId, @RequestBody(required = false) ReverseRequest r) {
        return operations.reverseHarvest(ctx, harvestId, r);
    }

    @GetMapping("/crops/{cropId}/produce-item")
    @RequiresPermission(Permission.CROP_READ)
    public ProduceItemView produceItem(TenantContext ctx, @PathVariable long cropId) { return new ProduceItemView(operations.currentProduceItem(ctx, cropId)); }

    @PutMapping("/crops/{cropId}/produce-item")
    @RequiresPermission(Permission.CROP_WRITE)
    public StockDtos.Item setProduceItem(TenantContext ctx, @PathVariable long cropId, @Valid @RequestBody ProduceItemRequest r) {
        return operations.setProduceItem(ctx, cropId, r.itemId());
    }

    @GetMapping("/cultivation-cycles/{cycleId}/report")
    @RequiresPermission(Permission.CROP_READ)
    public ResponseEntity<?> report(TenantContext ctx, @PathVariable long cycleId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(defaultValue = "ACCRUAL") String basis, @RequestParam(required = false) BigDecimal price,
                                    @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        CycleReport r = reports.report(ctx, cycleId, from, to, basis, price);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(r);
        return export.export(ctx, exports.cycle(ReportLabels.language(lang), r), format, lang, "crop-cycle-" + cycleId, r.from(), r.to());
    }

    @GetMapping("/cultivation-cycles/{cycleId}/report.csv")
    @RequiresPermission(Permission.CROP_READ)
    public ResponseEntity<String> reportCsv(TenantContext ctx, @PathVariable long cycleId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "ACCRUAL") String basis, @RequestParam(required = false) BigDecimal price) {
        return csv("crop-cycle-" + cycleId + ".csv", reports.reportCsv(ctx, cycleId, from, to, basis, price));
    }

    @GetMapping("/crops/{cropId}/comparison")
    @RequiresPermission(Permission.CROP_READ)
    public ResponseEntity<?> comparison(TenantContext ctx, @PathVariable long cropId,
                                        @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        Comparison c = reports.comparison(ctx, cropId);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(c);
        return export.export(ctx, exports.comparison(ReportLabels.language(lang), c), format, lang, "crop-comparison-" + cropId, null, null);
    }

    @GetMapping("/crops/{cropId}/comparison.csv")
    @RequiresPermission(Permission.CROP_READ)
    public ResponseEntity<String> comparisonCsv(TenantContext ctx, @PathVariable long cropId) {
        return csv("crop-comparison-" + cropId + ".csv", reports.comparisonCsv(ctx, cropId));
    }

    private static ResponseEntity<String> csv(String filename, String body) {
        return ResponseEntity.ok().contentType(CSV).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").body("﻿" + body);
    }
}
