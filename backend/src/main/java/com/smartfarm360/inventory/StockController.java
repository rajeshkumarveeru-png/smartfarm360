package com.smartfarm360.inventory;

import com.smartfarm360.inventory.StockDtos.*;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Stock API (stock movement design, approved 2026-10-20). Bills and sales with item lines go through the finance
 * API; the records here move stock on their own. Record-creating requests accept an {@code Idempotency-Key}.
 * Stock records are reversed through the finance API like any other record.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/stock")
public class StockController {
    private static final String KEY = "Idempotency-Key";
    private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
    private final ItemService items;
    private final StockRecordService records;
    private final StockReportService reports;

    private final StockExports exports;
    private final ExportService export;

    public StockController(ItemService items, StockRecordService records, StockReportService reports, StockExports exports, ExportService export) {
        this.exports = exports;
        this.export = export;
        this.items = items;
        this.records = records;
        this.reports = reports;
    }

    // ---- Units, items, locations ----

    @GetMapping("/units")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Unit> units(TenantContext ctx) { return items.units(); }

    @GetMapping("/items")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Item> items(TenantContext ctx, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return items.items(ctx, includeInactive);
    }

    @PostMapping("/items")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Item createItem(TenantContext ctx, @Valid @RequestBody ItemRequest r) { return items.createItem(ctx, r); }

    @GetMapping("/items/{itemId}")
    @RequiresPermission(Permission.INVENTORY_READ)
    public Item item(TenantContext ctx, @PathVariable long itemId) { return items.item(ctx, itemId); }

    @PatchMapping("/items/{itemId}")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    public Item updateItem(TenantContext ctx, @PathVariable long itemId, @Valid @RequestBody ItemPatch r) { return items.updateItem(ctx, itemId, r); }

    @PostMapping("/items/{itemId}/units")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Item addPackUnit(TenantContext ctx, @PathVariable long itemId, @Valid @RequestBody PackUnitRequest r) {
        return items.addPackUnit(ctx, itemId, r);
    }

    @DeleteMapping("/items/{itemId}/units/{unitId}")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    public Item removePackUnit(TenantContext ctx, @PathVariable long itemId, @PathVariable long unitId) {
        return items.removePackUnit(ctx, itemId, unitId);
    }

    @GetMapping("/items/{itemId}/lots")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Lot> lots(TenantContext ctx, @PathVariable long itemId) { return items.lots(ctx, itemId); }

    @GetMapping("/items/{itemId}/movements")
    @RequiresPermission(Permission.INVENTORY_READ)
    public PageResponse<MovementRow> movements(TenantContext ctx, @PathVariable long itemId, @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        return reports.movements(ctx, itemId, page, size);
    }

    @GetMapping("/items/{itemId}/movements.csv")
    @RequiresPermission(Permission.INVENTORY_READ)
    public ResponseEntity<String> movementsCsv(TenantContext ctx, @PathVariable long itemId) {
        return csv("stock-movements-" + itemId + ".csv", reports.movementsCsv(ctx, itemId));
    }

    @GetMapping("/locations")
    @RequiresPermission(Permission.INVENTORY_READ)
    public List<Location> locations(TenantContext ctx) { return items.locations(ctx); }

    @PostMapping("/locations")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Location createLocation(TenantContext ctx, @Valid @RequestBody LocationRequest r) { return items.createLocation(ctx, r); }

    @PatchMapping("/locations/{locationId}")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    public Location updateLocation(TenantContext ctx, @PathVariable long locationId, @Valid @RequestBody LocationPatch r) {
        return items.updateLocation(ctx, locationId, r);
    }

    // ---- Stock records ----

    @PostMapping("/issues")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult issue(TenantContext ctx, @Valid @RequestBody IssueRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return records.issue(ctx, r, key);
    }

    @PostMapping("/transfers")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult transfer(TenantContext ctx, @Valid @RequestBody TransferRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return records.transfer(ctx, r, key);
    }

    @PostMapping("/wastage")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult wastage(TenantContext ctx, @Valid @RequestBody WastageRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return records.wastage(ctx, r, key);
    }

    @PostMapping("/counts")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult count(TenantContext ctx, @Valid @RequestBody CountRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return records.count(ctx, r, key);
    }

    @PostMapping("/produce")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult produce(TenantContext ctx, @Valid @RequestBody ProduceRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return records.produce(ctx, r, key);
    }

    /** Returns change what parties owe, so they need finance permission. */
    @PostMapping("/purchase-returns")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult purchaseReturn(TenantContext ctx, @Valid @RequestBody PurchaseReturnRequest r,
                                            @RequestHeader(value = KEY, required = false) String key) {
        return records.purchaseReturn(ctx, r, key);
    }

    @PostMapping("/sale-returns")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockRecordResult saleReturn(TenantContext ctx, @Valid @RequestBody SaleReturnRequest r,
                                        @RequestHeader(value = KEY, required = false) String key) {
        return records.saleReturn(ctx, r, key);
    }

    // ---- Reports ----

    @GetMapping("/reports/on-hand")
    @RequiresPermission(Permission.INVENTORY_READ)
    public ResponseEntity<?> onHand(TenantContext ctx, @RequestParam(required = false) Long locationId,
                                    @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        OnHand o = reports.onHand(ctx, locationId);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(o);
        return export.export(ctx, exports.onHand(ReportLabels.language(lang), o), format, lang, "stock-on-hand", o.asOf(), o.asOf());
    }

    @GetMapping("/reports/on-hand.csv")
    @RequiresPermission(Permission.INVENTORY_READ)
    public ResponseEntity<String> onHandCsv(TenantContext ctx) { return csv("stock-on-hand.csv", reports.onHandCsv(ctx)); }

    @GetMapping("/reports/valuation")
    @RequiresPermission(Permission.INVENTORY_READ)
    public ResponseEntity<?> valuation(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                       @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        Valuation v = reports.valuation(ctx, asOf);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(v);
        return export.export(ctx, exports.valuation(ReportLabels.language(lang), v), format, lang, "stock-valuation", asOf, asOf);
    }

    @GetMapping("/reports/valuation.csv")
    @RequiresPermission(Permission.INVENTORY_READ)
    public ResponseEntity<String> valuationCsv(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return csv("stock-value-" + asOf + ".csv", reports.valuationCsv(ctx, asOf));
    }

    @GetMapping("/reports/reconciliation")
    @RequiresPermission(Permission.INVENTORY_READ)
    public Reconciliation reconciliation(TenantContext ctx) { return reports.reconciliation(ctx); }

    @GetMapping("/reports/alerts")
    @RequiresPermission(Permission.INVENTORY_READ)
    public Alerts alerts(TenantContext ctx) { return reports.alerts(ctx); }

    private static ResponseEntity<String> csv(String filename, String body) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body("﻿" + body);
    }
}
