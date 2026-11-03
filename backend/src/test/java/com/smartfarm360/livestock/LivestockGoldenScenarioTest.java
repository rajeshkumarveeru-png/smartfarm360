package com.smartfarm360.livestock;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/livestock/livestock-and-dairy.md, section 7, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class LivestockGoldenScenarioTest extends FinanceTestSupport {

    private String l(String path) { return "/api/v1/tenants/" + tenant + "/livestock" + path; }

    private String s(String path) { return "/api/v1/tenants/" + tenant + "/stock" + path; }

    private static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).filter(x -> value.equals(x.path(field).asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + field + "=" + value + " in " + array));
    }

    @Test
    void julyExampleMatchesTheLivestockDesign() throws Exception {
        newFinanceTenant("livestock-golden");
        JsonNode milking = body(post(l("/groups"), owner, Map.of("name", "Milking herd", "purpose", "MILKING")).andExpect(status().isCreated()));
        JsonNode calves = body(post(l("/groups"), owner, Map.of("name", "Calves", "purpose", "CALF")).andExpect(status().isCreated()));
        long herd = milking.get("id").asLong(), calf = calves.get("id").asLong();
        long herdCo = milking.get("costObjectId").asLong(), calfCo = calves.get("costObjectId").asLong();

        // Lakshmi: bought 1 April for Rs 60,000; 72 months to a cull value of Rs 24,000 (I3, I4)
        JsonNode lakshmi = body(post(l("/animals"), owner, Map.of("tag", "L-01", "name", "Lakshmi", "species", "COW", "sex", "FEMALE", "origin", "BOUGHT",
                "arrivedOn", "2026-04-01", "groupId", herd, "purchase", Map.of("amount", 60000, "financialAccountId", cash, "usefulLifeMonths", 72,
                        "cullValue", 24000))).andExpect(status().isCreated()));
        long lak = lakshmi.get("id").asLong();
        assertThat(lakshmi.get("assetId").isNull()).isFalse();
        // Ganga: home-bred, nil value; she calved Nandhini on 1 May (I11)
        long ganga = body(post(l("/animals"), owner, Map.of("tag", "G-01", "name", "Ganga", "species", "COW", "sex", "FEMALE", "origin", "BORN",
                "arrivedOn", "2022-03-01", "groupId", herd)).andExpect(status().isCreated())).get("id").asLong();
        JsonNode calving = body(post(l("/animals/" + ganga + "/calving"), owner, Map.of("date", "2026-05-01", "result", "LIVE",
                "calves", List.of(Map.of("tag", "C-01", "name", "Nandhini", "sex", "FEMALE")))).andExpect(status().isCreated()));
        assertThat(calving.at("/calves/0/groupId").asLong()).isEqualTo(calf);
        assertThat(calving.at("/calves/0/assetId").isNull()).isTrue();
        assertThat(calving.at("/lactations/0/number").asInt()).isEqualTo(1);

        // Depreciation May to July: Rs 500 a month, to the Milking herd
        for (String m : List.of("2026-05", "2026-06", "2026-07")) {
            money(body(post("/api/v1/tenants/" + tenant + "/assets/depreciation-runs", owner, Map.of("month", m)).andExpect(status().isCreated()))
                    .at("/summary/totalAmount"), "500.00");
        }

        // Feed: 500 kg bought at Rs 28; 400 kg to the Milking herd, 50 kg to Calves
        long feed = body(post(s("/items"), owner, Map.of("name", "Cattle feed", "kind", "INPUT", "baseUnit", "kg",
                "expenseCategoryId", categoryId("ANIMAL_FEED"))).andExpect(status().isCreated())).get("id").asLong();
        post(f("/bills"), owner, Map.of("docDate", "2026-07-01", "financialAccountId", cash,
                "lines", List.of(Map.of("itemId", feed, "quantity", 500, "rate", 28)))).andExpect(status().isCreated());
        // Green fodder and the deworming, mastitis and AI bills to the Milking herd
        invoice("bills", "2026-07-02", null, cash, line("ANIMAL_FEED", 3000, herdCo));
        post(l("/health"), owner, Map.of("groupId", herd, "kind", "DEWORMING", "date", "2026-07-05", "description", "Deworming",
                "nextDue", "2026-10-05", "bill", Map.of("amount", 600, "financialAccountId", cash))).andExpect(status().isCreated());
        JsonNode ai = body(post(l("/animals/" + ganga + "/breeding"), owner, Map.of("kind", "INSEMINATION", "date", "2026-07-10", "service", "AI",
                "bull", "HF semen", "bill", Map.of("amount", 300, "financialAccountId", cash))).andExpect(status().isCreated()));
        assertThat(ai.get("checkDueFrom").asText()).isEqualTo("2026-09-08");
        assertThat(ai.get("expectedCalving").asText()).isEqualTo("2027-04-19");
        assertThat(ai.get("dryOffDue").asText()).isEqualTo("2027-02-18");

        // Milk: Lakshmi 10 l and Ganga 8 l a day, in two sessions, for 31 days = 558 litres
        long societyParty = party("Village dairy society");
        for (int d = 1; d <= 31; d++) {
            LocalDate day = LocalDate.of(2026, 7, d);
            List<Map<String, Object>> sessions = List.of(
                    Map.of("session", "MORNING", "groupId", herd, "fat", 4.2, "snf", 8.5,
                            "yields", List.of(Map.of("animalId", lak, "litres", 5), Map.of("animalId", ganga, "litres", 4))),
                    Map.of("session", "EVENING", "groupId", herd,
                            "yields", List.of(Map.of("animalId", lak, "litres", 5), Map.of("animalId", ganga, "litres", 4))));
            put(l("/milk/" + day), owner, Map.of("sessions", sessions)).andExpect(status().isOk());
            if (d == 20) {
                // Mastitis: 4-day milk withdrawal from 20 July (I9)
                post(l("/health"), owner, Map.of("animalId", lak, "kind", "TREATMENT", "date", "2026-07-20", "description", "Mastitis",
                        "withdrawalDays", 4, "bill", Map.of("amount", 450, "financialAccountId", cash))).andExpect(status().isCreated());
            }
            if (d == 10 || d == 20) {
                post(l("/milk-sales"), owner, Map.of("date", day.toString(), "partyId", societyParty, "litres", 140, "amount", 5320, "groupId", herd,
                        "periodFrom", day.minusDays(9).toString(), "periodTo", day.toString(),
                        "paidNow", Map.of("financialAccountId", cash, "amount", 5320))).andExpect(status().isCreated());
            }
        }
        JsonNode withheldDay = body(get(l("/milk/2026-07-22"), owner));
        assertThat(withheldDay.at("/withheld/0/tag").asText()).isEqualTo("L-01");
        assertThat(withheldDay.at("/withheld/0/until").asText()).isEqualTo("2026-07-23");
        money(withheldDay.get("total"), "18");
        // Lakshmi's milk from 20 to 23 July (40 litres) discarded; 31 home use and 62 fed to Nandhini
        post(l("/milk-uses"), owner, Map.of("date", "2026-07-23", "discarded", 40, "discardReason", "Mastitis withdrawal (L-01)")).andExpect(status().isCreated());
        long sale3 = body(post(l("/milk-sales"), owner, Map.of("date", "2026-07-31", "partyId", societyParty, "litres", 145, "amount", 5510, "groupId", herd,
                "periodFrom", "2026-07-21", "periodTo", "2026-07-31")).andExpect(status().isCreated())).get("documentId").asLong();
        settle("receipts", "2026-07-31", societyParty, cash, 1360, sale3, 1360);
        post(l("/milk-uses"), owner, Map.of("date", "2026-07-31", "homeUse", 31, "fedLitres", 62, "fedGroupId", calf)).andExpect(status().isCreated());
        post(s("/issues"), owner, Map.of("docDate", "2026-07-31", "costObjectId", herdCo, "lines", List.of(Map.of("itemId", feed, "quantity", 400))))
                .andExpect(status().isCreated());
        post(s("/issues"), owner, Map.of("docDate", "2026-07-31", "costObjectId", calfCo, "lines", List.of(Map.of("itemId", feed, "quantity", 50))))
                .andExpect(status().isCreated());

        // Milk stock: 558 in; 425 sold, 31 home, 62 to calves, 40 discarded; balance 0
        JsonNode milk = body(get(l("/reports/milk?from=2026-07-01&to=2026-07-31"), owner).andExpect(status().isOk()));
        money(milk.get("produced"), "558");
        money(milk.get("sold"), "425");
        money(milk.get("homeUse"), "31");
        money(milk.get("fedToAnimals"), "62");
        money(milk.get("discarded"), "40");
        money(milk.get("fat"), "4.2");
        money(body(get(l("/milk/2026-07-31"), owner)).get("inStock"), "0");
        // Cattle feed: 50 kg worth Rs 1,400
        JsonNode feedItem = body(get(s("/items/" + feed), owner));
        money(feedItem.get("quantity"), "50");
        money(feedItem.get("value"), "1400");

        // Herd profitability, July (I13)
        JsonNode herdReport = body(get(l("/reports/herd?from=2026-07-01&to=2026-07-31"), owner).andExpect(status().isOk()));
        JsonNode mh = find(herdReport.get("groups"), "groupName", "Milking herd");
        money(mh.get("revenue"), "16150");
        money(mh.get("costTotal"), "16050");
        money(mh.get("profit"), "100");
        money(mh.get("litresProduced"), "558");
        money(mh.get("costPerLitre"), "28.76");
        money(mh.get("pricePerLitre"), "38.00");
        money(find(mh.get("costs"), "accountCode", "5800").get("amount"), "500");
        JsonNode ch = find(herdReport.get("groups"), "groupName", "Calves");
        money(ch.get("costTotal"), "1400");

        // Receivable from the society, Lakshmi's book value, Ganga's dates
        JsonNode dues = report("dues?asOf=2026-07-31");
        money(find(dues.get("receivables"), "partyName", "Village dairy society").get("balance"), "4150");
        JsonNode lakCard = body(get(l("/animals/" + lak), owner));
        money(lakCard.at("/animal/bookValue"), "58500");
        JsonNode gangaCard = body(get(l("/animals/" + ganga), owner));
        assertThat(gangaCard.at("/animal/breeding/repro").asText()).isEqualTo("INSEMINATED");
        assertThat(gangaCard.at("/animal/breeding/checkDueFrom").asText()).isEqualTo("2026-09-08");
        assertThat(gangaCard.at("/animal/breeding/inMilk").asBoolean()).isTrue();
        assertThat(gangaCard.get("calves")).hasSize(1);

        // Dashboard on 31 July: no withheld milk, nothing due within 7 days
        JsonNode dash = body(get("/api/v1/tenants/" + tenant + "/dashboard?asOf=2026-07-31", owner).andExpect(status().isOk()));
        assertThat(dash.get("errors")).isEmpty();
        assertThat(dash.toString()).doesNotContain("MILK_WITHHELD").doesNotContain("BREEDING_DUE").doesNotContain("HEALTH_DUE");
        JsonNode sept = body(get("/api/v1/tenants/" + tenant + "/dashboard?asOf=2026-09-05", owner));
        assertThat(find(sept.get("attention"), "code", "BREEDING_DUE").get("count").asInt()).isEqualTo(1);

        assertBooksBalance("2026-07-31");
        assertThat(journalImbalance()).isZero();
    }
}
