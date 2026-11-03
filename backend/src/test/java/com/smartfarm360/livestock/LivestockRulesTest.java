package com.smartfarm360.livestock;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Livestock design, sections 3–6 and 10: milk in stock, breeding dates, calving and lactations, moves, exits, permissions. */
class LivestockRulesTest extends FinanceTestSupport {
    private long milking, dry, calves, milkingCo, dryCo;

    private String l(String path) { return "/api/v1/tenants/" + tenant + "/livestock" + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("ls-" + UUID.randomUUID().toString().substring(0, 6));
        JsonNode m = body(post(l("/groups"), owner, Map.of("name", "Milking herd", "purpose", "MILKING")).andExpect(status().isCreated()));
        JsonNode d = body(post(l("/groups"), owner, Map.of("name", "Dry cows", "purpose", "DRY")).andExpect(status().isCreated()));
        milking = m.get("id").asLong();
        milkingCo = m.get("costObjectId").asLong();
        dry = d.get("id").asLong();
        dryCo = d.get("costObjectId").asLong();
        calves = body(post(l("/groups"), owner, Map.of("name", "Calves", "purpose", "CALF")).andExpect(status().isCreated())).get("id").asLong();
    }

    private long cow(String tag, String species, String arrived) throws Exception {
        return body(post(l("/animals"), owner, Map.of("tag", tag, "species", species, "sex", "FEMALE", "origin", "BORN", "arrivedOn", arrived,
                "groupId", milking)).andExpect(status().isCreated())).get("id").asLong();
    }

    private long boughtCow(String tag, int amount, int life, int cull, String date) throws Exception {
        return body(post(l("/animals"), owner, Map.of("tag", tag, "species", "COW", "sex", "FEMALE", "origin", "BOUGHT", "arrivedOn", date,
                "groupId", milking, "purchase", Map.of("amount", amount, "financialAccountId", cash, "usefulLifeMonths", life, "cullValue", cull))))
                .get("id").asLong();
    }

    private void milk(String date, long group, Object litres) throws Exception {
        put(l("/milk/" + date), owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", group, "litres", litres)))).andExpect(status().isOk());
    }

    private BigDecimal inStock(String date) throws Exception { return new BigDecimal(body(get(l("/milk/" + date), owner)).get("inStock").asText()); }

    @Test
    void milkStockFollowsTheSessionsUntilTheMilkIsUsed() throws Exception {
        cow("A1", "COW", "2025-01-01");
        milk("2026-07-01", milking, 10);
        assertThat(inStock("2026-07-01")).isEqualByComparingTo("10");
        milk("2026-07-01", milking, 12);   // more milk: a produce record for the difference
        assertThat(inStock("2026-07-01")).isEqualByComparingTo("12");
        milk("2026-07-01", milking, 9);    // less milk, nothing used yet: the day's records are replaced
        assertThat(inStock("2026-07-01")).isEqualByComparingTo("9");
        post(l("/milk-uses"), owner, Map.of("date", "2026-07-01", "homeUse", 8)).andExpect(status().isCreated());
        put(l("/milk/2026-07-01"), owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking, "litres", 5))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MILK_ALREADY_USED"));
        assertThat(inStock("2026-07-01")).isEqualByComparingTo("1");
        post(l("/milk-uses"), owner, Map.of("date", "2026-07-01", "discarded", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        // A session cleared before use takes its milk back out of stock
        milk("2026-07-02", milking, 6);
        put(l("/milk/2026-07-02"), owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking)))).andExpect(status().isOk());
        assertThat(inStock("2026-07-02")).isEqualByComparingTo("1");
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void yieldsMustBeForAnimalsInTheGroupThatDayAndTagsAreUnique() throws Exception {
        long a = cow("A1", "COW", "2026-07-05");
        Map<String, Object> early = Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking,
                "yields", List.of(Map.of("animalId", a, "litres", 4)))));
        put(l("/milk/2026-07-04"), owner, early).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ANIMAL_NOT_IN_GROUP"));
        put(l("/milk/2026-07-06"), owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", dry,
                "yields", List.of(Map.of("animalId", a, "litres", 4)))))).andExpect(status().isBadRequest());
        JsonNode day = body(put(l("/milk/2026-07-06"), owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking,
                "yields", List.of(Map.of("animalId", a, "litres", 4.5)))))).andExpect(status().isOk()));
        money(day.get("total"), "4.5");
        post(l("/animals"), owner, Map.of("tag", "a1", "species", "COW", "sex", "FEMALE", "origin", "BORN", "arrivedOn", "2026-07-01", "groupId", milking))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANIMAL_TAG_TAKEN"));
        put(l("/milk/" + LocalDate.now().plusDays(1)), owner, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking, "litres", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_DATE"));
    }

    @Test
    void breedingDatesAreWorkedOutAndClearedByANegativeCheckOrHeat() throws Exception {
        long buffalo = cow("B1", "BUFFALO", "2024-01-01");
        JsonNode ai = body(post(l("/animals/" + buffalo + "/breeding"), owner, Map.of("kind", "INSEMINATION", "date", "2026-03-01", "service", "NATURAL",
                "bull", "Murrah bull")).andExpect(status().isCreated()));
        assertThat(ai.get("expectedCalving").asText()).isEqualTo("2027-01-05");   // 310 days for a buffalo
        assertThat(ai.get("dryOffDue").asText()).isEqualTo("2026-11-06");
        post(l("/animals/" + buffalo + "/breeding"), owner, Map.of("kind", "PREGNANCY_CHECK", "date", "2026-05-01", "result", "NEGATIVE")).andExpect(status().isCreated());
        JsonNode b = body(get(l("/animals/" + buffalo), owner)).at("/animal/breeding");
        assertThat(b.get("repro").asText()).isEqualTo("OPEN");
        assertThat(b.get("expectedCalving").isNull()).isTrue();
        // Re-insemination with an overridden calving date, then a positive check
        post(l("/animals/" + buffalo + "/breeding"), owner, Map.of("kind", "INSEMINATION", "date", "2026-05-20", "service", "AI", "expectedCalving", "2027-03-30"))
                .andExpect(status().isCreated());
        post(l("/animals/" + buffalo + "/breeding"), owner, Map.of("kind", "PREGNANCY_CHECK", "date", "2026-08-01", "result", "POSITIVE")).andExpect(status().isCreated());
        b = body(get(l("/animals/" + buffalo), owner)).at("/animal/breeding");
        assertThat(b.get("repro").asText()).isEqualTo("PREGNANT");
        assertThat(b.get("expectedCalving").asText()).isEqualTo("2027-03-30");
        assertThat(b.get("dryOffDue").asText()).isEqualTo("2027-01-29");
        // Heat after an insemination opens a cow again; checks need an insemination; males have no breeding events
        long cow = cow("C1", "COW", "2024-01-01");
        post(l("/animals/" + cow + "/breeding"), owner, Map.of("kind", "PREGNANCY_CHECK", "date", "2026-05-01", "result", "POSITIVE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_INSEMINATION"));
        post(l("/animals/" + cow + "/breeding"), owner, Map.of("kind", "INSEMINATION", "date", "2026-05-01", "service", "AI")).andExpect(status().isCreated());
        post(l("/animals/" + cow + "/breeding"), owner, Map.of("kind", "HEAT", "date", "2026-05-22")).andExpect(status().isCreated());
        assertThat(body(get(l("/animals/" + cow), owner)).at("/animal/breeding/repro").asText()).isEqualTo("OPEN");
        long bull = body(post(l("/animals"), owner, Map.of("tag", "M1", "species", "COW", "sex", "MALE", "origin", "BORN", "arrivedOn", "2025-01-01",
                "groupId", milking))).get("id").asLong();
        post(l("/animals/" + bull + "/breeding"), owner, Map.of("kind", "HEAT", "date", "2026-05-22")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FEMALES_ONLY"));
    }

    @Test
    void calvingStartsLactationsAndDryOffEndsThemAndMovesTheCow() throws Exception {
        long cow = cow("C1", "COW", "2022-01-01");
        post(l("/animals/" + cow + "/breeding"), owner, Map.of("kind", "DRY_OFF", "date", "2026-01-01")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_MILK"));
        post(l("/animals/" + cow + "/calving"), owner, Map.of("date", "2025-06-01", "result", "STILLBORN")).andExpect(status().isCreated());
        post(l("/animals/" + cow + "/calving"), owner, Map.of("date", "2025-09-01", "result", "LIVE", "calves", List.of(Map.of("tag", "X1", "sex", "MALE"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALVING_TOO_SOON"));
        post(l("/animals/" + cow + "/breeding"), owner, Map.of("kind", "DRY_OFF", "date", "2026-04-01")).andExpect(status().isCreated());
        JsonNode card = body(get(l("/animals/" + cow), owner));
        assertThat(card.at("/animal/groupId").asLong()).isEqualTo(dry);
        assertThat(card.at("/lactations/0/dryOffDate").asText()).isEqualTo("2026-04-01");
        JsonNode after = body(post(l("/animals/" + cow + "/calving"), owner, Map.of("date", "2026-06-10", "result", "LIVE",
                "calves", List.of(Map.of("tag", "X2", "name", "Twin A", "sex", "FEMALE"), Map.of("tag", "X3", "sex", "MALE"))))
                .andExpect(status().isCreated()));
        assertThat(after.get("lactations")).hasSize(2);
        assertThat(after.at("/animal/groupId").asLong()).isEqualTo(milking);
        assertThat(after.at("/animal/breeding/inMilk").asBoolean()).isTrue();
        assertThat(after.get("calves")).hasSize(2);
        assertThat(after.at("/calves/0/damId").asLong()).isEqualTo(cow);
        assertThat(after.at("/calves/0/groupId").asLong()).isEqualTo(calves);
        JsonNode calendar = body(get(l("/reports/breeding?from=2026-01-01&to=2027-12-31"), owner));
        assertThat(calendar.at("/intervals/0/days").asLong()).isEqualTo(374);
    }

    @Test
    void depreciationFollowsTheGroupAndStopsAtTheCullValue() throws Exception {
        long lak = boughtCow("L1", 30000, 3, 24000, "2026-03-15");   // Rs 2,000 a month from April
        post(l("/animals/" + lak + "/moves"), owner, Map.of("groupId", dry, "date", "2026-05-20")).andExpect(status().isOk());
        post(l("/animals/" + lak + "/moves"), owner, Map.of("groupId", milking, "date", "2026-05-01")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LATER_MOVE_EXISTS"));
        for (String m : List.of("2026-04", "2026-05", "2026-06")) {
            money(body(post("/api/v1/tenants/" + tenant + "/assets/depreciation-runs", owner, Map.of("month", m)).andExpect(status().isCreated()))
                    .at("/summary/totalAmount"), "2000.00");
        }
        post("/api/v1/tenants/" + tenant + "/assets/depreciation-runs", owner, Map.of("month", "2026-07")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_DEPRECIATE"));
        money(body(get(l("/animals/" + lak), owner)).at("/animal/bookValue"), "24000");
        // Depreciation goes to the group the animal is in when the run is posted: all three runs were posted after the move to
        // Dry cows (a documented limitation; runs posted each month follow the herd month by month)
        money(report("income-statement?from=2026-04-01&to=2026-06-30&costObjectId=" + dryCo).get("operatingCostTotal"), "6000");
        money(report("income-statement?from=2026-04-01&to=2026-06-30&costObjectId=" + milkingCo).get("operatingCostTotal"), "0");
    }

    @Test
    void exitsPostTheRightRecordsAndReversingThemReturnsTheAnimal() throws Exception {
        long buyer = party("Cattle trader");
        // Bought cow sold above book value: gain to 4900
        long lak = boughtCow("L1", 30000, 60, 0, "2026-01-10");
        JsonNode sold = body(post(l("/animals/" + lak + "/exit"), owner, Map.of("date", "2026-02-15", "kind", "SOLD", "price", 32000, "partyId", buyer))
                .andExpect(status().isOk()));
        assertThat(sold.get("status").asText()).isEqualTo("SOLD");
        long disposal = sold.get("exitDocumentId").asLong();
        assertThat(report("income-statement?from=2026-01-01&to=2026-03-31").get("otherIncome").toString()).contains("4900");
        post(f("/documents/" + disposal + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        assertThat(body(get(l("/animals/" + lak), owner)).at("/animal/status").asText()).isEqualTo("ACTIVE");
        // Bought cow died: its book value is a loss (5980)
        JsonNode died = body(post(l("/animals/" + lak + "/exit"), owner, Map.of("date", "2026-03-01", "kind", "DIED", "cause", "Snake bite")).andExpect(status().isOk()));
        assertThat(died.get("status").asText()).isEqualTo("DIED");
        money(report("income-statement?from=2026-03-01&to=2026-03-31").get("losses"), "30000");
        // Home-bred animal sold: Livestock sales (4000), assigned to its group
        long calf = cow("H1", "COW", "2025-06-01");
        post(l("/animals/" + calf + "/exit"), owner, Map.of("date", "2026-03-05", "kind", "SOLD", "price", 9000)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYER_REQUIRED"));
        post(l("/animals/" + calf + "/exit"), owner, Map.of("date", "2026-03-05", "kind", "SOLD", "price", 9000, "financialAccountId", cash)).andExpect(status().isOk());
        money(report("income-statement?from=2026-03-01&to=2026-03-31&costObjectId=" + milkingCo).get("operatingRevenueTotal"), "9000");
        // Home-bred animal died: no money record; an insurance claim is other income (4100)
        long old = cow("H2", "COW", "2020-01-01");
        JsonNode gone = body(post(l("/animals/" + old + "/exit"), owner, Map.of("date", "2026-03-10", "kind", "DIED")).andExpect(status().isOk()));
        assertThat(gone.get("exitDocumentId").isNull()).isTrue();
        post(l("/animals/" + old + "/insurance"), owner, Map.of("date", "2026-03-20", "amount", 15000, "financialAccountId", cash)).andExpect(status().isCreated());
        // Category Other income maps to 4100 (Other farm income), which the profit and loss shows under operating revenue
        assertThat(report("income-statement?from=2026-03-01&to=2026-03-31").get("operatingRevenue").toString()).contains("4100");
        post(l("/animals/" + old + "/exit"), owner, Map.of("date", "2026-03-11", "kind", "DIED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ACTIVE"));
        assertThat(body(get(l("/animals"), owner))).hasSize(0);
        assertThat(body(get(l("/animals?includeGone=true"), owner))).hasSize(3);
        assertBooksBalance("2026-12-31");
    }

    @Test
    void permissionsAndTheModuleSwitchFollowTheRoles() throws Exception {
        long lak = boughtCow("L1", 30000, 60, 0, "2026-01-10");
        User worker = newUser("ls-worker"), viewer = newUser("ls-viewer");
        addMember(owner, tenant, worker, "WORKER");
        addMember(owner, tenant, viewer, "VIEWER");
        // A worker records milk and sees the herd without book values, but cannot sell a registered animal or see profit
        put(l("/milk/2026-07-01"), worker, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking, "litres", 8)))).andExpect(status().isOk());
        JsonNode list = body(get(l("/animals"), worker).andExpect(status().isOk()));
        assertThat(list.get(0).get("bookValue").isNull()).isTrue();
        post(l("/animals/" + lak + "/exit"), worker, Map.of("date", "2026-07-02", "kind", "DIED")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINANCE_MANAGE_REQUIRED"));
        get(l("/reports/herd?from=2026-07-01&to=2026-07-31"), worker).andExpect(status().isForbidden());
        post(l("/milk-sales"), worker, Map.of("date", "2026-07-01", "financialAccountId", cash, "litres", 8, "amount", 300, "groupId", milking))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCE_PERMISSION_REQUIRED"));
        // A viewer reads but does not record
        get(l("/animals/" + lak), viewer).andExpect(status().isOk());
        put(l("/milk/2026-07-01"), viewer, Map.of("sessions", List.of(Map.of("session", "MORNING", "groupId", milking, "litres", 8)))).andExpect(status().isForbidden());
        // A farm business without the module gets MODULE_DISABLED
        long cropOnly = body(post("/api/v1/tenants", owner, Map.of("name", "Crops only", "modules", List.of("CROP"))).andExpect(status().isCreated())).get("id").asLong();
        get("/api/v1/tenants/" + cropOnly + "/livestock/groups", owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }

    @Test
    void healthEventsSetWithdrawalsAndNextDueDates() throws Exception {
        long a = cow("A1", "COW", "2025-01-01");
        JsonNode t = body(post(l("/health"), owner, Map.of("animalId", a, "kind", "TREATMENT", "date", LocalDate.now().toString(), "description", "Fever",
                "withdrawalDays", 3)).andExpect(status().isCreated()));
        assertThat(t.get("withdrawalUntil").asText()).isEqualTo(LocalDate.now().plusDays(2).toString());
        assertThat(body(get(l("/milk/" + LocalDate.now()), owner)).at("/withheld/0/animalId").asLong()).isEqualTo(a);
        post(l("/health"), owner, Map.of("groupId", milking, "animalId", a, "kind", "VACCINATION", "date", "2026-06-01", "description", "FMD"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ANIMAL_OR_GROUP"));
        post(l("/health"), owner, Map.of("groupId", milking, "kind", "VACCINATION", "date", "2026-06-01", "description", "FMD",
                "nextDue", LocalDate.now().plusDays(3).toString())).andExpect(status().isCreated());
        JsonNode dash = body(get("/api/v1/tenants/" + tenant + "/dashboard", owner));
        assertThat(dash.toString()).contains("MILK_WITHHELD").contains("HEALTH_DUE");
        long eventId = t.get("id").asLong();
        jdbc.update("update sf_animal_events set document_id = null where id = ?", eventId);
        delete(l("/events/" + eventId), owner).andExpect(status().isNoContent());
        JsonNode health = body(get(l("/reports/health?from=2026-01-01&to=" + LocalDate.now()), owner));
        assertThat(health.get("events")).hasSize(1);
        assertThat(health.get("nextDue")).hasSize(1);
    }
}
