package com.smartfarm360.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Processing and trading design, sections 3–9 and 12: batches, output, cost per kg, the trace, trading, permissions and tenants. */
class ProcessingRulesTest extends FinanceTestSupport {
    private long farm;

    private String t(String path) { return "/api/v1/tenants/" + tenant + path; }

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("pr-" + UUID.randomUUID().toString().substring(0, 6));
        put(t("/modules/PROCESSING"), owner, Map.of("enabled", true)).andExpect(status().isOk());
        farm = newFarm(owner, tenant, "Main farm");
    }

    private long produceItem(String name, boolean lots) throws Exception {
        return body(post(t("/stock/items"), owner, Map.of("name", name + " " + UUID.randomUUID().toString().substring(0, 4), "kind", "PRODUCE", "baseUnit", "kg",
                "trackLots", lots, "saleCategoryId", categoryId("PROCESSED_SALE"))).andExpect(status().isCreated())).get("id").asLong();
    }

    /** A batch for the main item; returns {batch id, cost object id}. */
    private long[] batch(String code, long mainItem) throws Exception {
        JsonNode b = body(post(t("/processing/batches"), owner, Map.of("code", code, "name", "Batch " + code, "kind", "MILLING", "farmId", farm,
                "mainItemId", mainItem, "startedOn", "2026-09-01", "expectedEnd", "2026-09-10")).andExpect(status().isCreated()));
        return new long[]{b.get("id").asLong(), b.get("costObjectId").asLong()};
    }

    private Map<String, Object> out(String role, Long item, String description, int qty) {
        Map<String, Object> m = new HashMap<>(Map.of("date", "2026-09-05", "role", role, "quantity", qty));
        if (item != null) m.put("itemId", item);
        if (description != null) m.put("description", description);
        return m;
    }

    @Test
    void outputRolesLotsAndTheWeightOfSteps() throws Exception {
        long rice = produceItem("Rice", true), bran = produceItem("Bran", false);
        long input = body(post(t("/stock/items"), owner, Map.of("name", "Bags", "kind", "INPUT", "baseUnit", "piece", "expenseCategoryId", categoryId("OTHER_INPUTS")))
                .andExpect(status().isCreated())).get("id").asLong();
        long[] a = batch("RM-A", rice);
        post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("MAIN", bran, null, 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAIN_ITEM_MISMATCH"));
        post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("BYPRODUCT", input, null, 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_FARM_PRODUCE"));
        post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("WASTE", null, null, 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WASTE_DESCRIPTION_REQUIRED"));
        // Only lot-tracked items get a lot from the batch code
        assertThat(body(post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("BYPRODUCT", bran, null, 10)).andExpect(status().isCreated()))
                .get("lotNo").isNull()).isTrue();
        assertThat(body(post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("MAIN", rice, null, 50)).andExpect(status().isCreated()))
                .get("lotNo").asText()).isEqualTo("RM-A-2");
        post(t("/processing/batches/" + a[0] + "/steps"), owner, Map.of("date", "2026-09-02", "kind", "DRYING", "kgIn", 100, "kgOut", 101))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STEP_GAINS_WEIGHT"));
        // Codes are unique, ignoring case, and the code and main product are fixed once output exists
        post(t("/processing/batches"), owner, Map.of("code", "rm-a", "name", "Again", "kind", "DRYING", "farmId", farm, "mainItemId", rice, "startedOn", "2026-09-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_CODE_TAKEN"));
        put(t("/processing/batches/" + a[0]), owner, Map.of("code", "RM-B", "name", "Batch", "kind", "MILLING", "farmId", farm, "mainItemId", rice, "startedOn", "2026-09-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_HAS_OUTPUT"));
    }

    @Test
    void byproductSalesLowerTheMainProductsCostAndReversedOutputDoesNotCount() throws Exception {
        long rice = produceItem("Rice", false), bran = produceItem("Bran", false);
        long[] a = batch("RM-C", rice);
        invoice("bills", "2026-09-02", null, cash, line("LABOUR", 1000, a[1]));
        post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("MAIN", rice, null, 100)).andExpect(status().isCreated());
        JsonNode branOut = body(post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("BYPRODUCT", bran, null, 20)).andExpect(status().isCreated()));
        String report = "/processing/batches/" + a[0] + "/report?from=2026-09-01&to=2026-09-30";
        money(body(get(t(report), owner)).at("/whole/mainCostPerKg"), "10.00");            // Rs 1,000 ÷ 100 kg
        Map<String, Object> sale = new HashMap<>(line("PROCESSED_SALE", 200, a[1]));
        sale.put("itemId", bran);
        sale.put("quantity", 20);
        invoice("sales", "2026-09-06", null, cash, sale);
        money(body(get(t(report), owner)).at("/whole/mainCostPerKg"), "8.00");             // (1,000 − 200) ÷ 100 kg
        // A reversed output no longer counts (the bran is sold, so reverse a fresh one)
        JsonNode extra = body(post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("MAIN", rice, null, 50)).andExpect(status().isCreated()));
        post(f("/documents/" + extra.get("documentId").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        JsonNode w = body(get(t(report), owner)).get("whole");
        money(w.get("mainKg"), "100");
        assertThat(body(get(t("/processing/batches/" + a[0] + "/outputs"), owner)).findValuesAsText("reversed")).contains("true");
        assertThat(branOut.get("reversed").asBoolean()).isFalse();
    }

    @Test
    void ownProduceIntoABatchCarriesItsCostAndAFinishedBatchRefusesMore() throws Exception {
        long paddy = produceItem("Paddy", false), rice = produceItem("Rice", false);
        long[] src = batch("SRC", paddy), a = batch("RM-D", rice);
        invoice("bills", "2026-09-02", null, cash, line("LABOUR", 500, src[1]));
        post(t("/stock/produce"), owner, Map.of("docDate", "2026-09-03", "costObjectId", src[1], "lines", List.of(Map.of("itemId", paddy, "quantity", 100))))
                .andExpect(status().isCreated());
        User manager = newUser("pr-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        Map<String, Object> move = new HashMap<>(Map.of("date", "2026-09-04", "itemId", paddy, "quantity", 40, "fromCostObjectId", src[1], "toCostObjectId", a[1]));
        move.put("costMethod", "NIL");
        post(t("/stock/internal-transfers"), manager, move).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCE_MANAGE_REQUIRED"));
        move.remove("costMethod");
        money(body(post(t("/stock/internal-transfers"), manager, move).andExpect(status().isCreated())).get("costMoved"), "200");   // Rs 500 ÷ 100 kg × 40 kg
        post(t("/processing/batches/" + a[0] + "/finish"), owner, Map.of("date", "2026-09-05")).andExpect(status().isOk());
        post(t("/processing/batches/" + a[0] + "/steps"), owner, Map.of("date", "2026-09-05", "kind", "OTHER")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_FINISHED"));
        post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("MAIN", rice, null, 10)).andExpect(status().isConflict());
        move.put("quantity", 10);
        post(t("/stock/internal-transfers"), owner, move).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_FINISHED"));
        post(t("/processing/batches/" + a[0] + "/reopen"), owner, Map.of()).andExpect(status().isOk());
        post(t("/processing/batches/" + a[0] + "/steps"), owner, Map.of("date", "2026-09-06", "kind", "OTHER", "notes", "Restarted")).andExpect(status().isCreated());
        // Batches past their expected end show on the dashboard
        assertThat(body(get(t("/dashboard"), owner)).toString()).contains("PROCESSING_OVERDUE");
    }

    @Test
    void theTraceFollowsABoughtLotToItsSupplier() throws Exception {
        long groundnut = produceItem("Groundnut", true), oil = produceItem("Oil cake", true);
        long supplier = party("Village trader");
        post(f("/bills"), owner, Map.of("docDate", "2026-09-01", "partyId", supplier, "lines", List.of(Map.of("itemId", groundnut, "quantity", 500, "rate", 70,
                "lotNo", "GN-BUY-1")))).andExpect(status().isCreated());
        long[] a = batch("OP-1", oil);
        post(t("/stock/issues"), owner, Map.of("docDate", "2026-09-02", "costObjectId", a[1], "lines", List.of(Map.of("itemId", groundnut, "quantity", 500, "lotNo", "GN-BUY-1"))))
                .andExpect(status().isCreated());
        post(t("/processing/batches/" + a[0] + "/outputs"), owner, out("MAIN", oil, null, 300)).andExpect(status().isCreated());
        JsonNode back = body(get(t("/processing/trace?lotNo=OP-1-1"), owner)).get(0);
        assertThat(back.at("/inputs/0/origin/kind").asText()).isEqualTo("BOUGHT");
        assertThat(back.at("/inputs/0/origin/partyName").asText()).isEqualTo("Village trader");
        // Bought produce is raw material at its cost: Rs 35,000 for 500 kg
        money(body(get(t("/processing/batches/" + a[0] + "/report?from=2026-09-01&to=2026-09-30"), owner)).at("/whole/rawCostPerKg"), "70.00");
        get(t("/processing/trace?lotNo=NOPE"), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOT_NOT_FOUND"));
    }

    @Test
    void permissionsTheModuleAndTradingWithoutIt() throws Exception {
        long rice = produceItem("Rice", false);
        long[] a = batch("RM-E", rice);
        User viewer = newUser("pr-viewer"), worker = newUser("pr-worker");
        addMember(owner, tenant, viewer, "VIEWER");
        addMember(owner, tenant, worker, "WORKER");
        get(t("/processing/batches/" + a[0]), viewer).andExpect(status().isOk());
        post(t("/processing/batches/" + a[0] + "/steps"), viewer, Map.of("date", "2026-09-02", "kind", "OTHER")).andExpect(status().isForbidden());
        post(t("/processing/batches/" + a[0] + "/steps"), worker, Map.of("date", "2026-09-02", "kind", "CLEANING", "kgIn", 100, "kgOut", 98)).andExpect(status().isCreated());
        // Waste needs no stock permission; product output does
        post(t("/processing/batches/" + a[0] + "/outputs"), worker, out("WASTE", null, "Stones", 2)).andExpect(status().isCreated());
        post(t("/processing/batches/" + a[0] + "/outputs"), worker, out("MAIN", rice, null, 50)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVENTORY_PERMISSION_REQUIRED"));
        assertThat(body(get(t("/processing/batches/" + a[0] + "/report?from=2026-09-01&to=2026-09-30"), worker)).at("/period/costs").isNull()).isTrue();
        get(t("/stock/reports/trading?from=2026-09-01&to=2026-09-30"), worker).andExpect(status().isForbidden());
        put(t("/modules/PROCESSING"), owner, Map.of("enabled", false)).andExpect(status().isOk());
        get(t("/processing/batches"), owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        get(t("/stock/reports/trading?from=2026-09-01&to=2026-09-30"), owner).andExpect(status().isOk());
    }

    @Test
    void anotherFarmBusinesssBatchesAndLotsCannotBeReachedOrReferenced() throws Exception {
        long rice = produceItem("Rice", true);
        long[] mine = batch("MINE", rice);
        long other = body(post("/api/v1/tenants", owner, Map.of("name", "Other mill", "modules", List.of("PROCESSING"))).andExpect(status().isCreated())).get("id").asLong();
        String o = "/api/v1/tenants/" + other;
        long otherFarm = newFarm(owner, other, "Their farm");
        long theirItem = body(post(o + "/stock/items", owner, Map.of("name", "Their rice", "kind", "PRODUCE", "baseUnit", "kg", "trackLots", true))
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode theirs = body(post(o + "/processing/batches", owner, Map.of("code", "THEIRS", "name", "Theirs", "kind", "MILLING", "farmId", otherFarm,
                "mainItemId", theirItem, "startedOn", "2026-09-01")).andExpect(status().isCreated()));
        long theirBatch = theirs.get("id").asLong(), theirCo = theirs.get("costObjectId").asLong();
        long theirStep = body(post(o + "/processing/batches/" + theirBatch + "/steps", owner, Map.of("date", "2026-09-02", "kind", "OTHER")).andExpect(status().isCreated()))
                .get("id").asLong();
        long theirWaste = body(post(o + "/processing/batches/" + theirBatch + "/outputs", owner, out("WASTE", null, "Dust", 1)).andExpect(status().isCreated()))
                .get("id").asLong();
        post(o + "/processing/batches/" + theirBatch + "/outputs", owner, out("MAIN", theirItem, null, 5)).andExpect(status().isCreated());

        get(t("/processing/batches/" + theirBatch), owner).andExpect(status().isNotFound());
        put(t("/processing/batches/" + theirBatch), owner, Map.of("code", "X", "name", "X", "kind", "OTHER", "farmId", farm, "mainItemId", rice, "startedOn", "2026-09-01"))
                .andExpect(status().isNotFound());
        get(t("/processing/batches/" + theirBatch + "/steps"), owner).andExpect(status().isNotFound());
        post(t("/processing/batches/" + theirBatch + "/steps"), owner, Map.of("date", "2026-09-03", "kind", "OTHER")).andExpect(status().isNotFound());
        delete(t("/processing/steps/" + theirStep), owner).andExpect(status().isNotFound());
        delete(t("/processing/outputs/" + theirWaste), owner).andExpect(status().isNotFound());
        post(t("/processing/batches/" + theirBatch + "/outputs"), owner, out("WASTE", null, "Dust", 1)).andExpect(status().isNotFound());
        post(t("/processing/batches/" + theirBatch + "/finish"), owner, Map.of("date", "2026-09-05")).andExpect(status().isNotFound());
        get(t("/processing/batches/" + theirBatch + "/report?from=2026-09-01&to=2026-09-30"), owner).andExpect(status().isNotFound());
        get(t("/processing/trace?lotNo=THEIRS-1"), owner).andExpect(status().isNotFound());
        // Their item, farm and batch cannot be used from my records
        post(t("/processing/batches/" + mine[0] + "/outputs"), owner, out("BYPRODUCT", theirItem, null, 1)).andExpect(status().isNotFound());
        post(t("/processing/batches"), owner, Map.of("code", "B2", "name", "B2", "kind", "OTHER", "farmId", otherFarm, "mainItemId", rice, "startedOn", "2026-09-01"))
                .andExpect(status().isNotFound());
        post(t("/processing/batches"), owner, Map.of("code", "B3", "name", "B3", "kind", "OTHER", "farmId", farm, "mainItemId", theirItem, "startedOn", "2026-09-01"))
                .andExpect(status().isNotFound());
        post(t("/stock/internal-transfers"), owner, Map.of("date", "2026-09-04", "itemId", rice, "quantity", 1, "toCostObjectId", theirCo)).andExpect(status().isNotFound());
        // Nothing of theirs changed, and my list holds only my batch
        assertThat(body(get(o + "/processing/batches/" + theirBatch + "/steps", owner))).hasSize(1);
        assertThat(body(get(t("/processing/batches"), owner)).findValuesAsText("code")).containsExactly("MINE");
        assertThat(body(get(o + "/processing/trace?lotNo=THEIRS-1", owner))).hasSize(1);
    }
}
