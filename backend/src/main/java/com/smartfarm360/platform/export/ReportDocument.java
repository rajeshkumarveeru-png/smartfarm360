package com.smartfarm360.platform.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A report as exported (dashboard and reports design, section 5): a title, the header block every report carries
 * (principle 2), columns and rows. Cells are {@link String}, {@link BigDecimal} (money or quantity), {@link LocalDate}
 * or null. Labels are already in the reader's language.
 */
public final class ReportDocument {
    public enum Kind { TEXT, MONEY, NUMBER, DATE }

    public record Column(String label, Kind kind) {}

    public record Meta(String label, String value) {}

    public record Row(List<Object> cells, boolean total) {}

    private final String title;
    private final List<Meta> meta = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    public ReportDocument(String title) { this.title = title; }

    public ReportDocument meta(String label, String value) { meta.add(new Meta(label, value)); return this; }

    public ReportDocument column(String label, Kind kind) { columns.add(new Column(label, kind)); return this; }

    public ReportDocument text(String label) { return column(label, Kind.TEXT); }

    public ReportDocument money(String label) { return column(label, Kind.MONEY); }

    public ReportDocument row(Object... cells) { rows.add(new Row(Arrays.asList(cells), false)); return this; }

    public ReportDocument totalRow(Object... cells) { rows.add(new Row(Arrays.asList(cells), true)); return this; }

    /** A heading row inside the table, such as "Operating costs". */
    public ReportDocument section(String label) {
        Object[] cells = new Object[Math.max(1, columns.size())];
        cells[0] = label;
        rows.add(new Row(Arrays.asList(cells), true));
        return this;
    }

    public String title() { return title; }
    public List<Meta> meta() { return meta; }
    public List<Column> columns() { return columns; }
    public List<Row> rows() { return rows; }
}
