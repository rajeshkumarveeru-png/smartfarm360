package com.smartfarm360.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.inventory.StockTestSupport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/crops/crop-management.md, section 9, recorded through the API. Every figure the document
 * promises is asserted here; if this test changes, the document must change too.
 */
class CropGoldenScenarioTest extends StockTestSupport {

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    private long cycle(long farm, long parcel, long crop, String name, String sowing, int acres) throws Exception {
        return body(post(t("/cultivation-cycles"), owner, Map.of("farmId", farm, "landParcelId", parcel, "cropId", crop, "name", name,
                "plannedSowingDate", sowing, "cultivatedArea", acres, "areaUnit", "ACRE")).andExpect(status().isCreated())).get("id").asLong();
    }

    private JsonNode activity(Map<String, Object> body) throws Exception {
        return body(post(t("/activities"), owner, body).andExpect(status().isCreated()));
    }

    private JsonNode report(long cycle, String query) throws Exception {
        return body(get(t("/cultivation-cycles/" + cycle + "/report?" + query), owner).andExpect(status().isOk()));
    }

    @Test
    void julyExampleMatchesTheCropManagementDesign() throws Exception {
        newFinanceTenant("crop-golden");
        long agro = party("Agro depot");
        long raman = party("Raman (trader)");
        long farm = newFarm(owner, tenant, "Main farm");
        long north = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "North field", "area", 2))).get("id").asLong();
        long south = body(post(t("/farms/" + farm + "/land-parcels"), owner, Map.of("name", "South field", "area", 1))).get("id").asLong();
        long paddy = body(post(t("/crops"), owner, Map.of("name", "Paddy"))).get("id").asLong();
        long groundnut = body(post(t("/crops"), owner, Map.of("name", "Groundnut"))).get("id").asLong();
        long samba = cycle(farm, north, paddy, "Samba 2026", "2026-06-25", 2);
        long gnut = cycle(farm, south, groundnut, "Groundnut 2026", "2026-06-20", 1);
        post(t("/cultivation-cycles/" + gnut + "/status"), owner, Map.of("action", "START", "date", "2026-06-20")).andExpect(status().isOk());

        // Urea in stock at Rs 6.2222/kg (the stock design's example), bought in June
        long urea = item("Urea", "INPUT", "kg", Map.of("expenseCategoryId", categoryId("FERTILIZER"), "packUnits", List.of(Map.of("name", "bag", "factor", 45))));
        stockInvoice("bills", "2026-06-10", agro, itemLine(urea, 10, "bag", 270));
        stockInvoice("bills", "2026-06-12", null, itemLine(urea, 5, "bag", 300));

        // 1. Sowing, with seed bought on credit from Agro depot
        JsonNode sowing = activity(Map.of("cycleId", samba, "activityType", "SOWING", "activityDate", "2026-07-01",
                "paidWork", Map.of("categoryId", categoryId("SEEDS"), "amount", 4000, "partyId", agro, "description", "Seed paddy")));
        assertThat(sowing.at("/activity/billDocumentId").isNumber()).isTrue();
        JsonNode c = body(get(t("/cultivation-cycles/" + samba), owner));
        assertThat(c.get("status").asText()).isEqualTo("GROWING");
        assertThat(c.get("actualSowingDate").asText()).isEqualTo("2026-07-01");
        // 2. Fertilizer from stock
        JsonNode fert = activity(Map.of("cycleId", samba, "activityType", "FERTILIZER", "activityDate", "2026-07-05",
                "inputs", List.of(Map.of("itemId", urea, "quantity", 300, "unit", "kg"))));
        long issueId = fert.at("/activity/stockDocumentId").asLong();
        money(body(get(f("/documents/" + issueId), owner)).at("/summary/totalAmount"), "1866.67");
        // 3. Weeding labour and 4. tractor hire, paid in cash
        activity(Map.of("cycleId", samba, "activityType", "WEEDING", "activityDate", "2026-07-10",
                "paidWork", Map.of("categoryId", categoryId("LABOUR"), "amount", 6000, "financialAccountId", cash)));
        activity(Map.of("cycleId", samba, "activityType", "LAND_PREPARATION", "activityDate", "2026-07-02",
                "paidWork", Map.of("categoryId", categoryId("MACHINE_HIRE"), "amount", 3000, "financialAccountId", cash)));
        // 5. Electricity for the whole farm
        invoice("bills", "2026-07-15", null, cash, line("ELECTRICITY_WATER", 1200, null));
        // 7. Harvest 40 quintal into stock as a lot
        JsonNode harvest = body(post(t("/harvests"), owner, Map.of("cycleId", samba, "harvestDate", "2026-07-20", "quantity", 40, "unit", "quintal"))
                .andExpect(status().isCreated()));
        assertThat(harvest.at("/harvest/lotNo").asText()).isEqualTo("SAMBA-2026-H1");
        money(harvest.at("/harvest/baseQuantity"), "4000");
        long paddyItem = harvest.at("/harvest/itemId").asLong();
        // 8. Sale from the lot: assigned to Samba without choosing it
        Map<String, Object> saleLine = new HashMap<>(itemLine(paddyItem, 3000, null, 22));
        saleLine.put("lotNo", "SAMBA-2026-H1");
        JsonNode sale = stockInvoice("sales", "2026-07-25", raman, saleLine);
        money(sale.at("/summary/totalAmount"), "66000.00");
        assertThat(sale.at("/lines/0/costObjectName").asText()).isEqualTo("Samba 2026");

        // Before the allocation, overhead holds the electricity
        money(report(samba, "from=2026-07-01&to=2026-07-31").get("profit"), "51133.33");

        // 6. Overhead allocation for July: area x days, Samba 2 x 31, Groundnut 1 x 31
        JsonNode preview = body(post(f("/overhead-allocations/preview"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isOk()));
        money(preview.get("allocated"), "1200.00");
        JsonNode alloc = body(post(f("/overhead-allocations"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isCreated()));
        assertThat(alloc.at("/summary/number").asText()).isEqualTo("ALLOC/2026-27/0001");

        // Samba report (section 9)
        JsonNode r = report(samba, "from=2026-07-01&to=2026-07-31&price=22");
        money(r.get("revenueTotal"), "66000.00");
        money(r.get("directCostTotal"), "14866.67");
        money(r.get("allocatedTotal"), "800.00");
        money(r.get("profit"), "50333.33");
        money(r.get("totalCost"), "15666.67");
        money(r.get("areaAcres"), "2");
        money(r.get("costPerAcre"), "7833.34");
        money(r.at("/harvested/0/quantity"), "4000");
        assertThat(r.get("costUnit").asText()).isEqualTo("kg");
        money(r.get("yieldPerAcre"), "2000");
        money(r.get("costPerUnit"), "3.9167");
        money(r.get("unsold"), "1000");
        money(r.get("estimateValue"), "22000.00");
        assertThat(StreamSupport.stream(r.get("directCosts").spliterator(), false).map(x -> x.get("categoryCode").asText()).toList())
                .contains("SEEDS", "FERTILIZER", "LABOUR", "MACHINE_HIRE");

        // Groundnut carries the rest of the overhead; the farm's July profit is unchanged by the allocation
        money(report(gnut, "from=2026-07-01&to=2026-07-31").get("allocatedTotal"), "400.00");
        JsonNode farmPl = report("income-statement?from=2026-07-01&to=2026-07-31");
        money(farmPl.get("netProfit"), "49933.33");
        JsonNode electricity = StreamSupport.stream(farmPl.get("operatingCosts").spliterator(), false)
                .filter(x -> "ELECTRICITY_WATER".equals(x.path("categoryCode").asText())).findFirst().orElseThrow();
        money(electricity.get("amount"), "1200.00");
        JsonNode overhead = StreamSupport.stream(report("activity?from=2026-07-01&to=2026-07-31").get("rows").spliterator(), false)
                .filter(x -> "OVERHEAD".equals(x.get("kind").asText())).findFirst().orElseThrow();
        money(overhead.get("costs"), "0");

        // Stock: 1,000 kg of paddy left in the harvest lot
        money(stockItem(paddyItem).get("quantity"), "1000");
        assertThat(body(get(t("/cultivation-cycles/" + samba), owner)).get("status").asText()).isEqualTo("HARVESTING");
    }
}
