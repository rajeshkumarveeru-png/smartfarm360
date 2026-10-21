package com.smartfarm360.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/inventory/stock-movements.md, section 7, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class StockGoldenScenarioTest extends StockTestSupport {

    @Test
    void julyExampleMatchesTheStockMovementDesign() throws Exception {
        newFinanceTenant("stock-golden");
        long agro = party("Agro depot");
        long raman = party("Raman (trader)");
        long farm = newFarm(owner, tenant, "Main farm");
        long parcel = body(post("/api/v1/tenants/" + tenant + "/farms/" + farm + "/land-parcels", owner, Map.of("name", "North field", "area", 2))).get("id").asLong();
        long crop = body(post("/api/v1/tenants/" + tenant + "/crops", owner, Map.of("name", "Paddy"))).get("id").asLong();
        long cycle = body(post("/api/v1/tenants/" + tenant + "/cultivation-cycles", owner, Map.of("farmId", farm, "landParcelId", parcel,
                "cropId", crop, "name", "Samba 2026", "plannedSowingDate", "2026-06-01"))).get("id").asLong();
        long samba = StreamSupport.stream(body(get(f("/cost-objects"), owner)).spliterator(), false)
                .filter(c -> c.path("cropCycleId").asLong() == cycle).findFirst().orElseThrow().get("id").asLong();

        long urea = item("Urea", "INPUT", "kg", Map.of("expenseCategoryId", categoryId("FERTILIZER"),
                "packUnits", List.of(Map.of("name", "bag", "factor", 45))));
        long paddy = item("Paddy", "PRODUCE", "kg", Map.of("saleCategoryId", categoryId("CROP_SALE")));

        // 1. 10 bags of urea on credit at Rs 270 a bag
        stockInvoice("bills", "2026-07-01", agro, itemLine(urea, 10, "bag", 270));
        JsonNode u = stockItem(urea);
        money(u.get("quantity"), "450");
        money(u.get("value"), "2700.00");
        money(u.get("unitCost"), "6.0000");
        // 2. 5 more bags at Rs 300, paid in cash
        stockInvoice("bills", "2026-07-03", null, itemLine(urea, 5, "bag", 300));
        u = stockItem(urea);
        money(u.get("quantity"), "675");
        money(u.get("value"), "4200.00");
        money(u.get("unitCost"), "6.2222");
        // 3. 300 kg used on the Samba cycle
        JsonNode issue = record("/issues", Map.of("docDate", "2026-07-05", "costObjectId", samba, "lines", List.of(qty(urea, 300, "kg"))));
        money(issue.at("/document/summary/totalAmount"), "1866.67");
        assertThat(issue.at("/document/summary/number").asText()).isEqualTo("ISSUE/2026-27/0001");
        money(stockItem(urea).get("value"), "2333.33");
        // 4. 25 kg wet and spoiled
        record("/wastage", Map.of("docDate", "2026-07-10", "reason", "Wet and spoiled", "lines", List.of(qty(urea, 25, null))));
        money(stockItem(urea).get("value"), "2177.77");
        // 5. Count finds 348 kg
        JsonNode count = record("/counts", Map.of("docDate", "2026-07-15", "lines", List.of(Map.of("itemId", urea, "countedQuantity", 348))));
        money(count.at("/document/movements/0/quantity"), "-2");
        money(count.at("/document/movements/0/value"), "-12.44");
        // 6. 40 quintal of paddy harvested into stock from Samba, at nil value
        record("/produce", Map.of("docDate", "2026-07-20", "costObjectId", samba, "lines", List.of(qty(paddy, 40, "quintal"))));
        JsonNode p = stockItem(paddy);
        money(p.get("quantity"), "4000");
        money(p.get("value"), "0");
        // 7. 3,000 kg sold to Raman at Rs 22 on credit; the sale category comes from the item
        Map<String, Object> saleLine = itemLine(paddy, 3000, null, 22);
        saleLine.put("costObjectId", samba);
        JsonNode sale = stockInvoice("sales", "2026-07-25", raman, saleLine);
        money(sale.at("/summary/totalAmount"), "66000.00");
        assertThat(sale.at("/lines/0/categoryCode").asText()).isEqualTo("CROP_SALE");

        // Ledger equals store
        JsonNode rec = body(get(s("/reports/reconciliation"), owner));
        money(rec.get("ledgerBalance"), "2165.33");
        money(rec.get("stockValue"), "2165.33");
        money(rec.get("difference"), "0");

        // Profit (July): only the urea used and lost is a cost, not the Rs 4,200 bought
        JsonNode pl = report("income-statement?from=2026-07-01&to=2026-07-31");
        money(pl.get("operatingRevenueTotal"), "66000");
        money(pl.get("operatingCostTotal"), "1866.67");
        money(pl.get("losses"), "168.00");
        money(pl.get("netProfit"), "63965.33");
        assertThat(pl.at("/operatingCosts/0/categoryCode").asText()).isEqualTo("FERTILIZER");

        // Samba cycle contribution
        JsonNode sambaRow = StreamSupport.stream(report("activity?from=2026-07-01&to=2026-07-31").get("rows").spliterator(), false)
                .filter(r -> r.get("costObjectId").asLong() == samba).findFirst().orElseThrow();
        money(sambaRow.get("revenue"), "66000");
        money(sambaRow.get("costs"), "1866.67");
        money(sambaRow.get("contribution"), "64133.33");

        // Cash flow: only the cash bag purchase moved cash
        money(report("cash-flow?from=2026-07-01&to=2026-07-31").get("operating"), "-1500");

        // Dues
        JsonNode dues = report("dues?asOf=2026-07-31");
        money(dues.get("payableTotal"), "2700");
        money(dues.get("receivableTotal"), "66000");

        // Stock report
        JsonNode onHand = body(get(s("/reports/on-hand"), owner));
        JsonNode ureaRow = StreamSupport.stream(onHand.get("items").spliterator(), false)
                .filter(r -> r.at("/item/id").asLong() == urea).findFirst().orElseThrow();
        money(ureaRow.at("/item/quantity"), "348");
        money(ureaRow.at("/item/value"), "2165.33");
        money(ureaRow.at("/item/unitCost"), "6.2222");
        money(onHand.get("totalValue"), "2165.33");
        money(stockItem(paddy).get("quantity"), "1000");

        // Value at a past date is recomputed from movements: after the two purchases, before any use
        money(body(get(s("/reports/valuation?asOf=2026-07-04"), owner)).get("totalValue"), "4200.00");
        assertThat(body(get(s("/items/" + urea + "/movements"), owner)).get("totalItems").asInt()).isEqualTo(5);
        post(f("/documents/" + issue.at("/document/summary/id").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        money(stockItem(urea).get("value"), "4032.00");
        assertBooksBalance("2026-12-31");
    }
}
