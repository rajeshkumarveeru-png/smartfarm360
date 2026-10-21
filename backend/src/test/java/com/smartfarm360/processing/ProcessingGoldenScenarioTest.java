package com.smartfarm360.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/processing/processing-and-trading.md, section 10, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class ProcessingGoldenScenarioTest extends FinanceTestSupport {

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    private long item(String name, String kind, String unit, boolean lots, String category) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", kind, "baseUnit", unit, "trackLots", lots));
        body.put(kind.equals("INPUT") ? "expenseCategoryId" : "saleCategoryId", categoryId(category));
        return body(post(t("/stock/items"), owner, body).andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, Object> soldLine(String category, long item, int kg, String lot, int amount, Long costObject) {
        Map<String, Object> l = new HashMap<>(line(category, amount, costObject));
        l.put("itemId", item);
        l.put("quantity", kg);
        if (lot != null) l.put("lotNo", lot);
        return l;
    }

    private JsonNode output(long batch, String role, Long item, String description, int kg, boolean finish) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("date", "2026-10-05", "role", role, "quantity", kg, "finish", finish));
        if (item != null) body.put("itemId", item);
        if (description != null) body.put("description", description);
        return body(post(t("/processing/batches/" + batch + "/outputs"), owner, body).andExpect(status().isCreated()));
    }

    private JsonNode income(String from, String to, Long costObject) throws Exception {
        return body(get(f("/reports/income-statement?from=" + from + "&to=" + to + (costObject == null ? "" : "&costObjectId=" + costObject)), owner)
                .andExpect(status().isOk()));
    }

    @Test
    void riceMillingTraceAndTradingMatchTheProcessingDesign() throws Exception {
        newFinanceTenant("processing-golden");
        put(t("/modules/PROCESSING"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        long farm = newFarm(owner, tenant, "Main farm");
        long field = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "North field", "area", 2))).get("id").asLong();
        long paddyCrop = body(post(t("/crops"), owner, Map.of("name", "Paddy"))).get("id").asLong();
        long cycle = body(post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", field, "cropId", paddyCrop, "name", "Samba 2026",
                "plannedSowingDate", "2026-06-25", "cultivatedArea", 2, "areaUnit", "ACRE")).andExpect(status().isCreated())).get("id").asLong();
        long samba = jdbc.queryForObject("select id from sf_cost_objects where crop_cycle_id = ?", Long.class, cycle);
        long paddy = item("Paddy", "PRODUCE", "kg", true, "CROP_SALE");
        long rice = item("Rice", "PRODUCE", "kg", true, "PROCESSED_SALE");
        long broken = item("Broken rice", "PRODUCE", "kg", true, "PROCESSED_SALE");
        long bran = item("Bran", "PRODUCE", "kg", true, "PROCESSED_SALE");
        long bags = item("Rice bags", "INPUT", "piece", false, "OTHER_INPUTS");
        long turmeric = item("Turmeric (bought)", "RESALE", "kg", false, "CROP_SALE");

        // ---- Samba 2026: Rs 60,000 of costs and 4,000 kg of paddy, lot SAMBA-2026-H1 ----
        invoice("bills", "2026-07-15", null, cash, line("LABOUR", 60000, samba));
        post(t("/stock/produce"), owner, Map.of("docDate", "2026-09-30", "costObjectId", samba,
                "lines", List.of(Map.of("itemId", paddy, "quantity", 4000, "lotNo", "SAMBA-2026-H1")))).andExpect(status().isCreated());

        // ---- Batch RM-2610-01: 2,000 kg of paddy moved in at average cost (M3) ----
        JsonNode batch = body(post(t("/processing/batches"), owner, Map.of("code", "RM-2610-01", "name", "Rice milling Oct 2026", "kind", "MILLING",
                "farmId", farm, "mainItemId", rice, "startedOn", "2026-10-01", "expectedEnd", "2026-10-10")).andExpect(status().isCreated()));
        long b = batch.get("id").asLong(), rm = batch.get("costObjectId").asLong();
        BigDecimal farmOctBefore = new BigDecimal(income("2026-10-01", "2026-10-31", null).get("netProfit").asText());
        JsonNode moved = body(post(t("/stock/internal-transfers"), owner, Map.of("date", "2026-10-01", "itemId", paddy, "quantity", 2000, "lotNo", "SAMBA-2026-H1",
                "fromCostObjectId", samba, "toCostObjectId", rm)).andExpect(status().isCreated()));
        assertThat(moved.get("method").asText()).isEqualTo("AVERAGE_COST");
        money(moved.get("unitCost"), "15");
        money(moved.get("costMoved"), "30000");
        money(income("2026-10-01", "2026-10-31", null).get("netProfit"), farmOctBefore.toPlainString());   // the farm's total is unchanged
        money(income("2026-01-01", "2026-10-31", samba).get("operatingCostTotal"), "30000");

        // ---- Drying step, milling, bags and labour ----
        JsonNode drying = body(post(t("/processing/batches/" + b + "/steps"), owner, Map.of("date", "2026-10-02", "kind", "DRYING", "kgIn", 2000, "kgOut", 1920,
                "moistureBefore", 17, "moistureAfter", 13.5)).andExpect(status().isCreated()));
        money(drying.get("lossKg"), "80");
        invoice("bills", "2026-10-03", null, cash, line("MACHINE_HIRE", 4000, rm));
        post(f("/bills"), owner, Map.of("docDate", "2026-10-03", "financialAccountId", cash, "lines", List.of(Map.of("itemId", bags, "quantity", 26, "rate", 40))))
                .andExpect(status().isCreated());
        post(t("/stock/issues"), owner, Map.of("docDate", "2026-10-04", "costObjectId", rm, "lines", List.of(Map.of("itemId", bags, "quantity", 26))))
                .andExpect(status().isCreated());
        invoice("bills", "2026-10-04", null, cash, line("LABOUR", 1960, rm));

        // ---- 5 October: output with lots from the batch code (M5) ----
        assertThat(output(b, "MAIN", rice, null, 1300, false).get("lotNo").asText()).isEqualTo("RM-2610-01-1");
        assertThat(output(b, "BYPRODUCT", broken, null, 100, false).get("lotNo").asText()).isEqualTo("RM-2610-01-2");
        assertThat(output(b, "BYPRODUCT", bran, null, 160, false).get("lotNo").asText()).isEqualTo("RM-2610-01-3");
        JsonNode husk = output(b, "WASTE", null, "Husk (burnt in the dryer)", 340, true);
        assertThat(husk.get("documentId").isNull()).isTrue();
        assertThat(body(get(t("/processing/batches/" + b), owner)).get("status").asText()).isEqualTo("FINISHED");

        // ---- Sales assigned to the batch ----
        long lakshmi = party("Lakshmi Stores");
        invoice("sales", "2026-10-06", lakshmi, null, soldLine("PROCESSED_SALE", rice, 1000, "RM-2610-01-1", 60000, rm));
        invoice("sales", "2026-10-07", null, cash, soldLine("PROCESSED_SALE", rice, 300, "RM-2610-01-1", 18000, rm));
        invoice("sales", "2026-10-07", null, cash, soldLine("PROCESSED_SALE", broken, 100, "RM-2610-01-2", 3000, rm));
        invoice("sales", "2026-10-08", null, cash, soldLine("PROCESSED_SALE", bran, 160, "RM-2610-01-3", 2400, rm));

        JsonNode w = body(get(t("/processing/batches/" + b + "/report?from=2026-10-01&to=2026-10-31"), owner).andExpect(status().isOk())).get("whole");
        assertThat(w.get("days").asInt()).isEqualTo(4);
        money(w.get("inputKg"), "2000");
        Map<String, String> yields = new HashMap<>();
        for (JsonNode o : w.get("outputs")) yields.put(o.get("name").asText(), new BigDecimal(o.get("kg").asText()).stripTrailingZeros().toPlainString() + "|"
                + new BigDecimal(o.get("yieldPercent").asText()).stripTrailingZeros().toPlainString());
        assertThat(yields).containsEntry("Rice", "1300|65").containsEntry("Broken rice", "100|5").containsEntry("Bran", "160|8")
                .containsEntry("Husk (burnt in the dryer)", "340|17");
        money(w.get("lossKg"), "100");
        money(w.get("lossPercent"), "5.0");
        money(w.get("costs"), "37000");
        money(w.get("revenue"), "83400");
        money(w.get("profit"), "46400");
        money(w.get("byproductSales"), "5400");
        money(w.get("mainCostPerKg"), "24.31");
        money(w.get("rawCostPerKg"), "15.00");

        // ---- Trace (M8) ----
        JsonNode back = body(get(t("/processing/trace?lotNo=RM-2610-01-1"), owner).andExpect(status().isOk())).get(0);
        assertThat(back.at("/origin/kind").asText()).isEqualTo("BATCH");
        assertThat(back.at("/origin/batchCode").asText()).isEqualTo("RM-2610-01");
        assertThat(back.at("/inputs/0/lotNo").asText()).isEqualTo("SAMBA-2026-H1");
        assertThat(back.at("/inputs/0/origin/kind").asText()).isEqualTo("PRODUCED");
        assertThat(back.at("/inputs/0/origin/activityName").asText()).isEqualTo("Samba 2026");
        assertThat(back.at("/sales/0/partyName").asText()).isEqualTo("Lakshmi Stores");
        money(back.at("/sales/0/quantity"), "1000");
        JsonNode fwd = body(get(t("/processing/trace?lotNo=samba-2026-h1"), owner).andExpect(status().isOk())).get(0);
        assertThat(fwd.at("/usedIn/0/batchCode").asText()).isEqualTo("RM-2610-01");
        money(fwd.at("/usedIn/0/quantity"), "2000");
        assertThat(fwd.at("/usedIn/0/outputs").findValuesAsText("lotNo")).containsExactly("RM-2610-01-1", "RM-2610-01-2", "RM-2610-01-3");
        assertThat(fwd.at("/usedIn/0/outputs/0/sales")).hasSize(2);

        // ---- Trading (M10): turmeric bought at Rs 80 and sold at Rs 100 ----
        post(f("/bills"), owner, Map.of("docDate", "2026-10-02", "financialAccountId", cash, "lines", List.of(Map.of("itemId", turmeric, "quantity", 2000, "rate", 80))))
                .andExpect(status().isCreated());
        invoice("sales", "2026-10-09", null, cash, soldLine("CROP_SALE", turmeric, 1500, null, 150000, null));
        JsonNode trading = body(get(t("/stock/reports/trading?from=2026-10-01&to=2026-10-31"), owner).andExpect(status().isOk()));
        assertThat(trading).hasSize(1);
        JsonNode tr = trading.get(0);
        money(tr.get("boughtQuantity"), "2000");
        money(tr.get("boughtValue"), "160000");
        money(tr.get("soldQuantity"), "1500");
        money(tr.get("revenue"), "150000");
        money(tr.get("costOfSales"), "120000");
        money(tr.get("margin"), "30000");
        money(tr.get("marginPercent"), "20.0");
        money(tr.get("stockQuantity"), "500");
        money(tr.get("stockValue"), "40000");

        // Reports and exports
        assertThat(body(get(t("/processing/reports/batches?from=2026-10-01&to=2026-10-31"), owner))).hasSize(1);
        get(t("/processing/reports/batches?from=2026-10-01&to=2026-10-31&format=csv&lang=ta"), owner).andExpect(status().isOk());
        get(t("/stock/reports/trading?from=2026-10-01&to=2026-10-31&format=xlsx&lang=en"), owner).andExpect(status().isOk());
        assertBooksBalance("2026-10-31");
    }
}
