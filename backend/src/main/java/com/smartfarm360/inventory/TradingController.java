package com.smartfarm360.inventory;

import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The trading margin report (processing and trading design, M10): for goods bought for resale, what was bought, sold,
 * the cost of goods sold at moving-average cost and the gross margin. Uses only existing records, so it needs no module.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/stock/reports/trading")
public class TradingController {
    private static final String ISO = "yyyy-MM-dd";
    private final TradingReportService trading;
    private final ReportLabels labels;
    private final ExportService export;

    public TradingController(TradingReportService trading, ReportLabels labels, ExportService export) {
        this.trading = trading;
        this.labels = labels;
        this.export = export;
    }

    public record TradingRow(long itemId, String itemName, String baseUnit, BigDecimal boughtQuantity, BigDecimal boughtValue, BigDecimal soldQuantity,
                             BigDecimal revenue, BigDecimal costOfSales, BigDecimal margin, BigDecimal marginPercent, BigDecimal stockQuantity,
                             BigDecimal stockValue) {}

    @GetMapping
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> trading(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                     @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to, @RequestParam(required = false) String format,
                                     @RequestParam(required = false) String lang) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 3700) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid date range.");
        List<TradingRow> rows = trading.rows(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(rows);
        String l = ReportLabels.language(lang);
        ReportDocument d = new ReportDocument(labels.t(l, "stock.trading.title"));
        d.meta(labels.t(l, "reports.export.period"), from + " – " + to);
        d.meta(labels.t(l, "reports.export.figures"), labels.t(l, "reports.export.actualPosted"));
        d.text(labels.t(l, "stock.item")).text(labels.t(l, "stock.transfer.unit"))
                .column(labels.t(l, "stock.trading.bought"), ReportDocument.Kind.NUMBER).money(labels.t(l, "stock.trading.boughtValue"))
                .column(labels.t(l, "stock.trading.sold"), ReportDocument.Kind.NUMBER).money(labels.t(l, "fin.revenue"))
                .money(labels.t(l, "stock.trading.costOfSales")).money(labels.t(l, "stock.trading.margin"))
                .column(labels.t(l, "stock.trading.marginPercent"), ReportDocument.Kind.NUMBER)
                .column(labels.t(l, "stock.trading.inStock"), ReportDocument.Kind.NUMBER).money(labels.t(l, "stock.trading.stockValue"));
        for (TradingRow r : rows) {
            d.row(r.itemName(), r.baseUnit(), r.boughtQuantity(), r.boughtValue(), r.soldQuantity(), r.revenue(), r.costOfSales(), r.margin(), r.marginPercent(),
                    r.stockQuantity(), r.stockValue());
        }
        return export.export(ctx, d, format, lang, "trading-margin", from, to);
    }
}
