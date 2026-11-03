package com.smartfarm360.workforce;

import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.workforce.WorkforceDtos.LabourRow;
import com.smartfarm360.workforce.WorkforceDtos.WageRow;
import com.smartfarm360.workforce.WorkforceDtos.WagesReport;
import org.springframework.stereotype.Component;

/** The wages report laid out for CSV and XLSX export (dashboard and reports design, section 5). */
@Component
public class WorkforceExports {
    private final ReportLabels labels;

    public WorkforceExports(ReportLabels labels) { this.labels = labels; }

    public ReportDocument wages(String lang, WagesReport r) {
        ReportDocument d = new ReportDocument(labels.t(lang, "reports.title.wages"));
        d.meta(labels.t(lang, "reports.export.period"), r.from() + " – " + r.to());
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        if (!r.amountsShown()) d.meta(labels.t(lang, "reports.export.note"), labels.t(lang, "reports.amountsHidden"));
        d.text(labels.t(lang, "workforce.worker")).text(labels.t(lang, "workforce.payTypeLabel"))
                .column(labels.t(lang, "workforce.days"), ReportDocument.Kind.NUMBER);
        if (r.amountsShown()) {
            d.money(labels.t(lang, "workforce.wages")).money(labels.t(lang, "workforce.paidOut")).money(labels.t(lang, "workforce.advanceGiven"))
                    .money(labels.t(lang, "workforce.advanceRecovered")).money(labels.t(lang, "reports.owedAtEnd")).money(labels.t(lang, "reports.advanceAtEnd"));
        }
        for (WageRow w : r.workers()) {
            String pay = labels.t(lang, "workforce.payType." + w.payType());
            if (r.amountsShown()) d.row(w.name(), pay, w.days(), w.wages(), w.paid(), w.advanceGiven(), w.advanceRecovered(), w.owedAtEnd(), w.advanceAtEnd());
            else d.row(w.name(), pay, w.days());
        }
        WageRow t = r.total();
        if (r.amountsShown()) d.totalRow(labels.t(lang, "workforce.total"), null, t.days(), t.wages(), t.paid(), t.advanceGiven(), t.advanceRecovered(),
                t.owedAtEnd(), t.advanceAtEnd());
        else d.totalRow(labels.t(lang, "workforce.total"), null, t.days());

        d.section(labels.t(lang, "workforce.labourByActivity"));
        for (LabourRow l : r.byCrop().rows()) {
            String name = "OVERHEAD".equals(l.kind()) ? labels.t(lang, "fin.overhead") : l.name();
            if (r.amountsShown()) d.row(name, null, l.days(), l.cost());
            else d.row(name, null, l.days());
        }
        return d;
    }
}
