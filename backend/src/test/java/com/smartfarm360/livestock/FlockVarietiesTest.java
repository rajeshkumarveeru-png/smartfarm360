package com.smartfarm360.livestock;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Batch varieties (bin and batch varieties design, V1-V7): several varieties in a batch, their heads, and eggs by variety with a rate. */
class FlockVarietiesTest extends FinanceTestSupport {

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("var-" + UUID.randomUUID().toString().substring(0, 6));
        put("/api/v1/tenants/" + tenant + "/modules/POULTRY", owner, Map.of("enabled", true)).andExpect(status().isOk());
    }

    private String l(String path) { return "/api/v1/tenants/" + tenant + "/livestock" + path; }

    private Map<String, Integer> heads(long batch) throws Exception {
        JsonNode lines = body(get(l("/flocks/" + batch + "/lines"), owner).andExpect(status().isOk()));
        return StreamSupport.stream(lines.spliterator(), false)
                .collect(java.util.stream.Collectors.toMap(n -> n.get("variety").asText(), n -> n.get("heads").asInt()));
    }

    private long lineId(long batch, String variety) throws Exception {
        JsonNode lines = body(get(l("/flocks/" + batch + "/lines"), owner));
        return StreamSupport.stream(lines.spliterator(), false).filter(n -> variety.equals(n.get("variety").asText())).findFirst().orElseThrow().get("id").asLong();
    }

    private long startMixedBatch() throws Exception {
        return body(post(l("/flocks"), owner, Map.of("name", "Backyard 1", "species", "CHICKEN", "purpose", "COUNTRY", "placedOn", "2026-07-01",
                "bill", Map.of("amount", 900, "financialAccountId", cash),
                "lines", List.of(Map.of("species", "CHICKEN", "variety", "Sonali", "heads", 10, "amount", 500),
                        Map.of("species", "DUCK", "variety", "Muscovy", "heads", 5, "amount", 400)))).andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void aBatchHoldsSeveralVarietiesWithOneBill() throws Exception {
        long batch = startMixedBatch();
        assertThat(heads(batch)).containsEntry("Sonali", 10).containsEntry("Muscovy", 5);
        money(report("income-statement?from=2026-07-01&to=2026-07-31").get("operatingCostTotal"), "900");

        // A death must name its variety when there are several
        post(l("/flocks/" + batch + "/movements"), owner, Map.of("date", "2026-07-03", "kind", "DIED", "heads", 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINE_REQUIRED"));
        post(l("/flocks/" + batch + "/movements"), owner, Map.of("date", "2026-07-03", "kind", "DIED", "heads", 1, "lineId", lineId(batch, "Muscovy")))
                .andExpect(status().isCreated());
        // More than a variety has is refused
        post(l("/flocks/" + batch + "/movements"), owner, Map.of("date", "2026-07-04", "kind", "DIED", "heads", 5, "lineId", lineId(batch, "Muscovy")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HEADS_NEGATIVE"));

        // Buying more adds a new variety and more of an existing one, on one bill
        post(l("/flocks/" + batch + "/purchases"), owner, Map.of("date", "2026-07-05", "bill", Map.of("amount", 450, "financialAccountId", cash),
                "lines", List.of(Map.of("species", "CHICKEN", "variety", "Kadaknath", "heads", 3, "amount", 300),
                        Map.of("lineId", lineId(batch, "Sonali"), "heads", 2, "amount", 150)))).andExpect(status().isCreated());
        assertThat(heads(batch)).containsEntry("Sonali", 12).containsEntry("Muscovy", 4).containsEntry("Kadaknath", 3);

        // A count corrects one variety
        post(l("/flocks/" + batch + "/movements"), owner, Map.of("date", "2026-07-06", "kind", "COUNT", "countedHeads", 11, "cause", "Recounted",
                "lineId", lineId(batch, "Sonali"))).andExpect(status().isCreated());
        assertThat(heads(batch)).containsEntry("Sonali", 11);
        JsonNode moves = body(get(l("/flocks/" + batch + "/movements"), owner));
        assertThat(moves.findValuesAsText("lineVariety")).contains("Sonali", "Muscovy", "Kadaknath");
    }

    @Test
    void theDailyRecordTakesEggsByVarietyAndTheirRateCountsInTheProfit() throws Exception {
        long batch = startMixedBatch();
        long sonali = lineId(batch, "Sonali"), muscovy = lineId(batch, "Muscovy");
        JsonNode day = body(put(l("/flocks/" + batch + "/days/2026-07-02"), owner, Map.of("eggsCracked", 1,
                "lines", List.of(Map.of("lineId", sonali, "deaths", 1, "eggs", 6, "eggRate", 8), Map.of("lineId", muscovy, "eggs", 4, "eggRate", 12))))
                .andExpect(status().isOk()));
        assertThat(day.get("deaths").asInt()).isEqualTo(1);
        assertThat(day.get("eggsGood").asInt()).isEqualTo(9);   // 10 laid, 1 cracked
        assertThat(heads(batch)).containsEntry("Sonali", 9);

        JsonNode figures = body(get(l("/flocks/" + batch + "/report?from=2026-07-01&to=2026-07-31"), owner)).get("period");
        money(figures.get("eggValue"), "96");                     // 6 x 8 + 4 x 12
        money(figures.get("profitWithEggs"), "-804");              // 96 less the 900 the birds cost

        put(l("/flocks/" + batch + "/days/2026-07-03"), owner, Map.of("eggsCracked", 5, "lines", List.of(Map.of("lineId", sonali, "eggs", 2))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EGGS_SPLIT"));
    }

    private static JsonNode lineOf(JsonNode day, long lineId) {
        return StreamSupport.stream(day.get("lines").spliterator(), false).filter(n -> n.get("lineId").asLong() == lineId).findFirst().orElseThrow();
    }

    private static JsonNode varietyOf(JsonNode figures, String variety) {
        return StreamSupport.stream(figures.get("varieties").spliterator(), false).filter(n -> variety.equals(n.get("variety").asText())).findFirst().orElseThrow();
    }

    @Test
    void aNewDayOffersTheLastEggsAndRateWithoutSavingThem() throws Exception {
        long batch = startMixedBatch();
        long sonali = lineId(batch, "Sonali"), muscovy = lineId(batch, "Muscovy");
        put(l("/flocks/" + batch + "/days/2026-07-02"), owner, Map.of("lines", List.of(Map.of("lineId", sonali, "eggs", 6, "eggRate", 8)))).andExpect(status().isOk());
        put(l("/flocks/" + batch + "/days/2026-07-03"), owner, Map.of("lines", List.of(Map.of("lineId", sonali, "eggs", 7)))).andExpect(status().isOk());

        JsonNode next = body(get(l("/flocks/" + batch + "/days/2026-07-04"), owner).andExpect(status().isOk()));
        assertThat(next.get("version").asText()).isEqualTo("0");   // Nothing is recorded for the day
        JsonNode line = lineOf(next, sonali);
        assertThat(line.get("eggs").asInt()).isZero();
        assertThat(line.get("lastEggs").asInt()).isEqualTo(7);
        assertThat(line.get("lastEggsOn").asText()).isEqualTo("2026-07-03");
        money(line.get("lastEggRate"), "8");                       // The last rate given, a day earlier
        assertThat(lineOf(next, muscovy).get("lastEggs").isNull()).isTrue();
        // A later record is not offered for an earlier day
        assertThat(lineOf(body(get(l("/flocks/" + batch + "/days/2026-07-02"), owner)), sonali).get("lastEggs").isNull()).isTrue();
    }

    @Test
    void theFiguresAreSplitByVariety() throws Exception {
        long batch = startMixedBatch();
        long sonali = lineId(batch, "Sonali"), muscovy = lineId(batch, "Muscovy");
        put(l("/flocks/" + batch + "/days/2026-07-02"), owner, Map.of("lines", List.of(Map.of("lineId", sonali, "deaths", 1, "eggs", 6, "eggRate", 8),
                Map.of("lineId", muscovy, "eggs", 4, "eggRate", 12)))).andExpect(status().isOk());
        put(l("/flocks/" + batch + "/days/2026-07-03"), owner, Map.of("lines", List.of(Map.of("lineId", sonali, "eggs", 2, "eggRate", 10)))).andExpect(status().isOk());
        long sale = body(post(l("/flocks/" + batch + "/sales"), owner, Map.of("date", "2026-07-05", "heads", 2, "rate", 300, "rateBasis", "HEAD",
                "financialAccountId", cash, "lineId", muscovy)).andExpect(status().isCreated())).get("documentId").asLong();

        String report = l("/flocks/" + batch + "/report?from=2026-07-01&to=2026-07-31");
        JsonNode figures = body(get(report, owner)).get("period");
        JsonNode s = varietyOf(figures, "Sonali"), m = varietyOf(figures, "Muscovy");
        assertThat(s.get("arrived").asInt()).isEqualTo(10);
        assertThat(s.get("died").asInt()).isEqualTo(1);
        assertThat(s.get("closingHeads").asInt()).isEqualTo(9);
        assertThat(s.get("eggs").asInt()).isEqualTo(8);
        money(s.get("eggValue"), "68");                            // 6 x 8 + 2 x 10
        money(s.get("eggRate"), "8.5");
        money(s.get("boughtAmount"), "500");                       // Each from its own line of the one bill
        money(m.get("boughtAmount"), "400");
        assertThat(m.get("sold").asInt()).isEqualTo(2);
        money(m.get("soldAmount"), "600");
        assertThat(m.get("closingHeads").asInt()).isEqualTo(3);
        assertThat(s.get("closingHeads").asInt() + m.get("closingHeads").asInt()).isEqualTo(figures.get("closingHeads").asInt());
        assertThat(s.get("headDays").asLong() + m.get("headDays").asLong()).isEqualTo(figures.get("headDays").asLong());

        // A reversed sale no longer counts for its variety
        post("/api/v1/tenants/" + tenant + "/finance/documents/" + sale + "/reverse", owner, Map.of()).andExpect(status().isOk());
        JsonNode after = varietyOf(body(get(report, owner)).get("period"), "Muscovy");
        assertThat(after.get("sold").asInt()).isZero();
        money(after.get("soldAmount"), "0");
        assertThat(after.get("closingHeads").asInt()).isEqualTo(5);

        // Without finance access the heads and eggs show, not the money
        var worker = newUser("fv-worker");
        addMember(owner, tenant, worker, "WORKER");
        JsonNode seen = varietyOf(body(get(report, worker).andExpect(status().isOk())).get("period"), "Sonali");
        assertThat(seen.get("eggs").asInt()).isEqualTo(8);
        assertThat(seen.get("eggValue").isNull()).isTrue();
        assertThat(seen.get("boughtAmount").isNull()).isTrue();
        assertThat(seen.get("soldAmount").isNull()).isTrue();
    }

    @Test
    void aVarietyOfAnotherBatchIsRefused() throws Exception {
        long batch = startMixedBatch();
        long other = body(post(l("/flocks"), owner, Map.of("name", "Other", "species", "CHICKEN", "purpose", "COUNTRY", "placedOn", "2026-07-01", "heads", 4))
                .andExpect(status().isCreated())).get("id").asLong();
        long otherLine = body(get(l("/flocks/" + other + "/lines"), owner)).get(0).get("id").asLong();
        post(l("/flocks/" + batch + "/movements"), owner, Map.of("date", "2026-07-03", "kind", "DIED", "heads", 1, "lineId", otherLine))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINE_NOT_FOUND"));
        // A batch started the old way has one line and needs no choice
        post(l("/flocks/" + other + "/movements"), owner, Map.of("date", "2026-07-03", "kind", "DIED", "heads", 1)).andExpect(status().isCreated());
    }
}
