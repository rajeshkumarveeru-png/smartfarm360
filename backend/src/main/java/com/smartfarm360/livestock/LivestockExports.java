package com.smartfarm360.livestock;

import com.smartfarm360.livestock.LivestockDtos.*;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Livestock reports laid out for CSV and XLSX export (livestock design, section 8; reports design, H7). */
@Component
public class LivestockExports {
    private final ReportLabels labels;

    public LivestockExports(ReportLabels labels) { this.labels = labels; }

    private void period(ReportDocument d, String lang, LocalDate from, LocalDate to) {
        d.meta(labels.t(lang, "reports.export.period"), from + " – " + to);
        d.meta(labels.t(lang, "reports.export.figures"), labels.t(lang, "reports.export.actualPosted"));
    }

    public ReportDocument herdRegister(String lang, List<Animal> animals) {
        ReportDocument d = new ReportDocument(labels.t(lang, "livestock.report.register"));
        d.meta(labels.t(lang, "reports.export.asOf"), LocalDate.now().toString());
        d.text(labels.t(lang, "livestock.tag")).text(labels.t(lang, "livestock.name")).text(labels.t(lang, "livestock.speciesLabel"))
                .text(labels.t(lang, "livestock.breed")).text(labels.t(lang, "livestock.sexLabel")).column(labels.t(lang, "livestock.birthDate"), ReportDocument.Kind.DATE)
                .text(labels.t(lang, "livestock.originLabel")).text(labels.t(lang, "livestock.group")).text(labels.t(lang, "livestock.statusLabel"))
                .column(labels.t(lang, "livestock.lactation"), ReportDocument.Kind.NUMBER).text(labels.t(lang, "livestock.reproLabel"))
                .column(labels.t(lang, "livestock.expectedCalving"), ReportDocument.Kind.DATE).money(labels.t(lang, "assets.bookValue"));
        for (Animal a : animals) {
            Breeding b = a.breeding();
            d.row(a.tag(), a.name(), labels.t(lang, "livestock.species." + a.species()), a.breed(), labels.t(lang, "livestock.sex." + a.sex()), a.birthDate(),
                    labels.t(lang, "livestock.origin." + a.origin()), a.groupName(), labels.t(lang, "livestock.status." + a.status()),
                    b.lactation() == null ? null : BigDecimal.valueOf(b.lactation()), b.repro() == null ? null : labels.t(lang, "livestock.repro." + b.repro()),
                    b.expectedCalving(), a.bookValue());
        }
        return d;
    }

    public ReportDocument milk(String lang, MilkReport r) {
        ReportDocument d = new ReportDocument(labels.t(lang, "livestock.report.milk"));
        period(d, lang, r.from(), r.to());
        d.meta(labels.t(lang, "livestock.produced"), r.produced().toPlainString());
        d.meta(labels.t(lang, "livestock.sold"), r.sold().toPlainString());
        d.meta(labels.t(lang, "livestock.homeUse"), r.homeUse().toPlainString());
        d.meta(labels.t(lang, "livestock.fedToAnimals"), r.fedToAnimals().toPlainString());
        d.meta(labels.t(lang, "livestock.discarded"), r.discarded().toPlainString());
        d.column(labels.t(lang, "fin.date"), ReportDocument.Kind.DATE).text(labels.t(lang, "livestock.group"))
                .column(labels.t(lang, "livestock.session.MORNING"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "livestock.session.EVENING"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "livestock.litres"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "livestock.fat"), ReportDocument.Kind.NUMBER)
                .column(labels.t(lang, "livestock.snf"), ReportDocument.Kind.NUMBER);
        for (MilkRow m : r.days()) d.row(m.date(), m.groupName(), m.morning(), m.evening(), m.total(), m.fat(), m.snf());
        d.totalRow(labels.t(lang, "fin.total"), null, null, null, r.produced(), r.fat(), r.snf());
        return d;
    }

    public ReportDocument herd(String lang, HerdReport r) {
        ReportDocument d = new ReportDocument(labels.t(lang, "livestock.report.herd"));
        period(d, lang, r.from(), r.to());
        d.text(labels.t(lang, "livestock.group")).money(labels.t(lang, "fin.revenue")).money(labels.t(lang, "fin.costs")).money(labels.t(lang, "reports.profit"))
                .column(labels.t(lang, "livestock.produced"), ReportDocument.Kind.NUMBER).column(labels.t(lang, "livestock.sold"), ReportDocument.Kind.NUMBER)
                .money(labels.t(lang, "livestock.costPerLitre")).money(labels.t(lang, "livestock.pricePerLitre"));
        for (HerdRow h : r.groups()) {
            d.row(h.groupName(), h.revenue(), h.costTotal(), h.profit(), h.litresProduced(), h.litresSold(), h.costPerLitre(), h.pricePerLitre());
        }
        return d;
    }

    public ReportDocument breeding(String lang, BreedingCalendar c) {
        ReportDocument d = new ReportDocument(labels.t(lang, "livestock.report.breeding"));
        d.meta(labels.t(lang, "reports.export.period"), c.from() + " – " + c.to());
        d.text(labels.t(lang, "livestock.dueLabel")).text(labels.t(lang, "livestock.tag")).text(labels.t(lang, "livestock.name"))
                .column(labels.t(lang, "fin.date"), ReportDocument.Kind.DATE);
        for (DueItem i : c.due()) d.row(labels.t(lang, "livestock.due." + i.kind()), i.tag(), i.name(), i.due());
        for (DueItem i : c.openLong()) d.row(labels.t(lang, "livestock.due." + i.kind()), i.tag(), i.name(), i.due());
        return d;
    }

    public ReportDocument health(String lang, HealthReport h) {
        ReportDocument d = new ReportDocument(labels.t(lang, "livestock.report.health"));
        d.meta(labels.t(lang, "reports.export.period"), h.from() + " – " + h.to());
        d.column(labels.t(lang, "fin.date"), ReportDocument.Kind.DATE).text(labels.t(lang, "livestock.kindLabel")).text(labels.t(lang, "livestock.for"))
                .text(labels.t(lang, "livestock.description")).text(labels.t(lang, "livestock.doneBy"))
                .column(labels.t(lang, "livestock.withheldUntil"), ReportDocument.Kind.DATE).column(labels.t(lang, "livestock.nextDue"), ReportDocument.Kind.DATE);
        for (Event e : h.events()) {
            d.row(e.date(), labels.t(lang, "livestock.event." + e.kind()), e.animalTag() != null ? e.animalTag() : e.groupName(), e.description(), e.doneBy(),
                    e.withdrawalUntil(), e.nextDue());
        }
        return d;
    }
}
