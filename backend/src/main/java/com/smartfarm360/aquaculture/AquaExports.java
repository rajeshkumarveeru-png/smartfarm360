package com.smartfarm360.aquaculture;

import com.smartfarm360.aquaculture.AquaDtos.*;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Aquaculture reports laid out for CSV and XLSX export (aquaculture design, K14; reports design, H7). */
@Component
public class AquaExports {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ReportLabels labels;

    public AquaExports(ReportLabels labels) { this.labels = labels; }

    public ReportDocument cycles(String lang, LocalDate from, LocalDate to, List<CycleReport> list) {
        ReportDocument d = new ReportDocument(labels.t(lang, "aquaculture.report.cycles"));
        d.meta(labels.t(lang, "reports.export.period"), from + " – " + to);
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
        d.text(labels.t(lang, "aquaculture.pond")).text(labels.t(lang, "aquaculture.cycle")).text(labels.t(lang, "aquaculture.statusLabel"))
                .column(labels.t(lang, "aquaculture.daysOfCulture"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.stocked"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "aquaculture.deathsSeen"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.harvestedKg"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "aquaculture.feedKg"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.survival"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "aquaculture.fcr"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.yieldPerAcre"), ReportDocument.Kind.NUMBER)
                .money(labels.t(lang, "fin.revenue")).money(labels.t(lang, "fin.costs")).money(labels.t(lang, "reports.profit"))
                .money(labels.t(lang, "aquaculture.costPerKg")).money(labels.t(lang, "aquaculture.profitPerAcre"));
        for (CycleReport r : list) {
            Figures f = r.period();
            d.row(r.cycle().pondName(), r.cycle().name(), labels.t(lang, "aquaculture.status." + r.cycle().status()),
                    f.daysOfCulture() == null ? null : BigDecimal.valueOf(f.daysOfCulture()), BigDecimal.valueOf(f.stockedCount()), BigDecimal.valueOf(f.deaths()),
                    f.harvestedKg(), f.feedKg(), f.survivalPercent(), f.fcr(), f.yieldPerAcre(), f.revenue(), f.costs(), f.profit(), f.costPerKg(), f.profitPerAcre());
        }
        return d;
    }

    public ReportDocument water(String lang, LocalDate from, LocalDate to, List<Reading> readings) {
        ReportDocument d = new ReportDocument(labels.t(lang, "aquaculture.report.water"));
        d.meta(labels.t(lang, "reports.export.period"), from + " – " + to);
        d.text(labels.t(lang, "aquaculture.pond")).text(labels.t(lang, "aquaculture.takenAt"))
                .column(labels.t(lang, "aquaculture.parameter.DISSOLVED_OXYGEN"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.parameter.PH"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.parameter.TEMPERATURE"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.parameter.AMMONIA"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.parameter.TRANSPARENCY"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "aquaculture.parameter.SALINITY"), ReportDocument.Kind.NUMBER)
                .text(labels.t(lang, "aquaculture.outOfLimits")).text(labels.t(lang, "aquaculture.notes"));
        for (Reading r : readings) {
            String flags = String.join(", ", r.outOfLimits().stream().map(p -> labels.t(lang, "aquaculture.parameter." + p)).toList());
            d.row(r.pondName(), r.takenAt().atZoneSameInstant(WaterService.FARM_ZONE).format(TIME), r.dissolvedOxygen(), r.ph(), r.temperature(), r.ammonia(),
                    r.transparency(), r.salinity(), flags, r.notes());
        }
        return d;
    }
}
