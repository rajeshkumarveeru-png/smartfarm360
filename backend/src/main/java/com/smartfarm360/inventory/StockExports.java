package com.smartfarm360.inventory;

import com.smartfarm360.inventory.StockDtos.Item;
import com.smartfarm360.inventory.StockDtos.OnHand;
import com.smartfarm360.inventory.StockDtos.OnHandRow;
import com.smartfarm360.inventory.StockDtos.Valuation;
import com.smartfarm360.inventory.StockDtos.ValuationRow;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.stereotype.Component;

/** Stock reports laid out for CSV and XLSX export (dashboard and reports design, section 5). */
@Component
public class StockExports {
    private final ReportLabels labels;

    public StockExports(ReportLabels labels) { this.labels = labels; }

    private String unit(String lang, String baseUnit, String custom) {
        return custom != null ? custom : labels.t(lang, "stock.unit." + baseUnit);
    }

    public ReportDocument valuation(String lang, Valuation v) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.stockValuation"));
        d.meta(labels.t(lang, "reports.export.asOf"), v.asOf().toString());
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        d.text(labels.t(lang, "stock.item")).text(labels.t(lang, "stock.unitLabel"))
                .column(labels.t(lang, "stock.quantity"), ReportDocument.Kind.NUMBER).money(labels.t(lang, "stock.value"));
        for (ValuationRow r : v.items()) d.row(r.name(), unit(lang, r.baseUnit(), r.customUnitName()), r.quantity(), r.value());
        d.totalRow(labels.t(lang, "fin.total"), null, null, v.totalValue());
        return d;
    }

    public ReportDocument onHand(String lang, OnHand o) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.stockOnHand"));
        d.meta(labels.t(lang, "reports.export.asOf"), o.asOf().toString());
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        d.text(labels.t(lang, "stock.item")).text(labels.t(lang, "stock.unitLabel"))
                .column(labels.t(lang, "stock.quantity"), ReportDocument.Kind.NUMBER).money(labels.t(lang, "stock.unitCost"))
                .money(labels.t(lang, "stock.value")).text(labels.t(lang, "stock.lowStock"));
        for (OnHandRow r : o.items()) {
            Item i = r.item();
            d.row(i.name(), unit(lang, i.baseUnit(), i.customUnitName()), i.quantity(), i.unitCost(), i.value(), i.lowStock() ? labels.t(lang, "stock.lowStock") : null);
        }
        d.totalRow(labels.t(lang, "fin.total"), null, null, null, o.totalValue());
        return d;
    }
}
