package com.smartfarm360.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.inventory.StockTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Assets design, section 6: registering, depreciation runs and their order, disposal, reversal, use, maintenance, costs. */
class AssetRulesTest extends StockTestSupport {
    private String a(String path) { return "/api/v1/tenants/" + tenant + "/assets" + path; }

    @BeforeEach
    void setUp() throws Exception { newFinanceTenant("asset-" + UUID.randomUUID().toString().substring(0, 6)); }

    private JsonNode buy(String name, String date, int amount, Integer life, Integer salvage) throws Exception {
        Map<String, Object> asset = new HashMap<>(Map.of("name", name, "kind", "EQUIPMENT"));
        if (life != null) asset.put("usefulLifeMonths", life);
        if (salvage != null) asset.put("salvageValue", salvage);
        return body(post(a("/purchases"), owner, Map.of("docDate", date, "financialAccountId", cash, "amount", amount, "asset", asset))
                .andExpect(status().isCreated()));
    }

    private JsonNode run(String month) throws Exception { return body(post(a("/depreciation-runs"), owner, Map.of("month", month)).andExpect(status().isCreated())); }

    @Test
    void straightLineRunsGoInOrderAndTheLastMonthTakesTheRemainder() throws Exception {
        long pump = buy("Pump", "2026-01-15", 1000, 3, null).get("id").asLong();
        post(a("/depreciation-runs"), owner, Map.of("month", "2026-03")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPRECIATION_OUT_OF_ORDER"));
        post(a("/depreciation-runs"), owner, Map.of("month", "2026-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_DEPRECIATE"));     // starts the month after it came into use
        List<String> amounts = new ArrayList<>();
        for (String m : List.of("2026-02", "2026-03", "2026-04")) amounts.add(run(m).at("/summary/totalAmount").asText());
        assertThat(amounts).containsExactly("333.33", "333.33", "333.34");
        post(a("/depreciation-runs"), owner, Map.of("month", "2026-04")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPRECIATION_RUN_EXISTS"));
        post(a("/depreciation-runs"), owner, Map.of("month", "2026-05")).andExpect(status().isBadRequest());   // fully depreciated
        money(body(get(a("/" + pump), owner)).get("bookValue"), "0");
        post(a("/depreciation-runs"), owner, Map.of("month", YearMonth.now().plusMonths(1).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FUTURE_MONTH"));
        patch(a("/" + pump), owner, Map.of("usefulLifeMonths", 12)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_ALREADY_DEPRECIATED"));
    }

    @Test
    void salvageValueStopsDepreciationAndTheScheduleShowsWhatIsLeft() throws Exception {
        long id = buy("Sprayer", "2026-01-01", 1200, 10, 200).get("id").asLong();   // 100 a month down to 200
        JsonNode schedule = body(get(a("/" + id + "/schedule"), owner));
        assertThat(schedule).hasSize(10);
        money(schedule.get(9).get("bookValueAfter"), "200");
        run("2026-02");
        JsonNode after = body(get(a("/" + id + "/schedule"), owner));
        assertThat(after.get(0).get("posted").asBoolean()).isTrue();
        assertThat(after).hasSize(10);
    }

    @Test
    void reversalsGoLatestFirstAndNotPastADisposal() throws Exception {
        long id = buy("Weeder", "2026-01-01", 6000, 6, null).get("id").asLong();
        long feb = run("2026-02").at("/summary/id").asLong();
        long mar = run("2026-03").at("/summary/id").asLong();
        post(f("/documents/" + feb + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPRECIATION_LATER_MONTHS"));
        // Disposal in a month already depreciated is refused
        post(a("/" + id + "/disposal"), owner, Map.of("date", "2026-03-20", "kind", "SCRAPPED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPRECIATION_AFTER_DISPOSAL"));
        // Scrapped in April: book value 4,000 is a loss
        JsonNode s = body(post(a("/" + id + "/disposal"), owner, Map.of("date", "2026-04-10", "kind", "SCRAPPED")).andExpect(status().isCreated()));
        assertThat(s.get("status").asText()).isEqualTo("SCRAPPED");
        money(accountBalance("5980"), "4000");
        money(accountBalance("1300"), "0");
        money(accountBalance("1310"), "0");
        post(f("/documents/" + mar + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_DISPOSED"));
        post(a("/depreciation-runs"), owner, Map.of("month", "2026-04")).andExpect(status().isBadRequest());   // nothing left to depreciate
        // Reversing the disposal puts it back in use
        post(f("/documents/" + s.get("disposalDocumentId").asLong() + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        assertThat(body(get(a("/" + id), owner)).get("status").asText()).isEqualTo("IN_USE");
        post(f("/documents/" + mar + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        money(body(get(a("/" + id), owner)).get("accumulated"), "1000");
    }

    @Test
    void saleToABuyerOnCreditPostsAGainAndAReceivable() throws Exception {
        long buyer = party("Neighbour");
        long id = buy("Old pump", "2026-01-01", 5000, 10, null).get("id").asLong();
        run("2026-02");                                                          // 500
        post(a("/" + id + "/disposal"), owner, Map.of("date", "2026-03-05", "kind", "SOLD", "proceeds", 4800, "partyId", buyer)).andExpect(status().isCreated());
        money(accountBalance("4900"), "-300");                                   // credit: gain 300
        money(StreamSupportFind.party(body(get(f("/parties"), owner)), buyer).get("receivable"), "4800");
        post(a("/" + id + "/disposal"), owner, Map.of("date", "2026-03-06", "kind", "SCRAPPED")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_IN_USE"));
    }

    @Test
    void costsInTheAccountsCanBeRegisteredAndRegisteredPurchasesCannotBeReversed() throws Exception {
        long bill = invoice("bills", "2026-02-01", null, cash, line("EQUIPMENT", 9000, null)).at("/summary/id").asLong();
        JsonNode lines = body(get(a("/unregistered"), owner));
        assertThat(lines).hasSize(1);
        money(body(get(a("/reports/reconciliation"), owner)).get("unexplained"), "0");
        post(a(""), owner, Map.of("sourceLineId", lines.get(0).get("lineId").asLong(), "asset", Map.of("name", "No life", "kind", "IRRIGATION")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("USEFUL_LIFE_REQUIRED"));
        long id = body(post(a(""), owner, Map.of("sourceLineId", lines.get(0).get("lineId").asLong(), "asset", Map.of("name", "Drip kit",
                "kind", "IRRIGATION", "usefulLifeMonths", 36))).andExpect(status().isCreated())).get("id").asLong();
        post(a(""), owner, Map.of("sourceLineId", lines.get(0).get("lineId").asLong(), "asset", Map.of("name", "Again", "kind", "IRRIGATION",
                "usefulLifeMonths", 36))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_ALREADY_REGISTERED"));
        post(f("/documents/" + bill + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_REGISTERED"));
        delete(a("/" + id), owner).andExpect(status().isNoContent());
        post(f("/documents/" + bill + "/reverse"), owner, Map.of()).andExpect(status().isOk());
        assertThat(body(get(a("/unregistered"), owner))).isEmpty();
    }

    @Test
    void runningCostsUseAndMaintenanceAreTrackedPerAsset() throws Exception {
        long tractor = buy("Tractor", "2026-01-01", 120000, 120, null).get("id").asLong();
        long diesel = item("Diesel", "INPUT", "litre", Map.of("expenseCategoryId", categoryId("FUEL")));
        stockInvoice("bills", "2026-02-01", null, itemLine(diesel, 100, null, 90));
        record("/issues", Map.of("docDate", "2026-02-05", "assetId", tractor, "lines", List.of(qty(diesel, 20, null))));     // 1,800
        Map<String, Object> repair = new HashMap<>(line("REPAIRS", 2500, null));
        repair.put("assetId", tractor);
        post(f("/bills"), owner, Map.of("docDate", "2026-02-10", "financialAccountId", cash, "lines", List.of(repair))).andExpect(status().isCreated());
        post(a("/" + tractor + "/usage"), owner, Map.of("date", "2026-02-05", "hours", 8)).andExpect(status().isCreated());
        post(a("/" + tractor + "/usage"), owner, Map.of("date", "2026-02-06", "hours", 12)).andExpect(status().isCreated());
        run("2026-02");                                                          // 1,000
        JsonNode c = body(get(a("/" + tractor + "/costs?from=2026-02-01&to=2026-02-28"), owner));
        money(c.get("depreciation"), "1000");
        money(c.get("runningTotal"), "4300");
        money(c.get("hours"), "20");
        money(c.get("costPerHour"), "265");                                     // (1,000 + 4,300) / 20
        // Oil change every 250 hours and service every 180 days
        long plan = body(post(a("/" + tractor + "/maintenance-plans"), owner, Map.of("name", "Oil change", "everyHours", 22, "lastDoneHours", 0))
                .andExpect(status().isCreated())).get("id").asLong();
        assertThat(body(get(a("/" + tractor + "/maintenance-plans"), owner)).get(0).get("status").asText()).isEqualTo("DUE");   // 2 hours left
        post(a("/" + tractor + "/usage"), owner, Map.of("date", "2026-02-07", "hours", 6)).andExpect(status().isCreated());
        assertThat(body(get(a("/maintenance-plans"), owner)).get(0).get("status").asText()).isEqualTo("OVERDUE");
        post(a("/" + tractor + "/maintenance"), owner, Map.of("doneOn", "2026-02-08", "planId", plan, "description", "Oil and filter")).andExpect(status().isCreated());
        JsonNode p = body(get(a("/" + tractor + "/maintenance-plans"), owner)).get(0);
        assertThat(p.get("status").asText()).isEqualTo("OK");
        money(p.get("hoursLeft"), "22");
        post(a("/" + tractor + "/maintenance-plans"), owner, Map.of("name", "Nothing")).andExpect(status().isBadRequest());
    }

    @Test
    void permissionsFollowTheRoles() throws Exception {
        User manager = newUser("asset-manager");
        addMember(owner, tenant, manager, "FARM_MANAGER");
        User viewer = newUser("asset-viewer");
        addMember(owner, tenant, viewer, "VIEWER");
        long id = buy("Pump", "2026-01-01", 1000, 10, null).get("id").asLong();
        get(a(""), viewer).andExpect(status().isOk());
        post(a("/" + id + "/usage"), viewer, Map.of("date", "2026-02-01", "hours", 1)).andExpect(status().isForbidden());
        post(a("/" + id + "/usage"), manager, Map.of("date", "2026-02-01", "hours", 1)).andExpect(status().isCreated());
        post(a("/depreciation-runs"), manager, Map.of("month", "2026-02")).andExpect(status().isForbidden());   // needs FINANCE_MANAGE
    }

    static final class StreamSupportFind {
        static JsonNode party(JsonNode array, long id) {
            for (JsonNode n : array) if (n.get("id").asLong() == id) return n;
            throw new AssertionError("party " + id);
        }
    }

}
