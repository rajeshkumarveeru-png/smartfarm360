package com.smartfarm360.operations;

import com.smartfarm360.finance.ReportService.AmountRow;
import com.smartfarm360.operations.OperationsDtos.Comparison;
import com.smartfarm360.operations.OperationsDtos.ComparisonRow;
import com.smartfarm360.operations.OperationsDtos.CycleReport;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Crop reports laid out for CSV and XLSX export (dashboard and reports design, section 5). Estimates stay labelled (H12). */
@Component
public class CropExports {
    private final ReportLabels labels;

    public CropExports(ReportLabels labels) { this.labels = labels; }

    private String line(String lang, AmountRow r) {
        if (r.categoryCode() != null) return labels.t(lang, "financeCategory." + r.categoryCode());
        if (r.categoryName() != null) return r.categoryName();
        return labels.t(lang, "fin.ledger." + r.accountCode());
    }

    public ReportDocument cycle(String lang, CycleReport c) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.cropCycle"));
        d.meta(labels.t(lang, "reports.cycle"), c.cycleName() + " · " + c.cropName());
        d.meta(labels.t(lang, "reports.export.period"), c.from() + " – " + c.to());
        d.meta(labels.t(lang, "fin.basis"), labels.t(lang, "CASH".equals(c.basis()) ? "fin.cashBasis" : "fin.accrual"));
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        d.text(labels.t(lang, "fin.description")).money(labels.t(lang, "fin.amount"));
        section(d, lang, "fin.revenue", c.revenue(), c.revenueTotal());
        if (c.otherIncome().signum() != 0) d.row(labels.t(lang, "fin.otherIncome"), c.otherIncome());
        section(d, lang, "reports.directCosts", c.directCosts(), c.directCostTotal());
        section(d, lang, "reports.allocatedOverhead", c.allocatedOverhead(), c.allocatedTotal());
        if (c.interest().signum() != 0) d.row(labels.t(lang, "fin.interest"), c.interest());
        if (c.losses().signum() != 0) d.row(labels.t(lang, "fin.losses"), c.losses());
        d.totalRow(labels.t(lang, "fin.totalCosts"), c.totalCost());
        d.totalRow(labels.t(lang, "reports.profit"), c.profit());
        if (c.costPerAcre() != null) d.row(labels.t(lang, "reports.costPerAcre"), c.costPerAcre());
        if (c.estimateValue() != null) d.row(labels.t(lang, "reports.unsoldEstimate"), c.estimateValue());
        return d;
    }

    private void section(ReportDocument d, String lang, String key, List<AmountRow> rows, BigDecimal total) {
        d.section(labels.t(lang, key));
        rows.forEach(r -> d.row(line(lang, r), r.amount()));
        d.totalRow(labels.t(lang, "fin.total"), total);
    }

    public ReportDocument comparison(String lang, Comparison c) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.cropComparison"));
        d.meta(labels.t(lang, "crop.crop"), c.cropName());
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        d.text(labels.t(lang, "reports.cycle")).text(labels.t(lang, "reports.season")).column(labels.t(lang, "reports.acres"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "reports.sowingDate"), ReportDocument.Kind.DATE).column(labels.t(lang, "reports.harvested"), ReportDocument.Kind.NUMBER)
                .text(labels.t(lang, "stock.unitLabel")).column(labels.t(lang, "crop.yieldPerAcre"), ReportDocument.Kind.NUMBER)
                .money(labels.t(lang, "fin.revenue")).money(labels.t(lang, "fin.totalCosts")).money(labels.t(lang, "reports.costPerAcre"))
                .money(labels.t(lang, "reports.profit"));
        for (ComparisonRow r : c.cycles()) {
            d.row(r.cycleName(), r.seasonName(), r.areaAcres(), r.actualSowingDate() != null ? r.actualSowingDate() : r.plannedSowingDate(), r.harvested(),
                    r.unit() == null ? null : labels.t(lang, "stock.unit." + r.unit()), r.yieldPerAcre(), r.revenue(), r.totalCost(), r.costPerAcre(), r.profit());
        }
        return d;
    }
}
