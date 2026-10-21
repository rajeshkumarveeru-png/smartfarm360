package com.smartfarm360.livestock;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Poultry, goats and sheep design, sections 3–9 and 12: head counts, daily records, eggs, feed, closing, plans, modules. */
class FlockRulesTest extends FinanceTestSupport {
    private String l(String path) { return "/api/v1/tenants/" + tenant + "/livestock" + path; }

    private String s(String path) { return "/api/v1/tenants/" + tenant + "/stock" + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("fl-" + UUID.randomUUID().toString().substring(0, 6));
        put("/api/v1/tenants/" + tenant + "/modules/POULTRY", owner, Map.of("enabled", true)).andExpect(status().isOk());
        put("/api/v1/tenants/" + tenant + "/modules/GOAT_SHEEP", owner, Map.of("enabled", true)).andExpect(status().isOk());
    }

    private long batch(String name, String purpose, int heads, String placed) throws Exception {
        return body(post(l("/flocks"), owner, Map.of("name", name, "species", "CHICKEN", "purpose", purpose, "placedOn", placed, "heads", heads))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private int heads(long group) throws Exception {
        return body(get(l("/flocks"), owner)).findValues("id").stream().anyMatch(n -> n.asLong() == group)
                ? find(body(get(l("/flocks"), owner)), group).get("animals").asInt() : -1;
    }

    private static JsonNode find(JsonNode array, long id) {
        for (JsonNode n : array) if (n.get("id").asLong() == id) return n;
        throw new AssertionError("no group " + id);
    }

    private long feedItem(int kg) throws Exception {
        long id = body(post(s("/items"), owner, Map.of("name", "Mash " + UUID.randomUUID().toString().substring(0, 4), "kind", "INPUT", "baseUnit", "kg",
                "expenseCategoryId", categoryId("ANIMAL_FEED"))).andExpect(status().isCreated())).get("id").asLong();
        post(f("/bills"), owner, Map.of("docDate", "2026-06-01", "financialAccountId", cash, "lines", List.of(Map.of("itemId", id, "quantity", kg, "rate", 30))))
                .andExpect(status().isCreated());
        return id;
    }

    @Test
    void headCountsChangeOnlyByMovementsAndNeverGoBelowZero() throws Exception {
        long b = batch("Country birds", "COUNTRY", 100, "2026-06-01");
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-05", "kind", "DIED", "heads", 101)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEADS_NEGATIVE"));
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-05-30", "kind", "DIED", "heads", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_PLACEMENT"));
        // A count check records the difference with a reason (J7)
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-10", "kind", "COUNT", "countedHeads", 95)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        JsonNode count = body(post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-10", "kind", "COUNT", "countedHeads", 95, "cause", "Predator"))
                .andExpect(status().isCreated()));
        assertThat(count.at("/0/kind").asText()).isEqualTo("COUNT_LOSS");
        assertThat(count.at("/0/heads").asInt()).isEqualTo(5);
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-10", "kind", "COUNT", "countedHeads", 95, "cause", "Again"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COUNT_MATCHES"));
        // A back-dated death that would leave a later day negative is refused too
        long other = batch("Second", "COUNTRY", 10, "2026-06-01");
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-12", "kind", "MOVED", "heads", 90, "toGroupId", other)).andExpect(status().isCreated());
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-11", "kind", "DIED", "heads", 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEADS_NEGATIVE"));
        assertThat(heads(b)).isEqualTo(5);
        assertThat(heads(other)).isEqualTo(100);
        long goats = body(post(l("/flocks"), owner, Map.of("name", "Goats", "species", "GOAT", "purpose", "MIXED", "placedOn", "2026-06-01", "heads", 5)))
                .get("id").asLong();
        post(l("/flocks/" + b + "/movements"), owner, Map.of("date", "2026-06-13", "kind", "MOVED", "heads", 1, "toGroupId", goats)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPECIES_MISMATCH"));
    }

    @Test
    void eggsFollowTheDailyRecordUntilUsedAndBrokenEggsAreNotStocked() throws Exception {
        long b = batch("Layers", "LAYER", 100, "2026-06-01");
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("eggsGood", 80, "eggsCracked", 5, "eggsBroken", 3)).andExpect(status().isOk());
        long eggs = jdbc.queryForObject("select egg_item_id from sf_livestock_settings where tenant_id = ?", Long.class, tenant);
        money(body(get(s("/items/" + eggs), owner)).get("quantity"), "85");
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("eggsGood", 70, "eggsCracked", 5, "eggsBroken", 3)).andExpect(status().isOk());
        money(body(get(s("/items/" + eggs), owner)).get("quantity"), "75");
        post(l("/egg-sales"), owner, Map.of("date", "2026-07-01", "groupId", b, "quantity", 2, "unit", "tray", "amount", 300, "financialAccountId", cash))
                .andExpect(status().isCreated());
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("eggsGood", 10, "eggsCracked", 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EGGS_ALREADY_USED"));
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("eggsGood", 72, "eggsCracked", 5, "eggsBroken", 3)).andExpect(status().isOk());
        money(body(get(s("/items/" + eggs), owner)).get("quantity"), "17");
        post(l("/egg-uses"), owner, Map.of("date", "2026-07-01", "damaged", 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        // Broilers do not lay; goat herds have no daily record
        long br = batch("Broilers", "BROILER", 50, "2026-06-01");
        put(l("/flocks/" + br + "/days/2026-07-01"), owner, Map.of("eggsGood", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_EGGS_FOR_BROILERS"));
        long goats = body(post(l("/flocks"), owner, Map.of("name", "Goats", "species", "GOAT", "purpose", "MIXED", "placedOn", "2026-06-01", "heads", 5)))
                .get("id").asLong();
        put(l("/flocks/" + goats + "/days/2026-07-01"), owner, Map.of("deaths", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_BATCH"));
    }

    @Test
    void dailyFeedIsOneStockUseAndAChangeReversesTheEarlierOne() throws Exception {
        long b = batch("Layers", "LAYER", 100, "2026-06-01");
        long mash = feedItem(100);
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("feedItemId", mash, "feedKg", 10)).andExpect(status().isOk());
        money(body(get(s("/items/" + mash), owner)).get("quantity"), "90");
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("feedItemId", mash, "feedKg", 12, "deaths", 2)).andExpect(status().isOk());
        money(body(get(s("/items/" + mash), owner)).get("quantity"), "88");
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("deaths", 1)).andExpect(status().isOk());
        money(body(get(s("/items/" + mash), owner)).get("quantity"), "100");
        assertThat(heads(b)).isEqualTo(99);   // the day's deaths are replaced, not added
        JsonNode r = body(get(l("/flocks/" + b + "/report?from=2026-07-01&to=2026-07-01"), owner));
        money(r.at("/period/feedKg"), "0");
        put(l("/flocks/" + b + "/days/2026-07-02"), owner, Map.of("feedItemId", mash)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FEED_ITEM_AND_KG"));
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void anEmptyBatchClosesAndReversingItsSaleReopensIt() throws Exception {
        long b = batch("Broilers", "BROILER", 50, "2026-06-01");
        long sale = body(post(l("/flocks/" + b + "/sales"), owner, Map.of("date", "2026-07-10", "heads", 50, "kg", 110, "rateBasis", "KG", "rate", 100,
                "financialAccountId", cash)).andExpect(status().isCreated())).get("documentId").asLong();
        assertThat(find(body(get(l("/flocks"), owner)), b).get("closed").asBoolean()).isTrue();
        post(l("/flocks/" + b + "/purchases"), owner, Map.of("date", "2026-07-11", "heads", 10, "bill", Map.of("amount", 400, "financialAccountId", cash)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_CLOSED"));
        money(report("income-statement?from=2026-07-01&to=2026-07-31").get("operatingRevenueTotal"), "11000");
        post(f("/documents/" + sale + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        JsonNode g = find(body(get(l("/flocks"), owner)), b);
        assertThat(g.get("closed").asBoolean()).isFalse();
        assertThat(g.get("animals").asInt()).isEqualTo(50);
        post(l("/flocks/" + b + "/sales"), owner, Map.of("date", "2026-07-12", "heads", 10, "rateBasis", "KG", "rate", 100, "financialAccountId", cash))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEIGHT_REQUIRED"));
    }

    @Test
    void healthPlansAreCopiedWithShiftedDatesAndMarkedDone() throws Exception {
        long first = batch("Batch 1", "BROILER", 100, "2026-05-01");
        post(l("/health"), owner, Map.of("groupId", first, "kind", "VACCINATION", "date", "2026-05-08", "description", "Vaccine A")).andExpect(status().isCreated());
        long second = batch("Batch 2", "BROILER", 100, LocalDate.now().minusDays(3).toString());
        JsonNode plan = body(post(l("/flocks/" + second + "/plan/copy"), owner, Map.of("fromGroupId", first)).andExpect(status().isCreated()));
        assertThat(plan).hasSize(1);
        assertThat(plan.at("/0/dueOn").asText()).isEqualTo(LocalDate.now().plusDays(4).toString());   // 7 days after placement
        JsonNode dash = body(get("/api/v1/tenants/" + tenant + "/dashboard", owner));
        assertThat(dash.toString()).contains("HEALTH_DUE");
        post(l("/health"), owner, Map.of("groupId", second, "kind", "VACCINATION", "date", LocalDate.now().toString(), "description", "Vaccine A",
                "planItemId", plan.at("/0/id").asLong())).andExpect(status().isCreated());
        assertThat(body(get(l("/flocks/" + second + "/plan"), owner)).at("/0/doneEventId").isNull()).isFalse();
        long goats = body(post(l("/flocks"), owner, Map.of("name", "Goats", "species", "GOAT", "purpose", "MIXED", "placedOn", "2026-06-01", "heads", 5)))
                .get("id").asLong();
        post(l("/flocks/" + goats + "/plan/copy"), owner, Map.of("fromGroupId", first)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPECIES_MISMATCH"));
    }

    @Test
    void goatsKidInTheHerdAndTaggedDoesGetA150DayDate() throws Exception {
        long herd = body(post(l("/flocks"), owner, Map.of("name", "Goats", "species", "GOAT", "purpose", "BREEDING_HERD", "placedOn", "2026-01-01", "heads", 10)))
                .get("id").asLong();
        long doe = body(post(l("/animals"), owner, Map.of("tag", "D-1", "species", "GOAT", "sex", "FEMALE", "origin", "BORN", "arrivedOn", "2024-01-01",
                "groupId", herd)).andExpect(status().isCreated())).get("id").asLong();
        JsonNode mating = body(post(l("/animals/" + doe + "/breeding"), owner, Map.of("kind", "INSEMINATION", "date", "2026-02-01", "service", "NATURAL", "bull", "Buck"))
                .andExpect(status().isCreated()));
        assertThat(mating.get("expectedCalving").asText()).isEqualTo("2026-07-01");
        assertThat(mating.get("checkDueFrom").isNull()).isTrue();
        assertThat(mating.get("dryOffDue").isNull()).isTrue();
        JsonNode kidding = body(post(l("/flocks/" + herd + "/births"), owner, Map.of("date", "2026-06-30", "alive", 2, "dead", 1, "damId", doe))
                .andExpect(status().isCreated()));
        assertThat(kidding.get("kind").asText()).isEqualTo("KIDDING");
        assertThat(heads(herd)).isEqualTo(12);
        assertThat(body(get(l("/animals/" + doe), owner)).at("/animal/breeding/repro").asText()).isEqualTo("INSEMINATED");
        long layers = batch("Layers", "LAYER", 10, "2026-06-01");
        post(l("/flocks/" + layers + "/births"), owner, Map.of("date", "2026-06-30", "alive", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_FOR_POULTRY"));
    }

    @Test
    void withdrawalWarnsOnTheDailyRecordAndHighMortalityIsFlagged() throws Exception {
        long b = batch("Layers", "LAYER", 100, LocalDate.now().minusDays(10).toString());
        post(l("/health"), owner, Map.of("groupId", b, "kind", "TREATMENT", "date", LocalDate.now().minusDays(1).toString(), "description", "Antibiotic",
                "withdrawalDays", 5)).andExpect(status().isCreated());
        JsonNode day = body(put(l("/flocks/" + b + "/days/" + LocalDate.now()), owner, Map.of("deaths", 3, "eggsGood", 50)).andExpect(status().isOk()));
        assertThat(day.get("warnings").toString()).contains("EGGS_WITHHELD").contains("HIGH_MORTALITY");
        assertThat(body(get("/api/v1/tenants/" + tenant + "/dashboard", owner)).toString()).contains("HIGH_MORTALITY");
    }

    @Test
    void modulesAndPermissionsGateBatchesAndHerds() throws Exception {
        long b = batch("Layers", "LAYER", 10, "2026-06-01");
        User viewer = newUser("fl-viewer"), worker = newUser("fl-worker");
        addMember(owner, tenant, viewer, "VIEWER");
        addMember(owner, tenant, worker, "WORKER");
        put(l("/flocks/" + b + "/days/2026-07-01"), viewer, Map.of("eggsGood", 5)).andExpect(status().isForbidden());
        put(l("/flocks/" + b + "/days/2026-07-01"), worker, Map.of("eggsGood", 5)).andExpect(status().isOk());
        post(l("/flocks/" + b + "/sales"), worker, Map.of("date", "2026-07-02", "heads", 1, "rateBasis", "HEAD", "rate", 300, "financialAccountId", cash))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCE_PERMISSION_REQUIRED"));
        // Poultry off: batches are refused, but the module of other species still works
        put("/api/v1/tenants/" + tenant + "/modules/POULTRY", owner, Map.of("enabled", false)).andExpect(status().isOk());
        put(l("/flocks/" + b + "/days/2026-07-01"), owner, Map.of("eggsGood", 5)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        post(l("/flocks"), owner, Map.of("name", "More", "species", "CHICKEN", "purpose", "LAYER", "placedOn", "2026-06-01", "heads", 5))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        post(l("/flocks"), owner, Map.of("name", "Sheep", "species", "SHEEP", "purpose", "FATTENING", "placedOn", "2026-06-01", "heads", 5))
                .andExpect(status().isCreated());
        // A farm with only poultry cannot use the dairy-only endpoints
        long poultryOnly = body(post("/api/v1/tenants", owner, Map.of("name", "Birds only", "modules", List.of("POULTRY"))).andExpect(status().isCreated())).get("id").asLong();
        get("/api/v1/tenants/" + poultryOnly + "/livestock/groups", owner).andExpect(status().isOk());
        get("/api/v1/tenants/" + poultryOnly + "/livestock/milk/2026-07-01", owner).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        post("/api/v1/tenants/" + poultryOnly + "/livestock/groups", owner, Map.of("name", "Cows", "purpose", "MILKING"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }
    @Test
    void anotherFarmBusinesssBatchesCannotBeReachedOrReferenced() throws Exception {
        long mine = batch("Mine", "LAYER", 100, "2026-06-01");
        long otherTenant = body(post("/api/v1/tenants", owner, Map.of("name", "Other farm", "modules", List.of("POULTRY"))).andExpect(status().isCreated())).get("id").asLong();
        String o = "/api/v1/tenants/" + otherTenant + "/livestock";
        long theirs = body(post(o + "/flocks", owner, Map.of("name", "Theirs", "species", "CHICKEN", "purpose", "LAYER", "placedOn", "2026-06-01", "heads", 50))
                .andExpect(status().isCreated())).get("id").asLong();
        post(o + "/flocks/" + theirs + "/plan", owner, Map.of("kind", "VACCINATION", "description", "ND", "dueOn", "2026-06-08")).andExpect(status().isCreated());
        post(o + "/flocks/" + theirs + "/movements", owner, Map.of("date", "2026-06-02", "kind", "CULLED", "heads", 1)).andExpect(status().isCreated());
        long theirMove = body(get(o + "/flocks/" + theirs + "/movements", owner)).get(0).get("id").asLong();

        // Their ids inside my farm business are not found
        get(l("/flocks/" + theirs + "/report?from=2026-06-01&to=2026-06-30"), owner).andExpect(status().isNotFound());
        get(l("/flocks/" + theirs + "/movements"), owner).andExpect(status().isNotFound());
        get(l("/flocks/" + theirs + "/days/2026-06-02"), owner).andExpect(status().isNotFound());
        put(l("/flocks/" + theirs + "/days/2026-06-02"), owner, Map.of("deaths", 1)).andExpect(status().isNotFound());
        get(l("/flocks/" + theirs + "/plan"), owner).andExpect(status().isNotFound());
        post(l("/flocks/" + theirs + "/movements"), owner, Map.of("date", "2026-06-02", "kind", "DIED", "heads", 1)).andExpect(status().isNotFound());
        delete(l("/flock-movements/" + theirMove), owner).andExpect(status().isNotFound());
        // ... and cannot be referenced from my records
        post(l("/flocks/" + mine + "/movements"), owner, Map.of("date", "2026-06-02", "kind", "MOVED", "heads", 5, "toGroupId", theirs)).andExpect(status().isNotFound());
        post(l("/flocks/" + mine + "/plan/copy"), owner, Map.of("fromGroupId", theirs)).andExpect(status().isNotFound());
        post(l("/egg-sales"), owner, Map.of("date", "2026-06-02", "groupId", theirs, "quantity", 1, "unit", "tray", "amount", 150, "financialAccountId", cash))
                .andExpect(status().isNotFound());
        // Nothing of theirs changed, and my lists hold only my batches
        assertThat(body(get(o + "/flocks/" + theirs + "/movements", owner))).hasSize(2);
        assertThat(body(get(o + "/flocks/" + theirs + "/plan", owner))).hasSize(1);
        assertThat(body(get(l("/flocks"), owner)).findValuesAsText("name")).containsExactly("Mine");
        assertThat(heads(mine)).isEqualTo(100);
    }
}
