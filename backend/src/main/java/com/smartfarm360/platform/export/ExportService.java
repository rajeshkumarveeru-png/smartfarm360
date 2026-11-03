package com.smartfarm360.platform.export;

import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.ratelimit.RateLimiter;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a {@link ReportDocument} as CSV or XLSX (dashboard and reports design, section 5): every file starts with the
 * report header (principle 2), money stays exact, exports are rate limited and recorded in the activity history (H9).
 */
@Service
public class ExportService {
    public static final int MAX_EXPORTS_PER_MINUTE = 30;
    /** H9: an export covers at most five years. */
    public static final long MAX_RANGE_DAYS = 5 * 366;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final RateLimiter limiter;
    private final ReportLabels labels;
    private final Clock clock;

    public ExportService(JdbcClient jdbc, AuditService audit, RateLimiter limiter, ReportLabels labels) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.limiter = limiter;
        this.labels = labels;
        this.clock = Clock.systemUTC();
    }

    /** True when the request asks for a file rather than JSON. */
    public static boolean isExport(String format) { return format != null && !format.isBlank(); }

    /**
     * Writes the report. {@code code} names the report in the file name and the activity history; {@code from}/{@code to}
     * is its range (both equal for an "as of" report).
     */
    public ResponseEntity<byte[]> export(TenantContext ctx, ReportDocument doc, String format, String lang, String code, LocalDate from, LocalDate to) {
        String fmt = format.trim().toLowerCase();
        if (!fmt.equals("csv") && !fmt.equals("xlsx")) throw ApiException.badRequest("INVALID_EXPORT_FORMAT", "Choose CSV or Excel.");
        if (from != null && to != null && ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("EXPORT_RANGE_TOO_LONG", "An export can cover at most five years.");
        }
        limiter.check("report-export:" + ctx.userId(), MAX_EXPORTS_PER_MINUTE, Duration.ofMinutes(1));
        String language = ReportLabels.language(lang);
        Map<String, String> header = header(ctx, doc, language);
        byte[] body = fmt.equals("csv") ? csv(doc, header) : xlsx(doc, header);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("report", code);
        details.put("format", fmt);
        if (from != null) details.put("from", from.toString());
        if (to != null) details.put("to", to.toString());
        audit.record(ctx.tenantId(), ctx.userId(), "REPORT_EXPORTED", "REPORT", code, details);

        String name = (code + (from == null ? "" : "-" + from) + (to == null || to.equals(from) ? "" : "-" + to)).replaceAll("[^A-Za-z0-9._-]", "-") + "." + fmt;
        return ResponseEntity.ok()
                .contentType(fmt.equals("csv") ? CSV : XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private Map<String, String> header(TenantContext ctx, ReportDocument doc, String lang) {
        Map<String, String> h = new LinkedHashMap<>();
        String tenant = jdbc.sql("select name from sf_tenants where id = :t").param("t", ctx.tenantId()).query(String.class).single();
        h.put(labels.t(lang, "reports.export.farmBusiness"), tenant);
        for (ReportDocument.Meta m : doc.meta()) h.put(m.label(), m.value());
        h.put(labels.t(lang, "reports.export.generated"),
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return h;
    }

    // ---- CSV -----------------------------------------------------------------------------------------------------

    static byte[] csv(ReportDocument doc, Map<String, String> header) {
        StringBuilder out = new StringBuilder("﻿");   // byte-order mark: spreadsheet programs then read Tamil as UTF-8
        out.append(CsvWriter.row(doc.title()));
        header.forEach((k, v) -> out.append(CsvWriter.row(k, v)));
        out.append("\r\n");
        out.append(CsvWriter.row(doc.columns().stream().map(ReportDocument.Column::label).toArray(String[]::new)));
        for (ReportDocument.Row row : doc.rows()) {
            String[] cells = new String[doc.columns().size()];
            for (int i = 0; i < cells.length; i++) cells[i] = i < row.cells().size() ? plain(row.cells().get(i)) : "";
            out.append(CsvWriter.row(cells));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Plain digits with a dot, never grouped, so spreadsheets read numbers as numbers. */
    private static String plain(Object cell) {
        if (cell == null) return "";
        if (cell instanceof BigDecimal b) return b.toPlainString();
        return cell.toString();
    }

    // ---- XLSX ----------------------------------------------------------------------------------------------------

    static byte[] xlsx(ReportDocument doc, Map<String, String> header) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.setCompressTempFiles(true);
            SXSSFSheet sheet = wb.createSheet(WorkbookUtil.createSafeSheetName(doc.title()));
            DataFormat formats = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(formats.getFormat("#,##0.00"));
            CellStyle moneyBold = wb.createCellStyle();
            moneyBold.cloneStyleFrom(money);
            moneyBold.setFont(bold);
            CellStyle number = wb.createCellStyle();
            number.setDataFormat(formats.getFormat("#,##0.####"));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(formats.getFormat("dd-mm-yyyy"));

            int r = 0;
            Cell title = sheet.createRow(r++).createCell(0);
            title.setCellValue(doc.title());
            title.setCellStyle(boldStyle);
            for (Map.Entry<String, String> e : header.entrySet()) {
                Row row = sheet.createRow(r++);
                Cell label = row.createCell(0);
                label.setCellValue(e.getKey());
                label.setCellStyle(boldStyle);
                row.createCell(1).setCellValue(e.getValue());
            }
            r++;
            Row head = sheet.createRow(r++);
            for (int i = 0; i < doc.columns().size(); i++) {
                Cell c = head.createCell(i);
                c.setCellValue(doc.columns().get(i).label());
                c.setCellStyle(boldStyle);
            }
            sheet.createFreezePane(0, r);
            for (ReportDocument.Row data : doc.rows()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < doc.columns().size() && i < data.cells().size(); i++) {
                    Object v = data.cells().get(i);
                    if (v == null) continue;
                    Cell c = row.createCell(i);
                    ReportDocument.Kind kind = doc.columns().get(i).kind();
                    if (v instanceof BigDecimal b) {
                        c.setCellValue(b.doubleValue());   // a spreadsheet number cell; the exact value is also in the CSV and JSON
                        c.setCellStyle(kind == ReportDocument.Kind.NUMBER ? number : data.total() ? moneyBold : money);
                    } else if (v instanceof LocalDate d) {
                        c.setCellValue(d);
                        c.setCellStyle(date);
                    } else {
                        c.setCellValue(v.toString());
                        if (data.total()) c.setCellStyle(boldStyle);
                    }
                }
            }
            sheet.trackAllColumnsForAutoSizing();
            for (int i = 0; i < doc.columns().size(); i++) sheet.autoSizeColumn(i);
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the Excel file", e);
        }
    }
}
