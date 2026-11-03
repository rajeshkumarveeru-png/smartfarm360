package com.smartfarm360.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/reports/dashboard-and-reports.md, section 9, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class ReportsGoldenScenarioTest extends FinanceTestSupport {

    private static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).filter(x -> value.equals(x.path(field).asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + field + "=" + value + " in " + array));
    }

    private static boolean has(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).anyMatch(x -> value.equals(x.path(field).asText()));
    }

    @Test
    void aprilToJulyExampleMatchesTheDashboardAndReportsDesign() throws Exception {
        newFinanceTenant("reports-golden");
        long bank = account("Bank", "BANK");
        long raman = party("Raman Traders");
        long selvam = party("Selvam");
        String a = "/api/v1/tenants/" + tenant + "/assets", w = "/api/v1/tenants/" + tenant + "/workforce";

        // 1 Apr: owner brings Rs 2,00,000 into the bank
        post(f("/owner-money"), owner, Map.of("docDate", "2026-04-01", "direction", "IN", "financialAccountId", bank, "amount", 200000))
                .andExpect(status().isCreated());
        // 10 Apr: tractor on credit from Raman Traders, 60 months, Rs 2,000 a month from May
        JsonNode tractor = body(post(a + "/purchases", owner, Map.of("docDate", "2026-04-10", "partyId", raman, "amount", 120000,
                "asset", Map.of("name", "Tractor", "kind", "VEHICLE", "usefulLifeMonths", 60))).andExpect(status().isCreated()));
        money(tractor.get("monthlyDepreciation"), "2000.00");
        long tractorBill = tractor.get("sourceDocumentId").asLong();
        // Seed and fertilizer bills paid from the bank: Rs 30,000
        invoice("bills", "2026-04-15", null, bank, line("SEEDS", 10000, null));
        invoice("bills", "2026-06-10", null, bank, line("FERTILIZER", 20000, null));
        // Wages: 48 days at Rs 500 = Rs 24,000; Rs 20,000 paid
        long worker = body(post(w + "/workers", owner, Map.of("name", "Murugan", "payType", "DAILY", "rate", 500, "joinedOn", "2026-04-01"))
                .andExpect(status().isCreated())).get("id").asLong();
        for (int month = 4; month <= 7; month++) {
            for (int day = 1; day <= 12; day++) {
                put(w + "/attendance/" + LocalDate.of(2026, month, day), owner, Map.of("entries", List.of(Map.of("workerId", worker, "status", "PRESENT"))))
                        .andExpect(status().isOk());
            }
        }
        for (int month = 4; month <= 7; month++) {   // monthly pay runs, Rs 6,000 each
            java.time.YearMonth m = java.time.YearMonth.of(2026, month);
            money(body(post(w + "/pay-runs", owner, Map.of("from", m.atDay(1).toString(), "to", m.atEndOfMonth().toString()))
                    .andExpect(status().isCreated())).at("/summary/totalAmount"), "6000.00");
        }
        post(w + "/wage-payments", owner, Map.of("date", "2026-07-31", "financialAccountId", bank,
                "payments", List.of(Map.of("workerId", worker, "cash", 20000, "advanceRecovered", 0)))).andExpect(status().isCreated());
        // 15 Jul: paddy sold to Selvam for Rs 90,000, Rs 50,000 received
        long sale = invoice("sales", "2026-07-15", selvam, null, line("CROP_SALE", 90000, null)).at("/summary/id").asLong();
        settle("receipts", "2026-07-15", selvam, bank, 50000, sale, 50000);
        // 20 Jul: Rs 70,000 paid to Raman Traders
        settle("payments", "2026-07-20", raman, bank, 70000, tractorBill, 70000);
        // Depreciation for May, June and July
        for (String m : List.of("2026-05", "2026-06", "2026-07")) {
            money(body(post(a + "/depreciation-runs", owner, Map.of("month", m)).andExpect(status().isCreated())).at("/summary/totalAmount"), "2000.00");
        }

        // Income statement, April to July
        JsonNode is = report("income-statement?from=2026-04-01&to=2026-07-31");
        money(is.get("operatingRevenueTotal"), "90000");
        money(is.get("operatingCostTotal"), "60000");
        money(is.get("netProfit"), "30000");

        // Balance sheet as of 31 July
        JsonNode bs = report("balance-sheet?asOf=2026-07-31");
        assertThat(bs.get("fiscalYearFrom").asText()).isEqualTo("2026-04-01");
        money(find(bs.get("assets"), "accountCode", "1010").get("amount"), "130000");
        money(find(bs.get("assets"), "accountCode", "1100").get("amount"), "40000");
        money(find(bs.get("assets"), "accountCode", "1300").get("amount"), "120000");
        money(find(bs.get("assets"), "accountCode", "1310").get("amount"), "-6000");
        money(bs.get("assetTotal"), "284000");
        money(find(bs.get("liabilities"), "accountCode", "2000").get("amount"), "50000");
        money(find(bs.get("liabilities"), "accountCode", "2100").get("amount"), "4000");
        money(bs.get("liabilityTotal"), "54000");
        money(find(bs.get("equity"), "accountCode", "3000").get("amount"), "200000");
        money(bs.get("earlierProfit"), "0");
        money(bs.get("yearProfit"), "30000");
        money(bs.get("equityTotal"), "230000");
        money(bs.get("liabilitiesAndEquity"), "284000");
        money(bs.get("difference"), "0");

        // Trial balance agrees
        JsonNode tb = report("trial-balance?from=2026-04-01&to=2026-07-31");
        money(tb.get("debitTotal"), tb.get("creditTotal").asText());
        money(tb.get("closingTotal"), "0");

        // Monthly profit and loss: the months add up to the period, and the total column is the income statement
        JsonNode monthly = report("monthly?from=2026-04-01&to=2026-07-31");
        assertThat(monthly.get("months")).hasSize(4);
        JsonNode net = find(monthly.get("totals"), "section", "NET_PROFIT");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode m : net.get("amounts")) sum = sum.add(new BigDecimal(m.asText()));
        assertThat(sum).isEqualByComparingTo("30000");
        money(net.get("total"), "30000");
        money(net.get("amounts").get(0), "-16000");   // April: seeds and wages
        money(net.get("amounts").get(3), "82000");    // July: sale less wages and depreciation

        // Costs by category, with shares
        JsonNode cats = report("categories?from=2026-04-01&to=2026-07-31&kind=COSTS");
        money(cats.get("total"), "60000");
        JsonNode labour = find(cats.get("rows"), "accountCode", "5100");
        money(labour.get("amount"), "24000");
        money(labour.get("share"), "40.0");
        assertThat(cats.at("/rows/0/accountCode").asText()).isEqualTo("5100");   // largest first

        // Party statement: Raman Traders
        JsonNode st = body(get(f("/parties/" + raman + "/statement?from=2026-04-01&to=2026-07-31"), owner).andExpect(status().isOk()));
        money(st.get("opening"), "0");
        money(st.get("closing"), "-50000");
        assertThat(st.get("lines")).hasSize(2);
        money(st.at("/openItems/0/open"), "50000");

        // Dashboard as of 31 July
        JsonNode dash = body(get("/api/v1/tenants/" + tenant + "/dashboard?asOf=2026-07-31", owner).andExpect(status().isOk()));
        assertThat(dash.get("errors")).isEmpty();
        money(dash.at("/money/cashTotal"), "130000");
        money(dash.at("/money/receivableTotal"), "40000");
        money(dash.at("/money/payableTotal"), "50000");
        money(dash.at("/money/yearNetProfit"), "30000");
        assertThat(has(dash.get("attention"), "code", "OVERDUE_SALES")).as("16 days old is not over 30 days").isFalse();
        money(find(dash.get("attention"), "code", "WAGES_OWED").get("amount"), "4000");
        assertThat(has(dash.get("attention"), "code", "DEPRECIATION_DUE")).isFalse();
        assertThat(dash.at("/trend/months")).hasSize(12);
        JsonNode july = dash.at("/trend/months/11");
        assertThat(july.get("month").asText()).isEqualTo("2026-07");
        money(july.get("revenue"), "90000");

        // Each dashboard tile equals its source report (principle 1)
        JsonNode summary = report("summary?asOf=2026-07-31");
        money(dash.at("/money/cashTotal"), summary.get("cashTotal").asText());
        money(dash.at("/money/yearNetProfit"), find(report("monthly?from=2026-04-01&to=2026-07-31").get("totals"), "section", "NET_PROFIT").get("total").asText());
        JsonNode july2 = report("income-statement?from=2026-07-01&to=2026-07-31");
        money(july.get("operatingProfit"), july2.get("operatingProfit").asText());
        money(find(dash.get("attention"), "code", "WAGES_OWED").get("amount"),
                body(get(w + "/reports/reconciliation", owner)).get("workersOwed").asText());

        // A month later, August depreciation is due
        JsonNode sept = body(get("/api/v1/tenants/" + tenant + "/dashboard?asOf=2026-09-05", owner));
        assertThat(find(sept.get("attention"), "code", "DEPRECIATION_DUE").get("detail").asText()).isEqualTo("2026-08");
        assertThat(find(sept.get("attention"), "code", "OVERDUE_SALES").get("count").asInt()).isEqualTo(1);

        assertBooksBalance("2026-07-31");
        assertThat(journalImbalance()).isZero();
    }
}
