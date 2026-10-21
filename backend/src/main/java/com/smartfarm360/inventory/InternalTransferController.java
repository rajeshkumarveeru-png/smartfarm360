package com.smartfarm360.inventory;

import com.smartfarm360.inventory.InternalTransferService.Preview;
import com.smartfarm360.inventory.InternalTransferService.Transfer;
import com.smartfarm360.inventory.InternalTransferService.TransferRequest;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Internal transfers of own produce between activities (organic resources design, L4, L12). A stock feature for every
 * farm business; reversed through the finance API like other records.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/stock/internal-transfers")
public class InternalTransferController {
    private static final String KEY = "Idempotency-Key";
    private static final String ISO = "yyyy-MM-dd";
    private final InternalTransferService transfers;
    private final ReportLabels labels;
    private final ExportService export;

    public InternalTransferController(InternalTransferService transfers, ReportLabels labels, ExportService export) {
        this.transfers = transfers;
        this.labels = labels;
        this.export = export;
    }

    @PostMapping("/preview")
    @RequiresPermission(Permission.INVENTORY_WRITE)
    public Preview preview(TenantContext ctx, @Valid @RequestBody TransferRequest r) { return transfers.preview(ctx, r); }

    @PostMapping
    @RequiresPermission(Permission.INVENTORY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Transfer transfer(TenantContext ctx, @Valid @RequestBody TransferRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return transfers.transfer(ctx, r, key);
    }

    @GetMapping
    @RequiresPermission(Permission.INVENTORY_READ)
    public ResponseEntity<?> list(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to,
                                  @RequestParam(required = false) Long costObjectId, @RequestParam(required = false) String format,
                                  @RequestParam(required = false) String lang) {
        List<Transfer> list = transfers.transfers(ctx, from, to, costObjectId);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        String l = ReportLabels.language(lang);
        ReportDocument d = new ReportDocument(labels.t(l, "stock.transfer.report"));
        d.meta(labels.t(l, "reports.export.period"), from + " – " + to);
        d.column(labels.t(l, "fin.date"), ReportDocument.Kind.DATE).text(labels.t(l, "stock.transfer.number")).text(labels.t(l, "stock.item"))
                .column(labels.t(l, "stock.quantity"), ReportDocument.Kind.NUMBER).text(labels.t(l, "stock.transfer.unit"))
                .text(labels.t(l, "stock.transfer.from")).text(labels.t(l, "stock.transfer.to")).text(labels.t(l, "stock.transfer.method"))
                .money(labels.t(l, "stock.transfer.costMoved"));
        for (Transfer t : list) {
            if (t.reversed()) continue;
            d.row(t.date(), t.number(), t.itemName(), t.baseQuantity(), t.baseUnit(), t.fromName(), t.toName(), labels.t(l, "stock.transfer.methodName." + t.method()),
                    t.costMoved() == null ? BigDecimal.ZERO : t.costMoved());
        }
        return export.export(ctx, d, format, lang, "internal-transfers", from, to);
    }
}
