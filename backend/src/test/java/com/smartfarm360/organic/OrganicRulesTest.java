package com.smartfarm360.organic;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Organic resources design, sections 3–7 and 10: batches, internal transfers (P17), cost rules, permissions and tenants. */
class OrganicRulesTest extends FinanceTestSupport {
    private long farm;

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("or-" + UUID.randomUUID().toString().substring(0, 6));
        put(t("/modules/ORGANIC"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        farm = newFarm(owner, tenant, "Main farm");
    }

    private long produceItem(String name) throws Exception {
        return body(post(t("/stock/items"), owner, Map.of("name", name + " " + UUID.randomUUID().toString().substring(0, 4), "kind", "PRODUCE", "baseUnit", "kg",
                "saleCategoryId", categoryId("MANURE_SALE"))).andExpect(status().isCreated())).get("id").asLong();
    }

    /** An organic batch; returns {batch id, cost object id}. */
    private long[] batch(String name) throws Exception {
        JsonNode b = body(post(t("/organic/batches"), owner, Map.of("name", name, "kind", "COMPOST", "farmId", farm, "startedOn", "2026-06-01",
                "readyBy", "2026-07-01")).andExpect(status().isCreated()));
        return new long[]{b.get("id").asLong(), b.get("costObjectId").asLong()};
    }

    private void produce(String date, long item, int kg, long costObject) throws Exception {
        post(t("/stock/produce"), owner, Map.of("docDate", date, "costObjectId", costObject, "lines", List.of(Map.of("itemId", item, "quantity", kg))))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> move(String date, long item, int kg, Long from, long to, String method) {
        Map<String, Object> m = new HashMap<>(Map.of("date", date, "itemId", item, "quantity", kg, "toCostObjectId", to));
        if (from != null) m.put("fromCostObjectId", from);
        if (method != null) m.put("costMethod", method);
        return m;
    }

    private String costs(long costObject) throws Exception {
        return body(get(f("/reports/income-statement?from=2026-01-01&to=2026-10-31&costObjectId=" + costObject), owner)).get("operatingCostTotal").asText();
    }

    @Test
    void averageCostIsCappedAndTheSourceMustHaveProducedTheQuantity() throws Exception {
        long[] a = batch("Pit A"), b = batch("Pit B");
        long compost = produceItem("Compost");
        invoice("bills", "2026-06-05", null, cash, line("LABOUR", 1000, a[1]));
        produce("2026-06-20", compost, 100, a[1]);
        JsonNode first = body(post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 60, a[1], b[1], null)).andExpect(status().isCreated()));
        money(first.get("costMoved"), "600");                         // Rs 1,000 ÷ 100 kg × 60 kg
        post(t("/stock/internal-transfers"), owner, move("2026-06-22", compost, 50, a[1], b[1], null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_QUANTITY_EXCEEDED"));
        // More output without more cost lowers the average; the move is capped at the Rs 400 not yet moved
        produce("2026-06-25", compost, 100, a[1]);
        JsonNode second = body(post(t("/stock/internal-transfers"), owner, move("2026-06-26", compost, 100, a[1], b[1], null)).andExpect(status().isCreated()));
        money(second.get("unitCost"), "5");
        money(second.get("costMoved"), "400");
        money(new com.fasterxml.jackson.databind.node.TextNode(costs(a[1])), "0");
        money(new com.fasterxml.jackson.databind.node.TextNode(costs(b[1])), "1000");
    }

    @Test
    void reversingATransferRestoresBothActivitiesAndTheStock() throws Exception {
        long[] a = batch("Pit C"), b = batch("Pit D");
        long compost = produceItem("Compost");
        invoice("bills", "2026-06-05", null, cash, line("LABOUR", 2000, a[1]));
        produce("2026-06-20", compost, 200, a[1]);
        JsonNode tr = body(post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 50, a[1], b[1], null)).andExpect(status().isCreated()));
        money(new com.fasterxml.jackson.databind.node.TextNode(costs(b[1])), "500");
        money(body(get(t("/stock/items/" + compost), owner)).get("quantity"), "150");
        post(f("/documents/" + tr.get("documentId").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        money(new com.fasterxml.jackson.databind.node.TextNode(costs(a[1])), "2000");
        money(new com.fasterxml.jackson.databind.node.TextNode(costs(b[1])), "0");
        money(body(get(t("/stock/items/" + compost), owner)).get("quantity"), "200");
        // A reversed transfer no longer counts against the source's quantity
        post(t("/stock/internal-transfers"), owner, move("2026-06-22", compost, 200, a[1], b[1], null)).andExpect(status().isCreated());
    }

    @Test
    void transfersNeedOwnProduceAndARealTarget() throws Exception {
        long[] a = batch("Pit E");
        long compost = produceItem("Compost");
        produce("2026-06-20", compost, 100, a[1]);
        long bought = body(post(t("/stock/items"), owner, Map.of("name", "Urea", "kind", "INPUT", "baseUnit", "kg", "expenseCategoryId", categoryId("FERTILIZER")))
                .andExpect(status().isCreated())).get("id").asLong();
        long overhead = jdbc.queryForObject("select id from sf_cost_objects where tenant_id = ? and kind = 'OVERHEAD'", Long.class, tenant);
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", bought, 1, null, a[1], null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_FARM_PRODUCE"));
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 1, a[1], overhead, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TARGET_REQUIRED"));
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 1, a[1], a[1], null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACTIVITY"));
        long[] b = batch("Pit F");
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 1, null, b[1], "AVERAGE_COST")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_REQUIRED"));
        // Without a source the produce moves at nil
        JsonNode plain = body(post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 5, null, b[1], null)).andExpect(status().isCreated()));
        assertThat(plain.get("method").asText()).isEqualTo("NIL");
        assertThat(plain.get("fromCostObjectId").isNull()).isTrue();
    }

    @Test
    void harvestsThatTrackLotsAreMovedLotByLot() throws Exception {
        long[] a = batch("Pit M");
        long paddy = body(post(t("/stock/items"), owner, Map.of("name", "Paddy " + UUID.randomUUID().toString().substring(0, 4), "kind", "PRODUCE", "baseUnit", "kg",
                "trackLots", true)).andExpect(status().isCreated())).get("id").asLong();
        post(t("/stock/produce"), owner, Map.of("docDate", "2026-06-20", "lines", List.of(Map.of("itemId", paddy, "quantity", 100, "lotNo", "SAMBA-H1"))))
                .andExpect(status().isCreated());
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", paddy, 50, null, a[1], null)).andExpect(status().isBadRequest());
        Map<String, Object> withLot = move("2026-06-21", paddy, 50, null, a[1], null);
        withLot.put("lotNo", "SAMBA-H1");
        post(t("/stock/internal-transfers"), owner, withLot).andExpect(status().isCreated());
        money(body(get(t("/stock/items/" + paddy), owner)).get("quantity"), "50");
    }

    @Test
    void onlyAnOwnerOrAccountantChangesHowCostMoves() throws Exception {
        long[] a = batch("Pit G"), b = batch("Pit H");
        long compost = produceItem("Compost");
        invoice("bills", "2026-06-05", null, cash, line("LABOUR", 1000, a[1]));
        produce("2026-06-20", compost, 100, a[1]);
        User manager = newUser("or-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        // The default for batch output is average cost; a farm manager may record it but not change it
        post(t("/stock/internal-transfers"), manager, move("2026-06-21", compost, 10, a[1], b[1], "NIL")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINANCE_MANAGE_REQUIRED"));
        money(body(post(t("/stock/internal-transfers"), manager, move("2026-06-21", compost, 10, a[1], b[1], null)).andExpect(status().isCreated()))
                .get("costMoved"), "100");
        // The owner may move batch output at nil
        money(body(post(t("/stock/internal-transfers"), owner, move("2026-06-21", compost, 10, a[1], b[1], "NIL")).andExpect(status().isCreated()))
                .get("costMoved"), "0");
    }

    @Test
    void aFinishedBatchRefusesInputsUntilReopened() throws Exception {
        long[] a = batch("Pit I"), b = batch("Pit J");
        long compost = produceItem("Compost");
        post(t("/organic/batches/" + a[0] + "/logs"), owner, Map.of("date", "2026-06-02", "kind", "ADDED", "kg", 40, "notes", "Leaves")).andExpect(status().isCreated());
        post(t("/organic/batches/" + a[0] + "/logs"), owner, Map.of("date", "2026-06-02", "kind", "ADDED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOG_EMPTY"));
        JsonNode out = body(post(t("/organic/batches/" + a[0] + "/outputs"), owner, Map.of("date", "2026-06-30", "itemId", compost, "quantity", 20))
                .andExpect(status().isCreated()));
        assertThat(out.get("quantity").asInt()).isEqualTo(20);
        assertThat(body(get(t("/organic/batches/" + a[0]), owner)).get("status").asText()).isEqualTo("READY");
        post(t("/organic/batches/" + a[0] + "/finish"), owner, Map.of("date", "2026-07-01")).andExpect(status().isOk());
        post(t("/organic/batches/" + a[0] + "/logs"), owner, Map.of("date", "2026-07-02", "kind", "TURNED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FINISHED"));
        produce("2026-07-01", compost, 10, b[1]);
        post(t("/stock/internal-transfers"), owner, move("2026-07-02", compost, 5, b[1], a[1], null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FINISHED"));
        post(t("/organic/batches/" + a[0] + "/reopen"), owner, Map.of()).andExpect(status().isOk());
        post(t("/stock/internal-transfers"), owner, move("2026-07-02", compost, 5, b[1], a[1], null)).andExpect(status().isCreated());
        JsonNode r = body(get(t("/organic/batches/" + a[0] + "/report?from=2026-06-01&to=2026-07-31"), owner)).get("whole");
        money(r.get("inputKg"), "45");                                 // 40 kg of leaves logged and 5 kg moved in
        money(r.get("output"), "20");
        // Batches past their ready date show on the dashboard
        assertThat(body(get(t("/dashboard"), owner)).toString()).contains("ORGANIC_OVERDUE");
    }

    @Test
    void permissionsAndTheModuleGateBatchesButNotTransfers() throws Exception {
        long[] a = batch("Pit K"), b = batch("Pit L");
        long compost = produceItem("Compost");
        produce("2026-06-20", compost, 10, a[1]);
        User viewer = newUser("or-viewer"), worker = newUser("or-worker");
        addMember(owner, tenant, viewer, "VIEWER");
        addMember(owner, tenant, worker, "WORKER");
        get(t("/organic/batches/" + a[0]), viewer).andExpect(status().isOk());
        post(t("/organic/batches/" + a[0] + "/logs"), viewer, Map.of("date", "2026-06-02", "kind", "TURNED")).andExpect(status().isForbidden());
        post(t("/organic/batches/" + a[0] + "/logs"), worker, Map.of("date", "2026-06-02", "kind", "TURNED")).andExpect(status().isCreated());
        JsonNode r = body(get(t("/organic/batches/" + a[0] + "/report?from=2026-06-01&to=2026-06-30"), worker)).get("period");
        assertThat(r.get("costs").isNull()).isTrue();
        // With the module off, batches are refused but own produce can still move between activities (L12)
        put(t("/modules/ORGANIC"), owner, Map.of("enabled", false)).andExpect(status().isOk());
        get(t("/organic/batches"), owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        post(t("/stock/internal-transfers"), owner, move(LocalDate.of(2026, 6, 21).toString(), compost, 5, a[1], b[1], null)).andExpect(status().isCreated());
    }

    @Test
    void anotherFarmBusinesssBatchesAndActivitiesCannotBeReachedOrReferenced() throws Exception {
        long[] mine = batch("Mine");
        long myCompost = produceItem("Compost");
        produce("2026-06-20", myCompost, 10, mine[1]);
        long other = body(post("/api/v1/tenants", owner, Map.of("name", "Other organic farm", "modules", List.of("ORGANIC"))).andExpect(status().isCreated()))
                .get("id").asLong();
        String o = "/api/v1/tenants/" + other;
        long otherFarm = newFarm(owner, other, "Their farm");
        JsonNode theirs = body(post(o + "/organic/batches", owner, Map.of("name", "Theirs", "kind", "VERMICOMPOST", "farmId", otherFarm, "startedOn", "2026-06-01"))
                .andExpect(status().isCreated()));
        long theirBatch = theirs.get("id").asLong(), theirCo = theirs.get("costObjectId").asLong();
        long theirLog = body(post(o + "/organic/batches/" + theirBatch + "/logs", owner, Map.of("date", "2026-06-02", "kind", "TURNED")).andExpect(status().isCreated()))
                .get("id").asLong();
        long theirItem = body(post(o + "/stock/items", owner, Map.of("name", "Their compost", "kind", "PRODUCE", "baseUnit", "kg")).andExpect(status().isCreated()))
                .get("id").asLong();

        get(t("/organic/batches/" + theirBatch), owner).andExpect(status().isNotFound());
        put(t("/organic/batches/" + theirBatch), owner, Map.of("name", "X", "kind", "COMPOST", "farmId", farm, "startedOn", "2026-06-01")).andExpect(status().isNotFound());
        get(t("/organic/batches/" + theirBatch + "/logs"), owner).andExpect(status().isNotFound());
        post(t("/organic/batches/" + theirBatch + "/logs"), owner, Map.of("date", "2026-06-03", "kind", "TURNED")).andExpect(status().isNotFound());
        delete(t("/organic/logs/" + theirLog), owner).andExpect(status().isNotFound());
        post(t("/organic/batches/" + theirBatch + "/finish"), owner, Map.of("date", "2026-06-30")).andExpect(status().isNotFound());
        get(t("/organic/batches/" + theirBatch + "/report?from=2026-06-01&to=2026-06-30"), owner).andExpect(status().isNotFound());
        post(t("/organic/batches/" + theirBatch + "/outputs"), owner, Map.of("date", "2026-06-30", "itemId", myCompost, "quantity", 1)).andExpect(status().isNotFound());
        // Their item, farm and activities cannot be used from my records
        post(t("/organic/batches/" + mine[0] + "/outputs"), owner, Map.of("date", "2026-06-30", "itemId", theirItem, "quantity", 1)).andExpect(status().isNotFound());
        post(t("/organic/batches"), owner, Map.of("name", "Borrowed", "kind", "COMPOST", "farmId", otherFarm, "startedOn", "2026-06-01")).andExpect(status().isNotFound());
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", myCompost, 1, mine[1], theirCo, null)).andExpect(status().isNotFound());
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", myCompost, 1, theirCo, mine[1], null)).andExpect(status().isNotFound());
        post(t("/stock/internal-transfers"), owner, move("2026-06-21", theirItem, 1, null, mine[1], null)).andExpect(status().isNotFound());
        get(t("/stock/internal-transfers?from=2026-06-01&to=2026-06-30&costObjectId=" + theirCo), owner).andExpect(status().isNotFound());
        // Nothing of theirs changed, and my list holds only my batch
        assertThat(body(get(o + "/organic/batches/" + theirBatch + "/logs", owner))).hasSize(1);
        assertThat(body(get(t("/organic/batches"), owner)).findValuesAsText("name")).containsExactly("Mine");
    }
}
