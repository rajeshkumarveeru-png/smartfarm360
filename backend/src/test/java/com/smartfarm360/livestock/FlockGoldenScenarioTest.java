package com.smartfarm360.livestock;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/livestock/poultry-goats-sheep.md, section 10, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class FlockGoldenScenarioTest extends FinanceTestSupport {

    private String l(String path) { return "/api/v1/tenants/" + tenant + "/livestock" + path; }

    private String s(String path) { return "/api/v1/tenants/" + tenant + "/stock" + path; }

    private long feed(String name, int kg, int rate, String date) throws Exception {
        long id = body(post(s("/items"), owner, Map.of("name", name, "kind", "INPUT", "baseUnit", "kg", "expenseCategoryId", categoryId("ANIMAL_FEED")))
                .andExpect(status().isCreated())).get("id").asLong();
        post(f("/bills"), owner, Map.of("docDate", date, "financialAccountId", cash, "lines", List.of(Map.of("itemId", id, "quantity", kg, "rate", rate))))
                .andExpect(status().isCreated());
        return id;
    }

    private void day(long batch, String date, Map<String, Object> body) throws Exception {
        put(l("/flocks/" + batch + "/days/" + date), owner, body).andExpect(status().isOk());
    }

    @Test
    void julyExampleMatchesThePoultryGoatsAndSheepDesign() throws Exception {
        newFinanceTenant("flock-golden");
        put("/api/v1/tenants/" + tenant + "/modules/POULTRY", owner, Map.of("enabled", true)).andExpect(status().isOk());
        put("/api/v1/tenants/" + tenant + "/modules/GOAT_SHEEP", owner, Map.of("enabled", true)).andExpect(status().isOk());

        // ---- Layers 2026-A: 1,000 pullets at 18 weeks, Rs 180 each, a cost of the batch (J3) ----
        long mash = feed("Layer mash", 3400, 32, "2026-05-31");
        JsonNode layers = body(post(l("/flocks"), owner, Map.of("name", "Layers 2026-A", "species", "CHICKEN", "purpose", "LAYER", "placedOn", "2026-06-01",
                "ageAtPlacementDays", 126, "heads", 1000, "bill", Map.of("amount", 180000, "financialAccountId", cash))).andExpect(status().isCreated()));
        long lay = layers.get("id").asLong();
        assertThat(layers.get("animals").asInt()).isEqualTo(1000);
        day(lay, "2026-06-20", Map.of("deaths", 10, "deathCause", "Heat"));
        for (int d = 1; d <= 31; d++) {
            Map<String, Object> rec = new HashMap<>(d <= 30
                    ? Map.of("eggsGood", 826, "eggsCracked", 10, "eggsBroken", 3, "feedItemId", mash, "feedKg", 110)
                    : Map.of("eggsGood", 820, "eggsCracked", 0, "eggsBroken", 10, "feedItemId", mash, "feedKg", 100));
            if (d == 15) rec.put("deaths", 6);
            day(lay, LocalDate.of(2026, 7, d).toString(), rec);
        }
        post(l("/health"), owner, Map.of("groupId", lay, "kind", "VACCINATION", "date", "2026-07-08", "description", "Ranikhet",
                "bill", Map.of("amount", 1500, "financialAccountId", cash))).andExpect(status().isCreated());
        for (String[] sale : new String[][]{{"2026-07-12", "300", "45000"}, {"2026-07-24", "300", "45000"}, {"2026-07-31", "240", "36000"}}) {
            post(l("/egg-sales"), owner, Map.of("date", sale[0], "groupId", lay, "quantity", sale[1], "unit", "tray", "amount", sale[2],
                    "financialAccountId", cash)).andExpect(status().isCreated());
        }
        post(l("/egg-uses"), owner, Map.of("date", "2026-07-31", "homeUse", 300)).andExpect(status().isCreated());

        JsonNode lr = body(get(l("/flocks/" + lay + "/report?from=2026-07-01&to=2026-07-31"), owner).andExpect(status().isOk()));
        JsonNode p = lr.get("period");
        assertThat(p.get("openingHeads").asInt()).isEqualTo(990);
        assertThat(p.get("headDays").asLong()).isEqualTo(30588);
        assertThat(p.get("eggs").asInt()).isEqualTo(26000);
        assertThat(p.get("eggsGood").asInt()).isEqualTo(25600);
        money(p.get("henDayPercent"), "85.0");
        money(p.get("feedKg"), "3400");
        money(p.get("feedPerDozen"), "1.57");
        money(p.get("mortalityPercent"), "0.61");
        money(p.get("costs"), "110300");
        money(p.get("revenue"), "126000");
        money(p.get("profit"), "15700");
        money(p.get("costPerEgg"), "4.24");
        // Since placement includes the Rs 1,80,000 pullets and June (principle 4)
        JsonNode since = lr.get("sincePlacement");
        assertThat(since.get("from").asText()).isEqualTo("2026-06-01");
        money(since.get("costs"), "290300");
        assertThat(since.get("died").asInt()).isEqualTo(16);
        // Eggs in stock on 31 July: 400
        long eggItem = jdbc.queryForObject("select egg_item_id from sf_livestock_settings where tenant_id = ?", Long.class, tenant);
        money(body(get(s("/items/" + eggItem), owner)).get("quantity"), "400");

        // ---- Broilers B-07: 2,000 day-old chicks at Rs 40 ----
        long bfeed = feed("Broiler feed", 7000, 38, "2026-06-19");
        long broilers = body(post(l("/flocks"), owner, Map.of("name", "Broilers B-07", "species", "CHICKEN", "purpose", "BROILER", "placedOn", "2026-06-20",
                "ageAtPlacementDays", 1, "heads", 2000, "bill", Map.of("amount", 80000, "financialAccountId", cash))).andExpect(status().isCreated())).get("id").asLong();
        for (String[] d : new String[][]{{"2026-06-25", "2000"}, {"2026-07-05", "2500"}, {"2026-07-20", "2500"}}) {
            day(broilers, d[0], Map.of("deaths", 20, "feedItemId", bfeed, "feedKg", Integer.parseInt(d[1])));
        }
        post(l("/health"), owner, Map.of("groupId", broilers, "kind", "VACCINATION", "date", "2026-06-22", "description", "Vaccines and medicine",
                "bill", Map.of("amount", 6000, "financialAccountId", cash))).andExpect(status().isCreated());
        long co = jdbc.queryForObject("select cost_object_id from sf_animal_groups where id = ?", Long.class, broilers);
        invoice("bills", "2026-06-20", null, cash, line("OTHER_INPUTS", 4000, co));
        JsonNode sold = body(post(l("/flocks/" + broilers + "/sales"), owner, Map.of("date", "2026-07-31", "heads", 1940, "kg", 4268, "rateBasis", "KG",
                "rate", 110, "financialAccountId", cash)).andExpect(status().isCreated()));
        assertThat(sold.get("kind").asText()).isEqualTo("SOLD");
        JsonNode br = body(get(l("/flocks/" + broilers + "/report?from=2026-06-20&to=2026-07-31"), owner).andExpect(status().isOk()));
        JsonNode b = br.get("sincePlacement");
        assertThat(br.get("heads").asInt()).isZero();
        assertThat(br.at("/group/closed").asBoolean()).isTrue();
        money(b.get("mortalityPercent"), "3.00");
        money(b.get("averageWeight"), "2.20");
        money(b.get("fcr"), "1.64");
        money(b.get("costs"), "356000");
        money(b.get("revenue"), "469480");
        money(b.get("profit"), "113480");
        money(b.get("costPerKg"), "83.41");

        // ---- Goat herd: 21 goats, 6 kids born, 1 died, 4 sold at Rs 6,500 ----
        long goats = body(post(l("/flocks"), owner, Map.of("name", "Goat herd", "species", "GOAT", "purpose", "BREEDING_HERD", "placedOn", "2026-07-01",
                "heads", 21)).andExpect(status().isCreated())).get("id").asLong();
        long goatCo = jdbc.queryForObject("select cost_object_id from sf_animal_groups where id = ?", Long.class, goats);
        post(l("/flocks/" + goats + "/births"), owner, Map.of("date", "2026-07-10", "alive", 6, "dead", 0)).andExpect(status().isCreated());
        post(l("/flocks/" + goats + "/movements"), owner, Map.of("date", "2026-07-15", "kind", "DIED", "heads", 1, "cause", "Weak kid")).andExpect(status().isCreated());
        post(l("/flocks/" + goats + "/sales"), owner, Map.of("date", "2026-07-30", "heads", 4, "kg", 88, "rateBasis", "HEAD", "rate", 6500,
                "financialAccountId", cash)).andExpect(status().isCreated());
        long concentrate = feed("Goat concentrate", 150, 30, "2026-07-01");
        post(s("/issues"), owner, Map.of("docDate", "2026-07-20", "costObjectId", goatCo, "lines", List.of(Map.of("itemId", concentrate, "quantity", 150))))
                .andExpect(status().isCreated());
        post(l("/health"), owner, Map.of("groupId", goats, "kind", "DEWORMING", "date", "2026-07-12", "description", "Deworming",
                "bill", Map.of("amount", 400, "financialAccountId", cash))).andExpect(status().isCreated());
        JsonNode gr = body(get(l("/flocks/" + goats + "/report?from=2026-07-01&to=2026-07-31"), owner).andExpect(status().isOk()));
        JsonNode g = gr.get("period");
        assertThat(g.get("closingHeads").asInt()).isEqualTo(22);
        assertThat(g.get("born").asInt()).isEqualTo(6);
        money(g.get("costs"), "4900");
        money(g.get("revenue"), "26000");
        money(g.get("profit"), "21100");
        assertThat(gr.get("sincePlacement").isNull()).isTrue();   // herds are ongoing; only batches have since-placement figures

        assertBooksBalance("2026-07-31");
        assertThat(journalImbalance()).isZero();
    }
}
