package com.smartfarm360.aquaculture;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Aquaculture design, sections 3–9 and 12: ponds, cycles, estimated counts, feed, harvests, water limits, permissions and tenants. */
class AquaRulesTest extends FinanceTestSupport {
    private long farm;

    private String a(String path) { return "/api/v1/tenants/" + tenant + "/aquaculture" + path; }

    private String s(String path) { return "/api/v1/tenants/" + tenant + "/stock" + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("aq-" + UUID.randomUUID().toString().substring(0, 6));
        put("/api/v1/tenants/" + tenant + "/modules/AQUACULTURE", owner, Map.of("enabled", true)).andExpect(status().isOk());
        farm = newFarm(owner, tenant, "Fish farm");
    }

    private long pond(String name) throws Exception {
        return body(post(a("/ponds"), owner, Map.of("name", name, "farmId", farm, "kind", "EARTHEN", "waterArea", 50, "areaUnit", "CENT"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    /** A pond with a growing cycle of 1,000 rohu stocked on 1 June 2026, without a bill. */
    private long growing(String pond) throws Exception {
        long p = pond(pond);
        long c = body(post(a("/ponds/" + p + "/cycles"), owner, Map.of("startedOn", "2026-05-20")).andExpect(status().isCreated())).get("id").asLong();
        post(a("/cycles/" + c + "/stockings"), owner, Map.of("date", "2026-06-01", "species", "ROHU", "count", 1000, "kg", 10)).andExpect(status().isCreated());
        return c;
    }

    private long feedItem(int kg) throws Exception {
        long id = body(post(s("/items"), owner, Map.of("name", "Feed " + UUID.randomUUID().toString().substring(0, 4), "kind", "INPUT", "baseUnit", "kg",
                "expenseCategoryId", categoryId("ANIMAL_FEED"))).andExpect(status().isCreated())).get("id").asLong();
        post(f("/bills"), owner, Map.of("docDate", "2026-05-01", "financialAccountId", cash, "lines", List.of(Map.of("itemId", id, "quantity", kg, "rate", 30))))
                .andExpect(status().isCreated());
        return id;
    }

    private static Map<String, Object> sold(String species, int kg, Integer count, int rate) {
        Map<String, Object> m = new HashMap<>(Map.of("species", species, "kg", kg, "use", "SOLD", "rate", rate));
        if (count != null) m.put("count", count);
        return m;
    }

    @Test
    void pondsAreConvertedToAcresAndHoldOneOpenCycle() throws Exception {
        long p = pond("Pond A");
        money(body(get(a("/ponds/" + p), owner)).get("areaAcres"), "0.5");
        post(a("/ponds"), owner, Map.of("name", "pond a", "farmId", farm, "kind", "TANK", "waterArea", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POND_NAME_TAKEN"));
        JsonNode c = body(post(a("/ponds/" + p + "/cycles"), owner, Map.of("startedOn", "2026-05-20")).andExpect(status().isCreated()));
        assertThat(c.get("name").asText()).isEqualTo("Pond A – 2026");
        // Cycles are cost objects that bills and stock uses can be assigned to (K2, K10)
        assertThat(body(get(f("/cost-objects"), owner)).findValuesAsText("kind")).contains("POND_CYCLE");
        post(a("/ponds/" + p + "/cycles"), owner, Map.of("startedOn", "2026-06-01")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CYCLE_OPEN"));
        put(a("/ponds/" + p), owner, Map.of("name", "Pond A", "farmId", farm, "kind", "EARTHEN", "waterArea", 50, "areaUnit", "CENT", "active", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POND_HAS_OPEN_CYCLE"));
    }

    @Test
    void deathsNeverExceedTheFishStockedAndOnlyStockedSpeciesCount() throws Exception {
        long c = growing("Pond B");
        put(a("/cycles/" + c + "/days/2026-06-10"), owner, Map.of("deaths", List.of(Map.of("species", "ROHU", "count", 1001))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEATHS_EXCEED_STOCK"));
        put(a("/cycles/" + c + "/days/2026-06-10"), owner, Map.of("deaths", List.of(Map.of("species", "CATLA", "count", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPECIES_NOT_STOCKED"));
        put(a("/cycles/" + c + "/days/2026-05-25"), owner, Map.of("deaths", List.of(Map.of("species", "ROHU", "count", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTHING_STOCKED"));
        put(a("/cycles/" + c + "/days/2026-06-10"), owner, Map.of("deaths", List.of(Map.of("species", "ROHU", "count", 40, "cause", "Birds"))))
                .andExpect(status().isOk());
        JsonNode r = body(get(a("/cycles/" + c + "/report?from=2026-06-01&to=2026-06-30"), owner));
        assertThat(r.get("species").get(0).get("estimatedCount").asInt()).isEqualTo(960);
        // A species named Other needs its name
        post(a("/cycles/" + c + "/stockings"), owner, Map.of("date", "2026-06-02", "species", "OTHER", "count", 10, "kg", 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPECIES_NAME_REQUIRED"));
    }

    @Test
    void changingTheDaysFeedReversesTheEarlierStockUse() throws Exception {
        long c = growing("Pond C");
        long feed = feedItem(500);
        JsonNode first = body(put(a("/cycles/" + c + "/days/2026-06-05"), owner, Map.of("feeds", List.of(Map.of("itemId", feed, "kg", 100)))).andExpect(status().isOk()));
        long doc = first.get("feedDocumentId").asLong();
        money(body(get(s("/items/" + feed), owner)).get("quantity"), "400");
        // Saving the same feed again changes nothing
        assertThat(body(put(a("/cycles/" + c + "/days/2026-06-05"), owner, Map.of("feeds", List.of(Map.of("itemId", feed, "kg", 100)), "notes", "Rain")))
                .get("feedDocumentId").asLong()).isEqualTo(doc);
        JsonNode second = body(put(a("/cycles/" + c + "/days/2026-06-05"), owner, Map.of("feeds", List.of(Map.of("itemId", feed, "kg", 80)))).andExpect(status().isOk()));
        assertThat(second.get("feedDocumentId").asLong()).isNotEqualTo(doc);
        assertThat(body(get(f("/documents/" + doc), owner)).at("/summary/status").asText()).isEqualTo("REVERSED");
        money(body(get(s("/items/" + feed), owner)).get("quantity"), "420");
        JsonNode cleared = body(put(a("/cycles/" + c + "/days/2026-06-05"), owner, Map.of("feeds", List.of())).andExpect(status().isOk()));
        assertThat(cleared.get("feedDocumentId").isNull()).isTrue();
        money(body(get(s("/items/" + feed), owner)).get("quantity"), "500");
    }

    @Test
    void aHarvestedCycleRefusesRecordsAndReopensWhenItsSaleIsReversed() throws Exception {
        long c = growing("Pond D");
        long buyer = party("Pond-bank trader");
        JsonNode h = body(post(a("/cycles/" + c + "/harvests"), owner, Map.of("date", "2026-09-01", "kind", "FINAL", "partyId", buyer,
                "lines", List.of(sold("ROHU", 800, 900, 120)), "charges", List.of(Map.of("category", "MARKET_CHARGES", "amount", 2000))))
                .andExpect(status().isCreated()));
        assertThat(body(get(a("/cycles/" + c), owner)).get("status").asText()).isEqualTo("HARVESTED");
        // The buyer's charges are a cost of the cycle, so the net is what was received
        JsonNode r = body(get(a("/cycles/" + c + "/report?from=2026-05-01&to=2026-09-30"), owner)).get("sinceStocking");
        money(r.get("revenue"), "96000");
        money(r.get("costs"), "2000");
        money(r.get("survivalPercent"), "90.0");
        assertThat(r.get("unseenLosses").asInt()).isEqualTo(100);
        post(a("/cycles/" + c + "/stockings"), owner, Map.of("date", "2026-09-02", "species", "ROHU", "count", 10, "kg", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CYCLE_HARVESTED"));
        put(a("/cycles/" + c + "/days/2026-09-02"), owner, Map.of("notes", "Empty")).andExpect(status().isConflict());
        delete(a("/harvests/" + h.get("id").asLong()), owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HARVEST_HAS_SALE"));
        // Reversing the sale reopens the cycle; then the harvest record can go
        post(f("/documents/" + h.get("saleDocumentId").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        assertThat(body(get(a("/cycles/" + c), owner)).get("status").asText()).isEqualTo("GROWING");
        delete(a("/harvests/" + h.get("id").asLong()), owner).andExpect(status().isNoContent());
    }

    @Test
    void survivalIsUnknownWhenHarvestLinesAreNotCounted() throws Exception {
        long c = growing("Pond E");
        long feed = feedItem(1000);
        put(a("/cycles/" + c + "/days/2026-07-01"), owner, Map.of("feeds", List.of(Map.of("itemId", feed, "kg", 900)))).andExpect(status().isOk());
        post(a("/cycles/" + c + "/harvests"), owner, Map.of("date", "2026-08-01", "kind", "PARTIAL", "financialAccountId", cash,
                "lines", List.of(sold("ROHU", 200, null, 130)))).andExpect(status().isCreated());
        post(a("/cycles/" + c + "/harvests"), owner, Map.of("date", "2026-09-01", "kind", "FINAL",
                "lines", List.of(Map.of("species", "ROHU", "kg", 410, "count", 500, "use", "HOME")))).andExpect(status().isCreated());
        JsonNode w = body(get(a("/cycles/" + c + "/report?from=2026-05-01&to=2026-09-30"), owner)).get("sinceStocking");
        assertThat(w.get("survivalPercent").isNull()).isTrue();
        assertThat(w.get("harvestedCount").isNull()).isTrue();
        money(w.get("harvestedKg"), "610");
        money(w.get("fcr"), "1.50");                  // 900 kg feed ÷ (610 − 10) kg gained
        money(w.get("yieldPerAcre"), "1220");         // 610 kg on half an acre
        money(w.get("averagePrice"), "130");
        // Without fish sold, charges cannot be taken off
        long c2 = growing("Pond F");
        post(a("/cycles/" + c2 + "/harvests"), owner, Map.of("date", "2026-08-01", "kind", "PARTIAL", "lines", List.of(Map.of("species", "ROHU", "kg", 5, "use", "GIVEN")),
                "charges", List.of(Map.of("category", "TRANSPORT", "amount", 100)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGES_NEED_SALE"));
    }

    @Test
    void waterReadingsAreComparedWithPondOrFarmBusinessLimits() throws Exception {
        long p1 = pond("Pond G"), p2 = pond("Pond H");
        put(a("/water-limits"), owner, Map.of("limits", List.of(Map.of("parameter", "DISSOLVED_OXYGEN", "lowest", 4), Map.of("parameter", "PH", "lowest", 6.5, "highest", 8.5))))
                .andExpect(status().isOk());
        put(a("/water-limits"), owner, Map.of("pondId", p2, "limits", List.of(Map.of("parameter", "DISSOLVED_OXYGEN", "lowest", 3)))).andExpect(status().isOk());
        put(a("/water-limits"), owner, Map.of("limits", List.of(Map.of("parameter", "PH", "lowest", 9, "highest", 8)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        String now = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0).toString();
        JsonNode r1 = body(post(a("/ponds/" + p1 + "/readings"), owner, Map.of("takenAt", now, "dissolvedOxygen", 3.5, "ph", 9.1)).andExpect(status().isCreated()));
        JsonNode r2 = body(post(a("/ponds/" + p2 + "/readings"), owner, Map.of("takenAt", now, "dissolvedOxygen", 3.5, "ph", 7.2)).andExpect(status().isCreated()));
        assertThat(r1.get("outOfLimits").toString()).isEqualTo("[\"DISSOLVED_OXYGEN\",\"PH\"]");
        assertThat(r2.get("outOfLimits")).isEmpty();
        post(a("/ponds/" + p1 + "/readings"), owner, Map.of("takenAt", now)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("READING_EMPTY"));
        assertThat(body(get(a("/ponds/" + p1), owner)).at("/latestReading/id").asLong()).isEqualTo(r1.get("id").asLong());
        JsonNode dash = body(get("/api/v1/tenants/" + tenant + "/dashboard", owner));
        assertThat(dash.toString()).contains("WATER_OUT_OF_LIMITS");
    }

    @Test
    void permissionsAndTheModuleGateFishFarming() throws Exception {
        long c = growing("Pond I");
        User viewer = newUser("aq-viewer"), worker = newUser("aq-worker");
        addMember(owner, tenant, viewer, "VIEWER");
        addMember(owner, tenant, worker, "WORKER");
        get(a("/cycles/" + c), viewer).andExpect(status().isOk());
        put(a("/cycles/" + c + "/days/2026-06-03"), viewer, Map.of("notes", "x")).andExpect(status().isForbidden());
        put(a("/cycles/" + c + "/days/2026-06-03"), worker, Map.of("notes", "Fed by hand")).andExpect(status().isOk());
        post(a("/cycles/" + c + "/stockings"), worker, Map.of("date", "2026-06-03", "species", "ROHU", "count", 10, "kg", 1,
                "bill", Map.of("amount", 20, "financialAccountId", cash))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINANCE_PERMISSION_REQUIRED"));
        // Money in figures needs finance access
        JsonNode w = body(get(a("/cycles/" + c + "/report?from=2026-06-01&to=2026-06-30"), worker)).get("period");
        assertThat(w.get("costs").isNull()).isTrue();
        put("/api/v1/tenants/" + tenant + "/modules/AQUACULTURE", owner, Map.of("enabled", false)).andExpect(status().isOk());
        get(a("/ponds"), owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }

    @Test
    void anotherFarmBusinesssPondsAndCyclesCannotBeReachedOrReferenced() throws Exception {
        long mine = growing("Mine");
        long myPond = body(get(a("/cycles/" + mine), owner)).get("pondId").asLong();
        long otherTenant = body(post("/api/v1/tenants", owner, Map.of("name", "Other fish farm", "modules", List.of("AQUACULTURE"))).andExpect(status().isCreated()))
                .get("id").asLong();
        long otherFarm = newFarm(owner, otherTenant, "Their farm");
        String o = "/api/v1/tenants/" + otherTenant + "/aquaculture";
        long theirPond = body(post(o + "/ponds", owner, Map.of("name", "Theirs", "farmId", otherFarm, "kind", "TANK", "waterArea", 1)).andExpect(status().isCreated()))
                .get("id").asLong();
        long theirCycle = body(post(o + "/ponds/" + theirPond + "/cycles", owner, Map.of("startedOn", "2026-05-20")).andExpect(status().isCreated())).get("id").asLong();
        long theirStocking = body(post(o + "/cycles/" + theirCycle + "/stockings", owner, Map.of("date", "2026-06-01", "species", "TILAPIA", "count", 100, "kg", 2))
                .andExpect(status().isCreated())).get("id").asLong();
        long theirSample = body(post(o + "/cycles/" + theirCycle + "/samples", owner, Map.of("date", "2026-06-10", "fishWeighed", 10, "kg", 1))
                .andExpect(status().isCreated())).get("id").asLong();
        long theirReading = body(post(o + "/ponds/" + theirPond + "/readings", owner, Map.of("takenAt", "2026-06-10T06:00:00+05:30", "ph", 7))
                .andExpect(status().isCreated())).get("id").asLong();
        long theirHarvest = body(post(o + "/cycles/" + theirCycle + "/harvests", owner, Map.of("date", "2026-06-20", "kind", "PARTIAL",
                "lines", List.of(Map.of("species", "TILAPIA", "kg", 1, "use", "HOME"))))).get("id").asLong();
        long theirFeed;
        {
            theirFeed = body(post("/api/v1/tenants/" + otherTenant + "/stock/items", owner, Map.of("name", "Their feed", "kind", "INPUT", "baseUnit", "kg",
                    "expenseCategoryId", categoryId("ANIMAL_FEED"))).andExpect(status().isCreated())).get("id").asLong();
        }

        // Their ids inside my farm business are not found
        get(a("/ponds/" + theirPond), owner).andExpect(status().isNotFound());
        put(a("/ponds/" + theirPond), owner, Map.of("name", "X", "farmId", farm, "kind", "TANK", "waterArea", 1)).andExpect(status().isNotFound());
        post(a("/ponds/" + theirPond + "/cycles"), owner, Map.of("startedOn", "2026-06-01")).andExpect(status().isNotFound());
        get(a("/cycles/" + theirCycle), owner).andExpect(status().isNotFound());
        get(a("/cycles/" + theirCycle + "/report?from=2026-06-01&to=2026-06-30"), owner).andExpect(status().isNotFound());
        get(a("/cycles/" + theirCycle + "/stockings"), owner).andExpect(status().isNotFound());
        put(a("/cycles/" + theirCycle + "/days/2026-06-05"), owner, Map.of("notes", "x")).andExpect(status().isNotFound());
        post(a("/cycles/" + theirCycle + "/samples"), owner, Map.of("date", "2026-06-10", "fishWeighed", 1, "kg", 1)).andExpect(status().isNotFound());
        post(a("/cycles/" + theirCycle + "/harvests"), owner, Map.of("date", "2026-06-20", "kind", "PARTIAL",
                "lines", List.of(Map.of("species", "TILAPIA", "kg", 1, "use", "HOME")))).andExpect(status().isNotFound());
        delete(a("/stockings/" + theirStocking), owner).andExpect(status().isNotFound());
        delete(a("/samples/" + theirSample), owner).andExpect(status().isNotFound());
        delete(a("/readings/" + theirReading), owner).andExpect(status().isNotFound());
        delete(a("/harvests/" + theirHarvest), owner).andExpect(status().isNotFound());
        post(a("/ponds/" + theirPond + "/readings"), owner, Map.of("takenAt", "2026-06-10T06:00:00+05:30", "ph", 7)).andExpect(status().isNotFound());
        get(a("/readings?pondId=" + theirPond + "&from=2026-06-01&to=2026-06-30"), owner).andExpect(status().isNotFound());
        put(a("/water-limits"), owner, Map.of("pondId", theirPond, "limits", List.of(Map.of("parameter", "PH", "lowest", 6)))).andExpect(status().isNotFound());
        // ... and cannot be referenced from my records
        post(a("/ponds"), owner, Map.of("name", "Borrowed", "farmId", otherFarm, "kind", "TANK", "waterArea", 1)).andExpect(status().is4xxClientError());
        put(a("/cycles/" + mine + "/days/2026-06-05"), owner, Map.of("feeds", List.of(Map.of("itemId", theirFeed, "kg", 1)))).andExpect(status().is4xxClientError());
        // Nothing of theirs changed, and my lists hold only my ponds
        assertThat(body(get(o + "/cycles/" + theirCycle + "/stockings", owner))).hasSize(1);
        assertThat(body(get(o + "/readings?from=2026-06-01&to=2026-06-30", owner))).hasSize(1);
        JsonNode myPonds = body(get(a("/ponds"), owner));
        assertThat(myPonds).hasSize(1);
        assertThat(myPonds.get(0).get("name").asText()).isEqualTo("Mine");
        assertThat(body(get(a("/cycles"), owner)).findValuesAsText("pondId")).containsOnly(String.valueOf(myPond));
    }
}
