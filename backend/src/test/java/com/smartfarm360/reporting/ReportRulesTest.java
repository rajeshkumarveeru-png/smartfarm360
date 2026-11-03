package com.smartfarm360.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import com.smartfarm360.finance.LedgerReportService;
import com.smartfarm360.platform.export.ReportLabels;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dashboard and reports design, sections 3–7: exports, limits, audit, permissions, comparisons and labels. */
class ReportRulesTest extends FinanceTestSupport {
    @Autowired ReportLabels labels;

    private long bank, buyer;

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("rep-" + UUID.randomUUID().toString().substring(0, 6));
        bank = account("Bank", "BANK");
        buyer = party("Selvam");
        post(f("/owner-money"), owner, Map.of("docDate", "2026-04-01", "direction", "IN", "financialAccountId", bank, "amount", 100000))
                .andExpect(status().isCreated());
        invoice("bills", "2026-05-10", null, bank, line("SEEDS", 1250.50, null));
        invoice("sales", "2026-06-15", buyer, null, line("CROP_SALE", 40000, null));
    }

    private byte[] file(String url, User user) throws Exception {
        MvcResult r = get(url, user).andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsByteArray();
    }

    @Test
    void xlsxHasTheHeaderBlockAndRealNumberCells() throws Exception {
        byte[] xlsx = file(f("/reports/balance-sheet?asOf=2026-07-31&format=xlsx&lang=en"), owner);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Balance sheet");
            List<String> header = new ArrayList<>();
            for (int i = 1; i <= 5; i++) header.add(sheet.getRow(i).getCell(0).getStringCellValue());
            assertThat(header).contains("Farm business", "As of", "Figures");
            Cell bankCell = null;
            for (Row row : sheet) {
                Cell first = row.getCell(0);
                if (first != null && first.getCellType() == CellType.STRING && first.getStringCellValue().equals("Bank and UPI accounts")) bankCell = row.getCell(1);
            }
            assertThat(bankCell).isNotNull();
            assertThat(bankCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(BigDecimal.valueOf(bankCell.getNumericCellValue())).isEqualByComparingTo("98749.50");
        }
    }

    @Test
    void csvHasAByteOrderMarkTamilLabelsAndPlainNumbers() throws Exception {
        byte[] csv = file(f("/reports/income-statement?from=2026-04-01&to=2026-07-31&format=csv&lang=ta"), owner);
        assertThat(csv[0] & 0xff).isEqualTo(0xEF);
        assertThat(csv[1] & 0xff).isEqualTo(0xBB);
        assertThat(csv[2] & 0xff).isEqualTo(0xBF);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains(labels.t("ta", "fin.report.income")).contains(labels.t("ta", "reports.export.farmBusiness"));
        assertThat(text).contains("1250.50").contains("40000.00").doesNotContain("40,000");
    }

    @Test
    void exportsAreAuditedRateLimitedAndRangeChecked() throws Exception {
        file(f("/reports/cash-flow?from=2026-04-01&to=2026-07-31&format=csv"), owner);
        Map<String, Object> audit = jdbc.queryForMap("select action, entity_id, details::text as details from sf_audit_history where tenant_id = ? and action = 'REPORT_EXPORTED'"
                + " order by id desc limit 1", tenant);
        assertThat(audit.get("entity_id")).isEqualTo("cash-flow");
        assertThat((String) audit.get("details")).contains("\"format\": \"csv\"").contains("2026-04-01").doesNotContain("98749");

        get(f("/reports/cash-flow?from=2020-01-01&to=2026-07-31&format=xlsx"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_RANGE_TOO_LONG"));
        get(f("/reports/cash-flow?from=2026-04-01&to=2026-07-31&format=pdf"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPORT_FORMAT"));
        // 30 a minute per user (one already used above)
        for (int i = 0; i < 29; i++) get(f("/reports/dues?asOf=2026-07-31&format=csv"), owner).andExpect(status().isOk());
        get(f("/reports/dues?asOf=2026-07-31&format=csv"), owner).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        get(f("/reports/dues?asOf=2026-07-31"), owner).andExpect(status().isOk());   // the screen itself is not limited
    }

    @Test
    void everyReportExportsAsCsvAndXlsx() throws Exception {
        String t = "/api/v1/tenants/" + tenant;
        List<String> urls = List.of(
                f("/reports/income-statement?from=2026-04-01&to=2026-07-31"),
                f("/reports/income-statement?from=2026-04-01&to=2026-07-31&compare=lastYear"),
                f("/reports/monthly?from=2026-04-01&to=2026-07-31"),
                f("/reports/categories?from=2026-04-01&to=2026-07-31&kind=INCOME&compare=previous"),
                f("/reports/balance-sheet?asOf=2026-07-31"),
                f("/reports/trial-balance?from=2026-04-01&to=2026-07-31"),
                f("/reports/cash-flow?from=2026-04-01&to=2026-07-31"),
                f("/reports/dues?asOf=2026-07-31"),
                f("/reports/activity?from=2026-04-01&to=2026-07-31"),
                f("/parties/" + buyer + "/statement?from=2026-04-01&to=2026-07-31"),
                t + "/stock/reports/valuation?asOf=2026-07-31",
                t + "/stock/reports/on-hand",
                t + "/workforce/reports/wages?from=2026-04-01&to=2026-07-31",
                t + "/assets/reports/running-costs?from=2026-04-01&to=2026-07-31",
                t + "/assets/reports/register?x=1");
        for (String url : urls) {
            for (String format : List.of("csv", "xlsx")) {
                String sep = url.contains("?") ? "&" : "?";
                get(url + sep + "format=" + format + "&lang=ta", owner).andExpect(status().isOk())
                        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("." + format)));
                jdbc.update("delete from sf_audit_history where tenant_id = ? and action = 'REPORT_EXPORTED'", tenant);
                resetRateLimits();
            }
        }
    }

    @Test
    void comparisonsUseTheEarlierPeriodAndShowNoPercentFromZero() throws Exception {
        assertThat(LedgerReportService.comparePeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 31), "previous"))
                .containsExactly(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 3, 31));
        assertThat(LedgerReportService.comparePeriod(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 19), "previous"))
                .containsExactly(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 9));
        assertThat(LedgerReportService.comparePeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 31), "lastYear"))
                .containsExactly(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 31));
        assertThat(LedgerReportService.changePercent(new BigDecimal("150"), new BigDecimal("100"))).isEqualByComparingTo("50.0");
        assertThat(LedgerReportService.changePercent(new BigDecimal("50"), new BigDecimal("-100"))).isEqualByComparingTo("150.0");
        assertThat(LedgerReportService.changePercent(new BigDecimal("10"), BigDecimal.ZERO)).isNull();

        JsonNode c = report("income-statement?from=2026-04-01&to=2026-07-31&compare=previous");
        JsonNode revenue = StreamSupport.stream(c.get("totals").spliterator(), false).filter(x -> x.get("section").asText().equals("OPERATING_REVENUE"))
                .findFirst().orElseThrow();
        money(revenue.get("current"), "40000");
        money(revenue.get("previous"), "0");
        money(revenue.get("change"), "40000");
        assertThat(revenue.get("changePercent").isNull()).isTrue();
        get(f("/reports/income-statement?from=2026-04-01&to=2026-07-31&compare=sideways"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPARISON"));
        get(f("/reports/monthly?from=2025-01-01&to=2026-07-31"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTHLY_RANGE_TOO_LONG"));
    }

    @Test
    void tilesReportsAndExportsFollowTheRole() throws Exception {
        User viewer = newUser("viewer"), supervisor = newUser("supervisor"), worker = newUser("worker");
        addMember(owner, tenant, viewer, "VIEWER");
        addMember(owner, tenant, supervisor, "FIELD_SUPERVISOR");
        addMember(owner, tenant, worker, "WORKER");
        String dash = "/api/v1/tenants/" + tenant + "/dashboard?asOf=2026-07-31";

        JsonNode v = body(get(dash, viewer).andExpect(status().isOk()));
        assertThat(v.get("money").isNull()).isFalse();
        get(f("/reports/trial-balance?from=2026-04-01&to=2026-07-31"), viewer).andExpect(status().isForbidden());
        get(f("/reports/trial-balance?from=2026-04-01&to=2026-07-31&format=csv"), viewer).andExpect(status().isForbidden());

        JsonNode s = body(get(dash, supervisor).andExpect(status().isOk()));
        assertThat(s.get("money").isNull()).as("no money totals for a field supervisor").isTrue();
        assertThat(s.get("trend").isNull()).isTrue();
        assertThat(s.get("crops").isArray()).isTrue();
        assertThat(s.toString()).doesNotContain("98749").doesNotContain("40000");
        get(f("/reports/balance-sheet?asOf=2026-07-31&format=xlsx"), supervisor).andExpect(status().isForbidden());
        JsonNode wages = body(get("/api/v1/tenants/" + tenant + "/workforce/reports/wages?from=2026-04-01&to=2026-07-31", supervisor).andExpect(status().isOk()));
        assertThat(wages.get("amountsShown").asBoolean()).isFalse();
        assertThat(wages.at("/total/wages").isNull()).isTrue();

        JsonNode w = body(get(dash, worker).andExpect(status().isOk()));
        assertThat(w.get("money").isNull()).isTrue();
        assertThat(w.get("attention")).isEmpty();
        get(f("/reports/income-statement?from=2026-04-01&to=2026-07-31&format=csv"), worker).andExpect(status().isForbidden());
    }

    @Test
    void runningCostsGiveCostPerHourAndHideMoneyWithoutFinanceAccess() throws Exception {
        String a = "/api/v1/tenants/" + tenant + "/assets";
        long pump = body(post(a + "/purchases", owner, Map.of("docDate", "2026-04-02", "financialAccountId", bank, "amount", 24000,
                "asset", Map.of("name", "Pump", "kind", "IRRIGATION", "usefulLifeMonths", 120))).andExpect(status().isCreated())).get("id").asLong();
        post(a + "/" + pump + "/usage", owner, Map.of("date", "2026-06-01", "hours", 10)).andExpect(status().isCreated());
        Map<String, Object> diesel = new HashMap<>(line("REPAIRS", 1200, null));
        diesel.put("assetId", pump);
        invoice("bills", "2026-06-02", null, bank, diesel);
        post(a + "/" + pump + "/maintenance", owner, Map.of("doneOn", "2026-06-03", "description", "Seal replaced")).andExpect(status().isCreated());

        JsonNode r = body(get(a + "/reports/running-costs?from=2026-04-01&to=2026-06-30", owner).andExpect(status().isOk()));
        JsonNode row = r.at("/assets/0");
        money(row.get("runningCosts"), "1200");
        money(row.get("depreciation"), "0");      // no depreciation run posted yet: only posted runs count
        money(row.get("costPerHour"), "120");     // Rs 1,200 over 10 hours
        assertThat(row.get("maintenanceDone").asInt()).isEqualTo(1);
        money(row.get("hours"), "10");

        User supervisor = newUser("supervisor-assets");
        addMember(owner, tenant, supervisor, "FIELD_SUPERVISOR");
        JsonNode hidden = body(get(a + "/reports/running-costs?from=2026-04-01&to=2026-06-30", supervisor).andExpect(status().isOk()));
        assertThat(hidden.get("amountsShown").asBoolean()).isFalse();
        assertThat(hidden.at("/assets/0/runningCosts").isNull()).isTrue();
        assertThat(hidden.at("/assets/0/maintenanceDone").asInt()).isEqualTo(1);
    }

    @Test
    void partyStatementIsTenantScoped() throws Exception {
        User other = newUser("other-owner");
        long otherTenant = newTenant(other, "Other farm");
        long otherParty = id(post("/api/v1/tenants/" + otherTenant + "/finance/parties", other, Map.of("name", "Theirs", "customer", true))
                .andExpect(status().isCreated()));
        get(f("/parties/" + otherParty + "/statement?from=2026-04-01&to=2026-07-31"), owner).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARTY_NOT_FOUND"));
        get("/api/v1/tenants/" + otherTenant + "/finance/reports/balance-sheet?asOf=2026-07-31&format=csv", owner).andExpect(status().isNotFound());
    }

    /** Every literal label an export uses exists in the shared catalog, so no file ever shows a raw key. */
    @Test
    void exportLabelsExistInTheCatalog() throws Exception {
        Pattern literal = Pattern.compile("labels\\.t\\(lang, \"([\\w.]+)\"\\s*[,)]");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = literal.matcher(Files.readString(p));
                while (m.find()) if (!m.group(1).endsWith(".") && !labels.has(m.group(1))) missing.add(p.getFileName() + ": " + m.group(1));
            }
        }
        assertThat(missing).isEmpty();
        for (String section : List.of("REVENUE", "COSTS", "OTHER_INCOME", "OPERATING_REVENUE", "OPERATING_COSTS", "OPERATING_PROFIT", "INTEREST", "LOSSES",
                "NET_PROFIT", "ASSETS", "LIABILITIES", "EQUITY")) {
            assertThat(labels.has("reports.section." + section)).as(section).isTrue();
        }
        assertThat(labels.has("reports.compare.previous") && labels.has("reports.compare.lastYear")).isTrue();
    }
}
