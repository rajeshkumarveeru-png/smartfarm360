package com.smartfarm360.assets;

import com.smartfarm360.assets.AssetDtos.Asset;
import com.smartfarm360.assets.AssetDtos.RunningCostRow;
import com.smartfarm360.assets.AssetDtos.RunningCostsReport;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Asset reports laid out for CSV and XLSX export (dashboard and reports design, section 5). */
@Component
public class AssetExports {
    private final ReportLabels labels;

    public AssetExports(ReportLabels labels) { this.labels = labels; }

    public ReportDocument runningCosts(String lang, RunningCostsReport r) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.runningCosts"));
        d.meta(labels.t(lang, "reports.export.period"), r.from() + " – " + r.to());
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        if (!r.amountsShown()) d.meta(labels.t(lang, "reports.export.note"), labels.t(lang, "reports.amountsHidden"));
        d.text(labels.t(lang, "assets.name")).text(labels.t(lang, "assets.kindLabel")).text(labels.t(lang, "assets.statusLabel"));
        if (r.amountsShown()) d.money(labels.t(lang, "assets.depreciation")).money(labels.t(lang, "assets.runningCosts")).money(labels.t(lang, "assets.totalCost"));
        d.column(labels.t(lang, "assets.hours"), ReportDocument.Kind.NUMBER);
        if (r.amountsShown()) d.money(labels.t(lang, "reports.costPerHour"));
        d.column(labels.t(lang, "reports.maintenanceDone"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "reports.plansDue"), ReportDocument.Kind.NUMBER);
        for (RunningCostRow a : r.assets()) {
            String kind = labels.t(lang, "assets.kind." + a.kind()), status = labels.t(lang, "assets.status." + a.status());
            java.math.BigDecimal maint = java.math.BigDecimal.valueOf(a.maintenanceDone()), due = java.math.BigDecimal.valueOf(a.plansDue());
            if (r.amountsShown()) d.row(a.name(), kind, status, a.depreciation(), a.runningCosts(), a.totalCost(), a.hours(), a.costPerHour(), maint, due);
            else d.row(a.name(), kind, status, a.hours(), maint, due);
        }
        if (r.amountsShown()) d.totalRow(labels.t(lang, "assets.total"), null, null, r.depreciation(), r.runningCosts(), r.totalCost());
        return d;
    }

    public ReportDocument register(String lang, List<Asset> assets) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.assetRegister"));
        d.meta(labels.t(lang, "reports.export.asOf"), LocalDate.now().toString());
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        d.text(labels.t(lang, "assets.name")).text(labels.t(lang, "assets.kindLabel")).text(labels.t(lang, "assets.serialNo"))
                .text(labels.t(lang, "assets.statusLabel")).column(labels.t(lang, "assets.inUseFrom"), ReportDocument.Kind.DATE)
                .money(labels.t(lang, "assets.cost")).money(labels.t(lang, "assets.salvageValue"))
                .column(labels.t(lang, "assets.usefulLifeMonths"), ReportDocument.Kind.NUMBER)
                .money(labels.t(lang, "assets.accumulated")).money(labels.t(lang, "assets.bookValue")).money(labels.t(lang, "assets.perMonth"))
                .text(labels.t(lang, "assets.source"));
        for (Asset a : assets) {
            d.row(a.name(), labels.t(lang, "assets.kind." + a.kind()), a.serialNo(), labels.t(lang, "assets.status." + a.status()), a.inUseFrom(),
                    a.cost(), a.salvageValue(), a.usefulLifeMonths() == null ? null : java.math.BigDecimal.valueOf(a.usefulLifeMonths()),
                    a.accumulated(), a.bookValue(), a.monthlyDepreciation(), a.sourceNumber());
        }
        return d;
    }
}
