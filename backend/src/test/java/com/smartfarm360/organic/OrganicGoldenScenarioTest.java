package com.smartfarm360.organic;

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
 * The worked example in docs/organic/organic-resources.md, section 8, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class OrganicGoldenScenarioTest extends FinanceTestSupport {

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    private long produceItem(String name, String saleCategory) throws Exception {
        return body(post(t("/stock/items"), owner, Map.of("name", name, "kind", "PRODUCE", "baseUnit", "kg", "saleCategoryId", categoryId(saleCategory)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void produce(String date, long item, int kg, long costObject) throws Exception {
        post(t("/stock/produce"), owner, Map.of("docDate", date, "costObjectId", costObject, "lines", List.of(Map.of("itemId", item, "quantity", kg))))
                .andExpect(status().isCreated());
    }

    private JsonNode transfer(String date, long item, int kg, long from, long to, String method) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("date", date, "itemId", item, "quantity", kg, "fromCostObjectId", from, "toCostObjectId", to));
        if (method != null) body.put("costMethod", method);
        return body(post(t("/stock/internal-transfers"), owner, body).andExpect(status().isCreated()));
    }

    private long cycleCostObject(long farm, long parcel, long crop, String name, String sowing) throws Exception {
        long cycle = body(post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", parcel, "cropId", crop, "name", name,
                "plannedSowingDate", sowing, "cultivatedArea", 1, "areaUnit", "ACRE")).andExpect(status().isCreated())).get("id").asLong();
        return jdbc.queryForObject("select id from sf_cost_objects where crop_cycle_id = ?", Long.class, cycle);
    }

    private JsonNode income(String from, String to, Long costObject) throws Exception {
        return body(get(f("/reports/income-statement?from=" + from + "&to=" + to + (costObject == null ? "" : "&costObjectId=" + costObject)), owner)
                .andExpect(status().isOk()));
    }

    @Test
    void vermicompostAndFodderMatchTheOrganicResourcesDesign() throws Exception {
        newFinanceTenant("organic-golden");
        put(t("/modules/ORGANIC"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        put(t("/modules/DAIRY"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        long farm = newFarm(owner, tenant, "Main farm");
        long field = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "North field", "area", 3))).get("id").asLong();
        long paddy = body(post(t("/crops"), owner, Map.of("name", "Paddy"))).get("id").asLong();
        long groundnut = body(post(t("/crops"), owner, Map.of("name", "Groundnut"))).get("id").asLong();
        long grass = body(post(t("/crops"), owner, Map.of("name", "CO-4 grass"))).get("id").asLong();
        long samba = cycleCostObject(farm, field, paddy, "Samba 2026", "2026-06-25");
        long gnut = cycleCostObject(farm, field, groundnut, "Groundnut 2026", "2026-06-20");
        long fodderPlot = cycleCostObject(farm, field, grass, "CO-4 grass 2026", "2026-05-01");
        long herd = body(post(t("/livestock/groups"), owner, Map.of("name", "Milking herd", "purpose", "MILKING")).andExpect(status().isCreated()))
                .get("costObjectId").asLong();
        long dung = produceItem("Cow dung", "MANURE_SALE");
        long straw = produceItem("Paddy straw", "CROP_SALE");
        long fodder = produceItem("Green fodder", "CROP_SALE");
        long vermi = produceItem("Vermicompost", "MANURE_SALE");

        // ---- Fodder to the herd: Rs 12,000 of costs, 24,000 kg produced, 6,000 kg fed at average cost ----
        invoice("bills", "2026-06-10", null, cash, line("LABOUR", 12000, fodderPlot));
        produce("2026-07-01", fodder, 24000, fodderPlot);
        JsonNode byDefault = body(post(t("/stock/internal-transfers/preview"), owner, Map.of("date", "2026-07-20", "itemId", fodder, "quantity", 6000,
                "fromCostObjectId", fodderPlot, "toCostObjectId", herd)).andExpect(status().isOk()));
        assertThat(byDefault.get("method").asText()).isEqualTo("NIL");       // a byproduct or other produce moves at nil unless chosen (L9)
        BigDecimal farmJulyBefore = new BigDecimal(income("2026-07-01", "2026-07-31", null).get("netProfit").asText());
        JsonNode fed = transfer("2026-07-20", fodder, 6000, fodderPlot, herd, "AVERAGE_COST");
        money(fed.get("unitCost"), "0.5");
        money(fed.get("costMoved"), "3000");
        money(income("2026-06-01", "2026-07-31", fodderPlot).get("operatingCostTotal"), "9000");
        money(income("2026-06-01", "2026-07-31", herd).get("operatingCostTotal"), "3000");
        money(income("2026-07-01", "2026-07-31", null).get("netProfit"), farmJulyBefore.toPlainString());   // the farm's total is unchanged

        // ---- Making vermicompost: dung and straw in at nil, Rs 10,000 of bills ----
        produce("2026-07-31", dung, 2000, herd);
        produce("2026-07-31", straw, 500, samba);
        JsonNode batch = body(post(t("/organic/batches"), owner, Map.of("name", "VC bed 1 – Aug 2026", "kind", "VERMICOMPOST", "farmId", farm,
                "startedOn", "2026-08-01", "readyBy", "2026-10-10")).andExpect(status().isCreated()));
        long b = batch.get("id").asLong(), vc = batch.get("costObjectId").asLong();
        assertThat(batch.get("status").asText()).isEqualTo("MAKING");
        JsonNode dungIn = transfer("2026-08-02", dung, 2000, herd, vc, null);
        assertThat(dungIn.get("method").asText()).isEqualTo("NIL");
        money(dungIn.get("costMoved"), "0");
        transfer("2026-08-02", straw, 500, samba, vc, null);
        invoice("bills", "2026-08-03", null, cash, line("OTHER_INPUTS", 3000, vc));
        invoice("bills", "2026-08-03", null, cash, line("OTHER_INPUTS", 2000, vc));
        invoice("bills", "2026-09-30", null, cash, line("LABOUR", 5000, vc));
        post(t("/organic/batches/" + b + "/logs"), owner, Map.of("date", "2026-08-20", "kind", "TURNED", "notes", "Turned and watered")).andExpect(status().isCreated());

        // ---- 15 October: 1,000 kg out and the batch finished ----
        post(t("/organic/batches/" + b + "/outputs"), owner, Map.of("date", "2026-10-15", "itemId", vermi, "quantity", 1000, "finish", true))
                .andExpect(status().isCreated());
        assertThat(body(get(t("/organic/batches/" + b), owner)).get("status").asText()).isEqualTo("FINISHED");

        // ---- 18 October: 300 kg sold at Rs 15; 20 October: 600 kg to the Groundnut cycle at average cost ----
        Map<String, Object> saleLine = new HashMap<>(line("MANURE_SALE", 4500, vc));
        saleLine.put("itemId", vermi);
        saleLine.put("quantity", 300);
        invoice("sales", "2026-10-18", null, cash, saleLine);
        JsonNode preview = body(post(t("/stock/internal-transfers/preview"), owner, Map.of("date", "2026-10-20", "itemId", vermi, "quantity", 600,
                "fromCostObjectId", vc, "toCostObjectId", gnut)).andExpect(status().isOk()));
        assertThat(preview.get("method").asText()).isEqualTo("AVERAGE_COST");   // organic batch output moves at average cost by default (L9)
        money(preview.get("unitCost"), "10");
        money(preview.get("costMoved"), "6000");
        BigDecimal farmOctBefore = new BigDecimal(income("2026-10-01", "2026-10-31", null).get("netProfit").asText());
        JsonNode spread = transfer("2026-10-20", vermi, 600, vc, gnut, null);
        money(spread.get("costMoved"), "6000");
        money(income("2026-10-01", "2026-10-31", null).get("netProfit"), farmOctBefore.toPlainString());
        money(income("2026-06-01", "2026-10-31", gnut).get("operatingCostTotal"), "6000");

        JsonNode r = body(get(t("/organic/batches/" + b + "/report?from=2026-08-01&to=2026-10-31"), owner).andExpect(status().isOk())).get("whole");
        assertThat(r.get("days").asInt()).isEqualTo(75);
        money(r.get("inputKg"), "2500");
        money(r.get("output"), "1000");
        assertThat(r.get("outputUnit").asText()).isEqualTo("kg");
        money(r.get("yieldPercent"), "40.0");
        money(r.get("costs"), "10000");
        money(r.get("costPerUnit"), "10.00");
        money(r.get("movedOut"), "6000");
        money(r.get("revenue"), "4500");
        money(r.get("profit"), "500");
        money(r.get("inStock"), "100");
        money(body(get(t("/stock/items/" + vermi), owner)).get("quantity"), "100");

        // Reports and exports
        assertThat(body(get(t("/stock/internal-transfers?from=2026-07-01&to=2026-10-31"), owner))).hasSize(4);
        get(t("/stock/internal-transfers?from=2026-07-01&to=2026-10-31&format=csv&lang=ta"), owner).andExpect(status().isOk());
        assertThat(body(get(t("/organic/reports/batches?from=2026-08-01&to=2026-10-31"), owner))).hasSize(1);
        get(t("/organic/reports/batches?from=2026-08-01&to=2026-10-31&format=xlsx&lang=en"), owner).andExpect(status().isOk());
        assertBooksBalance("2026-10-31");
    }
}
