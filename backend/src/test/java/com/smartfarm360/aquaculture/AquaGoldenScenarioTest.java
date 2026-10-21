package com.smartfarm360.aquaculture;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/aquaculture/aquaculture.md, section 10, recorded through the API. Every figure the document
 * promises is asserted here; if this test changes, the document must change too.
 */
class AquaGoldenScenarioTest extends FinanceTestSupport {

    private String a(String path) { return "/api/v1/tenants/" + tenant + "/aquaculture" + path; }

    private String s(String path) { return "/api/v1/tenants/" + tenant + "/stock" + path; }

    private long item(String name, String category, int kg, int rate, String date) throws Exception {
        long id = body(post(s("/items"), owner, Map.of("name", name, "kind", "INPUT", "baseUnit", "kg", "expenseCategoryId", categoryId(category)))
                .andExpect(status().isCreated())).get("id").asLong();
        post(f("/bills"), owner, Map.of("docDate", date, "financialAccountId", cash, "lines", List.of(Map.of("itemId", id, "quantity", kg, "rate", rate))))
                .andExpect(status().isCreated());
        return id;
    }

    private void day(long cycle, String date, Map<String, Object> body) throws Exception {
        put(a("/cycles/" + cycle + "/days/" + date), owner, body).andExpect(status().isOk());
    }

    private static Map<String, Object> feed(long item, int kg) { return Map.of("itemId", item, "kg", kg); }

    private static Map<String, Object> death(String species, int count) { return Map.of("species", species, "count", count); }

    private static Map<String, Object> sold(String species, String grade, int kg, int count, int rate) {
        return Map.of("species", species, "grade", grade, "kg", kg, "count", count, "use", "SOLD", "rate", rate);
    }

    @Test
    void pondOneExampleMatchesTheAquacultureDesign() throws Exception {
        newFinanceTenant("aqua-golden");
        put("/api/v1/tenants/" + tenant + "/modules/AQUACULTURE", owner, Map.of("enabled", true)).andExpect(status().isOk());
        long farm = newFarm(owner, tenant, "Fish farm");

        // ---- Pond 1: an earthen pond with 1 acre of water spread ----
        JsonNode pond = body(post(a("/ponds"), owner, Map.of("name", "Pond 1", "farmId", farm, "kind", "EARTHEN", "waterArea", 1, "areaUnit", "ACRE"))
                .andExpect(status().isCreated()));
        long pondId = pond.get("id").asLong();
        money(pond.get("areaAcres"), "1");
        JsonNode cycle = body(post(a("/ponds/" + pondId + "/cycles"), owner, Map.of("name", "Pond 1 – 2026", "startedOn", "2026-01-05",
                "plannedHarvest", "2026-10-15")).andExpect(status().isCreated()));
        long c = cycle.get("id").asLong(), co = cycle.get("costObjectId").asLong();
        assertThat(cycle.get("status").asText()).isEqualTo("PREPARING");

        // ---- Preparation (January): lime from stock Rs 2,000 and a manure bill Rs 3,000 ----
        long lime = item("Lime", "OTHER_INPUTS", 200, 10, "2026-01-04");
        post(s("/issues"), owner, Map.of("docDate", "2026-01-06", "costObjectId", co, "lines", List.of(Map.of("itemId", lime, "quantity", 200, "unit", "kg"))))
                .andExpect(status().isCreated());
        invoice("bills", "2026-01-10", null, cash, line("OTHER_INPUTS", 3000, co));

        // ---- Stocking, 1 February: 4,000 fingerlings at Rs 2, 40 kg (K3, K4) ----
        for (Object[] st : new Object[][]{{"ROHU", 1600, 16, 3200}, {"CATLA", 1200, 12, 2400}, {"MRIGAL", 1200, 12, 2400}}) {
            post(a("/cycles/" + c + "/stockings"), owner, Map.of("date", "2026-02-01", "species", st[0], "count", st[1], "kg", st[2], "hatchery", "District hatchery",
                    "bill", Map.of("amount", st[3], "financialAccountId", cash))).andExpect(status().isCreated());
        }
        assertThat(body(get(a("/cycles/" + c), owner)).get("status").asText()).isEqualTo("GROWING");

        // ---- Growing: 6,000 kg of feed at Rs 30 recorded on the daily record; 200 deaths seen (K5) ----
        long floating = item("Floating feed", "ANIMAL_FEED", 6000, 30, "2026-01-31");
        day(c, "2026-02-28", Map.of("feeds", List.of(feed(floating, 300))));
        day(c, "2026-03-31", Map.of("feeds", List.of(feed(floating, 500)), "deaths", List.of(death("ROHU", 50))));
        day(c, "2026-04-30", Map.of("feeds", List.of(feed(floating, 700))));
        day(c, "2026-05-31", Map.of("feeds", List.of(feed(floating, 800))));
        day(c, "2026-06-30", Map.of("feeds", List.of(feed(floating, 900)), "deaths", List.of(death("CATLA", 50), death("MRIGAL", 50))));
        day(c, "2026-07-31", Map.of("feeds", List.of(feed(floating, 1000)), "notes", "Fish feeding well"));
        day(c, "2026-08-31", Map.of("feeds", List.of(feed(floating, 900))));
        day(c, "2026-09-29", Map.of("feeds", List.of(feed(floating, 900)), "deaths", List.of(death("ROHU", 50))));
        invoice("bills", "2026-06-30", null, cash, line("ELECTRICITY_WATER", 12000, co));
        invoice("bills", "2026-09-30", null, cash, line("LABOUR", 24000, co));

        // ---- Sample, 1 August: 50 fish weighed 30 kg (K6) ----
        JsonNode sample = body(post(a("/cycles/" + c + "/samples"), owner, Map.of("date", "2026-08-01", "fishWeighed", 50, "kg", 30)).andExpect(status().isCreated()));
        money(sample.get("averageGrams"), "600");
        JsonNode growing = body(get(a("/cycles/" + c + "/report?from=2026-07-01&to=2026-08-01"), owner).andExpect(status().isOk()));
        JsonNode est = growing.get("sinceStocking");
        money(est.get("estimatedKg"), "2310");
        money(est.get("estimatedFcr"), "1.85");
        assertThat(est.get("estimatedOn").asText()).isEqualTo("2026-08-01");
        JsonNode rohu = null;
        for (JsonNode n : growing.get("species")) if (n.get("species").asText().equals("ROHU")) rohu = n;
        assertThat(growing.get("species")).hasSize(3);
        assertThat(rohu.get("estimatedCount").asInt()).isEqualTo(1550);
        money(rohu.get("averageGrams"), "600");
        assertThat(est.get("survivalPercent").isNull()).isTrue();

        // ---- Water, 12 June 5 a.m.: DO 3.2 against the farmer's lowest of 4.0 (K7) ----
        put(a("/water-limits"), owner, Map.of("limits", List.of(Map.of("parameter", "DISSOLVED_OXYGEN", "lowest", 4.0)))).andExpect(status().isOk());
        JsonNode low = body(post(a("/ponds/" + pondId + "/readings"), owner, Map.of("takenAt", "2026-06-12T05:00:00+05:30", "dissolvedOxygen", 3.2, "ph", 7.8))
                .andExpect(status().isCreated()));
        assertThat(low.get("outOfLimits").toString()).isEqualTo("[\"DISSOLVED_OXYGEN\"]");

        // ---- Final harvest, 30 September: 3,000 kg, 3,200 fish, sold to a trader (K8) ----
        long trader = party("Fish trader");
        JsonNode harvest = body(post(a("/cycles/" + c + "/harvests"), owner, Map.of("date", "2026-09-30", "kind", "FINAL", "partyId", trader,
                "lines", List.of(sold("ROHU", "Above 1 kg", 1100, 1000, 140), sold("CATLA", "Above 1 kg", 1000, 900, 150),
                        sold("MRIGAL", "Above 750 g", 700, 800, 120), sold("ROHU", "Small", 200, 500, 80)))).andExpect(status().isCreated()));
        assertThat(harvest.get("saleDocumentId").isNull()).isFalse();
        money(body(get(f("/documents/" + harvest.get("saleDocumentId").asLong()), owner)).at("/summary/totalAmount"), "404000");

        JsonNode r = body(get(a("/cycles/" + c + "/report?from=2026-01-01&to=2026-09-30"), owner).andExpect(status().isOk()));
        assertThat(r.at("/cycle/status").asText()).isEqualTo("HARVESTED");
        JsonNode w = r.get("sinceStocking");
        assertThat(w.get("daysOfCulture").asInt()).isEqualTo(241);
        assertThat(w.get("stockedCount").asInt()).isEqualTo(4000);
        assertThat(w.get("deaths").asInt()).isEqualTo(200);
        assertThat(w.get("harvestedCount").asInt()).isEqualTo(3200);
        money(w.get("harvestedKg"), "3000");
        money(w.get("feedKg"), "6000");
        money(w.get("survivalPercent"), "80.0");
        assertThat(w.get("unseenLosses").asInt()).isEqualTo(600);
        money(w.get("fcr"), "2.03");
        money(w.get("yieldPerAcre"), "3000");
        money(w.get("costs"), "229000");
        money(w.get("revenue"), "404000");
        money(w.get("profit"), "175000");
        money(w.get("profitPerAcre"), "175000");
        money(w.get("costPerKg"), "76.33");
        money(w.get("averagePrice"), "134.67");
        assertThat(w.get("estimatedKg").isNull()).isTrue();

        // July alone is a loss; the whole-cycle column beside it is not (principle 4)
        JsonNode july = body(get(a("/cycles/" + c + "/report?from=2026-07-01&to=2026-07-31"), owner)).get("period");
        money(july.get("revenue"), "0");
        money(july.get("costs"), "30000");
        money(july.get("profit"), "-30000");

        // The pond is free for the next crop; the cycles report and its export list the crop
        assertThat(body(get(a("/ponds/" + pondId), owner)).get("openCycle").isNull()).isTrue();
        JsonNode cycles = body(get(a("/reports/cycles?from=2026-01-01&to=2026-09-30"), owner).andExpect(status().isOk()));
        assertThat(cycles).hasSize(1);
        get(a("/reports/cycles?from=2026-01-01&to=2026-09-30&format=csv&lang=en"), owner).andExpect(status().isOk());
        get(a("/readings?from=2026-06-01&to=2026-06-30&format=xlsx&lang=ta"), owner).andExpect(status().isOk());
        assertBooksBalance("2026-09-30");
    }
}
