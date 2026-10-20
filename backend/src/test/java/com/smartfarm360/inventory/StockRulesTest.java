package com.smartfarm360.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Stock movement design, sections 2–6 and 9: units, valuation, negative stock, lots, records, reversal, returns, exports. */
class StockRulesTest extends StockTestSupport {
    private long urea;
    private long supplier;

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("stock-" + UUID.randomUUID().toString().substring(0, 6));
        supplier = party("Agro depot");
        urea = item("Urea", "INPUT", "kg", Map.of("packUnits", List.of(Map.of("name", "bag", "factor", 45)), "reorderLevel", 50));
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class, tenant); }

    @Test
    void unitsConvertToTheBaseUnitAndOnlyMatchingUnitsAreAccepted() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 2, "bag", 270), itemLine(urea, 0.5, "quintal", 600), itemLine(urea, 500, "g", 0.008));
        money(stockItem(urea).get("quantity"), "140.5");   // 90 + 50 + 0.5 kg
        money(stockItem(urea).get("value"), "844.00");      // 540 + 300 + 4
        post(f("/bills"), owner, Map.of("docDate", "2026-07-01", "financialAccountId", cash, "lines", List.of(itemLine(urea, 1, "litre", 10))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNIT_NOT_ALLOWED"));
        post(s("/items/" + urea + "/units"), owner, Map.of("name", "kg", "factor", 2))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PACK_UNIT_NAME_RESERVED"));
        post(s("/items"), owner, Map.of("name", "urea", "kind", "INPUT", "baseUnit", "kg"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_ITEM_EXISTS"));
        post(s("/items"), owner, Map.of("name", "Land", "kind", "INPUT", "baseUnit", "acre"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNIT_NOT_ALLOWED"));
    }

    @Test
    void stockCannotGoNegativeAndARefusedRecordWritesNothing() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 6));
        long docs = count("select count(*) from sf_finance_documents where tenant_id = ?");
        post(s("/issues"), owner, Map.of("docDate", "2026-07-02", "lines", List.of(qty(urea, 4, null), qty(urea, 7, null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        post(f("/sales"), owner, Map.of("docDate", "2026-07-02", "financialAccountId", cash,
                "lines", List.of(Map.of("itemId", urea, "quantity", 11, "rate", 10, "categoryId", categoryId("OTHER_INCOME")))))
                .andExpect(status().isConflict());
        assertThat(count("select count(*) from sf_finance_documents where tenant_id = ?")).isEqualTo(docs);
        assertThat(count("select count(*) from sf_stock_movements where tenant_id = ?")).isEqualTo(1);
        money(stockItem(urea).get("quantity"), "10");
    }

    @Test
    void theLastUnitOutTakesTheRemainingValueExactly() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, Map.of("itemId", urea, "quantity", 3, "amount", 10));
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            values.add(record("/issues", Map.of("docDate", "2026-07-02", "lines", List.of(qty(urea, 1, null)))).at("/document/summary/totalAmount").asText());
        }
        assertThat(values).containsExactly("3.33", "3.34", "3.33");
        money(stockItem(urea).get("value"), "0");
        money(accountBalance("5000"), "10");
    }

    @Test
    void transfersMoveQuantityBetweenLocationsWithoutAJournal() throws Exception {
        long main = defaultLocation();
        long shed = id(post(s("/locations"), owner, Map.of("name", "Field shed")).andExpect(status().isCreated()));
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 100, null, 6));
        JsonNode t = record("/transfers", Map.of("docDate", "2026-07-02", "fromLocationId", main, "toLocationId", shed, "lines", List.of(qty(urea, 40, null))));
        money(t.at("/document/summary/totalAmount"), "0");
        assertThat(count("select count(*) from sf_journal_entries e join sf_finance_documents d on d.id = e.document_id " +
                "where d.tenant_id = ? and d.doc_type = 'STOCK_TRANSFER'")).isZero();
        post(s("/issues"), owner, Map.of("docDate", "2026-07-03", "locationId", shed, "lines", List.of(qty(urea, 41, null))))
                .andExpect(status().isConflict());
        JsonNode onHand = body(get(s("/reports/on-hand?locationId=" + shed), owner));
        money(onHand.at("/items/0/locations/0/quantity"), "40");
        money(stockItem(urea).get("value"), "600.00");
        // A location with stock cannot be closed; the default cannot be closed at all
        patch(s("/locations/" + shed), owner, Map.of("active", false)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCATION_HAS_STOCK"));
        patch(s("/locations/" + main), owner, Map.of("active", false)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEFAULT_LOCATION"));
        // Reversing the transfer moves it back
        post(f("/documents/" + t.at("/document/summary/id").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        assertThat(body(get(s("/reports/on-hand?locationId=" + shed), owner)).get("items").size()).isZero();
    }

    @Test
    void reversingPutsStockBackAtItsOriginalValueAndRefusesWhatWasAlreadyUsed() throws Exception {
        long first = stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 10)).at("/summary/id").asLong();
        stockInvoice("bills", "2026-07-02", supplier, itemLine(urea, 10, null, 20));
        JsonNode issue = record("/issues", Map.of("docDate", "2026-07-03", "lines", List.of(qty(urea, 15, null))));
        money(issue.at("/document/summary/totalAmount"), "225.00");
        // The first bill's 10 kg cannot leave: only 5 are left
        post(f("/documents/" + first + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_ALREADY_USED"));
        // Reversing the issue brings the 15 kg back at 225.00
        post(f("/documents/" + issue.at("/document/summary/id").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        money(stockItem(urea).get("value"), "300.00");
        money(accountBalance("5000"), "0");
        // Now the first bill can be reversed; its 10 kg leave at the 100.00 they came in with
        record("/issues", Map.of("docDate", "2026-07-04", "lines", List.of(qty(urea, 10, null))));   // 150.00 at average 15
        post(f("/documents/" + first + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        // The last 10 kg held 150.00; taking them out at 100.00 would leave value without stock, so 50.00 is corrected to farm inputs
        money(stockItem(urea).get("quantity"), "0");
        money(stockItem(urea).get("value"), "0");
        money(accountBalance("5000"), "200.00");
        money(accountBalance("1200"), "0");
    }

    @Test
    void salesTakeStockAtAverageCostAndDraftsMoveNothingUntilPosted() throws Exception {
        long rice = item("Rice (for resale)", "RESALE", "kg", Map.of("saleCategoryId", categoryId("PROCESSED_SALE")));
        stockInvoice("bills", "2026-07-01", supplier, itemLine(rice, 100, null, 40));
        Map<String, Object> draft = new HashMap<>(Map.of("docDate", "2026-07-05", "partyId", supplier, "post", false,
                "lines", List.of(itemLine(rice, 30, null, 55))));
        long id = id(post(f("/sales"), owner, draft).andExpect(status().isCreated()));
        money(stockItem(rice).get("quantity"), "100");
        JsonNode posted = body(post(f("/documents/" + id + "/post"), owner, Map.of()).andExpect(status().isOk()));
        money(posted.at("/movements/0/value"), "-1200.00");
        money(accountBalance("5700"), "1200.00");
        JsonNode pl = report("income-statement?from=2026-07-01&to=2026-07-31");
        money(pl.get("operatingRevenueTotal"), "1650");
        money(pl.get("operatingCostTotal"), "1200.00");
        // The cash-basis view counts money, not stock: nothing received yet, and cost of goods sold is not cash
        money(report("income-statement?from=2026-07-01&to=2026-07-31&basis=CASH").get("operatingCostTotal"), "0");
        // Reversal brings the stock back and removes the cost of goods sold
        post(f("/documents/" + id + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        money(stockItem(rice).get("quantity"), "100");
        money(accountBalance("5700"), "0");
    }

    @Test
    void lotsAreRequiredWhenTrackedAndExpiredLotsWarnButAreAllowed() throws Exception {
        long seed = item("Hybrid seed", "INPUT", "kg", Map.of("trackLots", true));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-01", "partyId", supplier, "lines", List.of(itemLine(seed, 10, null, 500))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOT_REQUIRED"));
        Map<String, Object> old = itemLine(seed, 5, null, 500);
        old.put("lotNo", "L-OLD");
        old.put("lotExpiry", "2026-07-10");
        Map<String, Object> fresh = itemLine(seed, 5, null, 520);
        fresh.put("lotNo", "L-NEW");
        fresh.put("lotExpiry", "2027-06-30");
        stockInvoice("bills", "2026-07-01", supplier, fresh, old);
        JsonNode lots = body(get(s("/items/" + seed + "/lots"), owner));
        assertThat(lots.get(0).get("lotNo").asText()).as("first-expiring lot first").isEqualTo("L-OLD");
        post(s("/issues"), owner, Map.of("docDate", "2026-07-15", "lines", List.of(qty(seed, 1, null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOT_REQUIRED"));
        Map<String, Object> line = qty(seed, 6, null);
        line.put("lotNo", "L-OLD");
        post(s("/issues"), owner, Map.of("docDate", "2026-07-15", "lines", List.of(line))).andExpect(status().isConflict());
        line.put("quantity", 2);
        JsonNode issue = record("/issues", Map.of("docDate", "2026-07-15", "lines", List.of(line)));
        assertThat(issue.get("warnings").get(0).asText()).startsWith("LOT_EXPIRED");
        Map<String, Object> notTracked = qty(urea, 1, null);
        notTracked.put("lotNo", "X");
        post(s("/issues"), owner, Map.of("docDate", "2026-07-15", "lines", List.of(notTracked)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOT_NOT_TRACKED"));
    }

    @Test
    void farmProduceComesInAtNilValueAndOnlyForProduceItems() throws Exception {
        long milk = item("Milk", "PRODUCE", "litre", Map.of());
        JsonNode r = record("/produce", Map.of("docDate", "2026-07-01", "lines", List.of(qty(milk, 120, null))));
        money(r.at("/document/summary/totalAmount"), "0");
        money(stockItem(milk).get("value"), "0");
        post(s("/produce"), owner, Map.of("docDate", "2026-07-01", "lines", List.of(qty(urea, 1, null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_FARM_PRODUCE"));
        // Spoiled milk costs nothing in stock terms
        record("/wastage", Map.of("docDate", "2026-07-02", "reason", "Curdled", "lines", List.of(qty(milk, 5, null))));
        money(accountBalance("5980"), "0");
    }

    @Test
    void countsPostShortagesAndSurplusesToLosses() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 100, null, 6));
        record("/counts", Map.of("docDate", "2026-07-10", "lines", List.of(Map.of("itemId", urea, "countedQuantity", 95))));
        money(accountBalance("5980"), "30.00");
        record("/counts", Map.of("docDate", "2026-07-11", "lines", List.of(Map.of("itemId", urea, "countedQuantity", 97))));
        money(accountBalance("5980"), "18.00");
        money(stockItem(urea).get("quantity"), "97");
        post(s("/counts"), owner, Map.of("docDate", "2026-07-11", "lines", List.of(Map.of("itemId", urea, "countedQuantity", 1),
                Map.of("itemId", urea, "countedQuantity", 2)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_LINE"));
    }

    @Test
    void returnsToSuppliersReduceWhatWeOweAndPriceDifferencesAdjustFarmInputs() throws Exception {
        long bill = stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 100, null, 6)).at("/summary/id").asLong();
        JsonNode ret = record("/purchase-returns", Map.of("docDate", "2026-07-05", "partyId", supplier, "originalDocumentId", bill,
                "lines", List.of(Map.of("itemId", urea, "quantity", 10, "amount", 55))));
        assertThat(ret.at("/document/summary/number").asText()).startsWith("PRET/");
        money(ret.at("/document/allocations/0/amount"), "55.00");
        money(stockItem(urea).get("quantity"), "90");
        money(stockItem(urea).get("value"), "540.00");
        money(accountBalance("5000"), "5.00");   // credited 55 for stock carried at 60
        JsonNode party = StreamSupport.find(body(get(f("/parties"), owner)), supplier);
        money(party.get("payable"), "545.00");
        money(body(get(f("/documents/" + bill), owner)).at("/summary/openAmount"), "545.00");
    }

    @Test
    void customerReturnsComeBackAtTheCostTheyLeftWith() throws Exception {
        long raman = party("Raman");
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 10));
        long sale = stockInvoice("sales", "2026-07-02", raman, withCategory(itemLine(urea, 4, null, 15), "OTHER_INCOME")).at("/summary/id").asLong();
        stockInvoice("bills", "2026-07-03", supplier, itemLine(urea, 6, null, 20));   // 12 kg now carried at 180.00
        JsonNode ret = record("/sale-returns", Map.of("docDate", "2026-07-04", "originalDocumentId", sale,
                "lines", List.of(Map.of("itemId", urea, "quantity", 1, "amount", 15))));
        money(ret.at("/document/movements/0/value"), "10.00");
        money(accountBalance("5700"), "30.00");
        money(body(get(f("/documents/" + sale), owner)).at("/summary/openAmount"), "45.00");
        post(s("/sale-returns"), owner, Map.of("docDate", "2026-07-04", "originalDocumentId", sale,
                "lines", List.of(Map.of("itemId", urea, "quantity", 4, "amount", 0))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RETURN_EXCEEDS_SALE"));
        record("/sale-returns", Map.of("docDate", "2026-07-05", "originalDocumentId", sale, "lines", List.of(Map.of("itemId", urea, "quantity", 3, "amount", 45))));
        money(accountBalance("5700"), "0");
        money(body(get(f("/documents/" + sale), owner)).at("/summary/openAmount"), "0");
    }

    @Test
    void openingStockIsPartOfTheOpeningBalanceRecord() throws Exception {
        long paddy = item("Paddy", "PRODUCE", "kg", Map.of());
        post(f("/opening-balances"), owner, Map.of("docDate", "2026-04-01", "lines", List.of(
                Map.of("kind", "STOCK", "itemId", urea, "quantity", 20, "unit", "bag", "amount", 5400),
                Map.of("kind", "STOCK", "itemId", paddy, "quantity", 500, "amount", 0)))).andExpect(status().isCreated());
        money(stockItem(urea).get("quantity"), "900");
        money(accountBalance("3900"), "-5400.00");
        money(stockItem(paddy).get("quantity"), "500");
    }

    @Test
    void retriesWithTheSameKeyRecordOnce() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 6));
        Map<String, Object> body = Map.of("docDate", "2026-07-02", "lines", List.of(qty(urea, 2, null)));
        for (int i = 0; i < 2; i++) {
            mvc.perform(MockMvcRequestBuilders.post(s("/issues")).header("Authorization", "Bearer " + owner.token()).header("Idempotency-Key", "issue-1")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isCreated());
        }
        money(stockItem(urea).get("quantity"), "8");
        mvc.perform(MockMvcRequestBuilders.post(s("/issues")).header("Authorization", "Bearer " + owner.token()).header("Idempotency-Key", "issue-1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("docDate", "2026-07-02", "lines", List.of(qty(urea, 3, null))))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentIssuesCannotTakeMoreThanIsInStock() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 6));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return post(s("/issues"), owner, Map.of("docDate", "2026-07-02", "lines", List.of(qty(urea, 6, null)))).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        money(stockItem(urea).get("quantity"), "4");
    }

    @Test
    void lockedPeriodsRefuseStockRecords() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 6));
        put(f("/settings"), owner, Map.of("lockDate", "2026-07-31", "fiscalYearStartMonth", 4)).andExpect(status().isOk());
        post(s("/issues"), owner, Map.of("docDate", "2026-07-15", "lines", List.of(qty(urea, 1, null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_LOCKED"));
    }

    @Test
    void workersAndViewersCannotMoveStockButViewersCanSeeIt() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 10, null, 6));
        User viewer = newUser("stock-viewer");
        addMember(owner, tenant, viewer, "VIEWER");
        User worker = newUser("stock-worker");
        addMember(owner, tenant, worker, "WORKER");
        get(s("/reports/on-hand"), viewer).andExpect(status().isOk());
        post(s("/issues"), viewer, Map.of("docDate", "2026-07-02", "lines", List.of(qty(urea, 1, null)))).andExpect(status().isForbidden());
        get(s("/items"), worker).andExpect(status().isForbidden());
        post(s("/items"), worker, Map.of("name", "x", "kind", "INPUT", "baseUnit", "kg")).andExpect(status().isForbidden());
        money(stockItem(urea).get("quantity"), "10");
    }

    @Test
    void alertsListLowStockAndExpiringLots() throws Exception {
        stockInvoice("bills", "2026-07-01", supplier, itemLine(urea, 40, null, 6));
        long seed = item("Seed", "INPUT", "kg", Map.of("trackLots", true));
        Map<String, Object> l = itemLine(seed, 1, null, 100);
        l.put("lotNo", "S1");
        l.put("lotExpiry", LocalDate.now().plusDays(10).toString());
        stockInvoice("bills", LocalDate.now().toString(), supplier, l);
        JsonNode alerts = body(get(s("/reports/alerts"), owner));
        assertThat(alerts.get("lowStock").get(0).get("id").asLong()).isEqualTo(urea);
        assertThat(alerts.get("expiringLots").get(0).get("lotNo").asText()).isEqualTo("S1");
    }

    @Test
    void csvExportsAreSafeToOpenInSpreadsheets() throws Exception {
        long odd = item("=HYPERLINK(\"x\")", "INPUT", "kg", Map.of());
        stockInvoice("bills", "2026-07-01", supplier, itemLine(odd, 1, null, 6));
        String csv = get(s("/reports/on-hand.csv"), owner).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("stock-on-hand.csv")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        String movements = get(s("/items/" + odd + "/movements.csv"), owner).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(movements).contains("PURCHASE");
    }

    private Map<String, Object> withCategory(Map<String, Object> line, String code) {
        line.put("categoryId", categoryId(code));
        return line;
    }

    /** Finds a party by id in a JSON array. */
    static final class StreamSupport {
        static JsonNode find(JsonNode array, long id) {
            for (JsonNode n : array) if (n.get("id").asLong() == id) return n;
            throw new AssertionError("No element with id " + id);
        }
    }
}
