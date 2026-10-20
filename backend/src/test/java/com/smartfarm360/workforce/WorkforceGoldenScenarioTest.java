package com.smartfarm360.workforce;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/workforce/workforce-and-assets.md, section 7, recorded through the API. Every figure the
 * document promises is asserted here; if this test changes, the document must change too.
 */
class WorkforceGoldenScenarioTest extends FinanceTestSupport {

    private String w(String path) { return "/api/v1/tenants/" + tenant + "/workforce" + path; }

    private String a(String path) { return "/api/v1/tenants/" + tenant + "/assets" + path; }

    private long worker(String name, String payType, int rate) throws Exception {
        return body(post(w("/workers"), owner, Map.of("name", name, "payType", payType, "rate", rate, "joinedOn", "2026-06-01"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void attendance(LocalDate day, List<Map<String, Object>> entries) throws Exception {
        put(w("/attendance/" + day), owner, Map.of("entries", entries)).andExpect(status().isOk());
    }

    private static Map<String, Object> entry(long worker, String status, Long costObject) {
        Map<String, Object> m = new HashMap<>(Map.of("workerId", worker, "status", status));
        if (costObject != null) m.put("costObjectId", costObject);
        return m;
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).filter(x -> value.equals(x.path(field).asText())).findFirst().orElseThrow();
    }

    @Test
    void julyExampleMatchesTheWorkforceAndAssetsDesign() throws Exception {
        newFinanceTenant("workforce-golden");
        long dealer = party("Tractor dealer");
        long farm = newFarm(owner, tenant, "Main farm");
        long parcel = body(post("/api/v1/tenants/" + tenant + "/farms/" + farm + "/land-parcels", owner, Map.of("name", "North field", "area", 2))).get("id").asLong();
        long crop = body(post("/api/v1/tenants/" + tenant + "/crops", owner, Map.of("name", "Paddy"))).get("id").asLong();
        long samba = body(post("/api/v1/tenants/" + tenant + "/cultivation-cycles", owner, Map.of("farmId", farm, "landParcelId", parcel, "cropId", crop,
                "name", "Samba 2026", "plannedSowingDate", "2026-06-15"))).get("costObjectId").asLong();

        // Assets: tractor on credit, a milch cow for cash, and a power tiller already depreciated through June 2026
        JsonNode tractor = body(post(a("/purchases"), owner, Map.of("docDate", "2026-04-01", "partyId", dealer, "amount", 600000,
                "asset", Map.of("name", "Tractor", "kind", "VEHICLE", "usefulLifeMonths", 120, "salvageValue", 60000))).andExpect(status().isCreated()));
        money(tractor.get("monthlyDepreciation"), "4500.00");
        post(a("/purchases"), owner, Map.of("docDate", "2026-05-01", "financialAccountId", cash, "amount", 60000,
                "asset", Map.of("name", "Milch cow", "kind", "BREEDING_ANIMAL"))).andExpect(status().isCreated());
        post(f("/opening-balances"), owner, Map.of("docDate", "2026-06-30", "lines", List.of(
                Map.of("kind", "ASSET", "description", "Power tiller", "amount", 120000),
                Map.of("kind", "CASH", "financialAccountId", cash, "amount", 50000)))).andExpect(status().isCreated());
        post(f("/depreciation"), owner, Map.of("docDate", "2026-06-30", "amount", 46000, "notes", "Tiller, Aug 2024 - Jun 2026"))
                .andExpect(status().isCreated());
        long tillerLine = body(get(a("/unregistered"), owner)).get(0).get("lineId").asLong();
        long tiller = body(post(a(""), owner, Map.of("sourceLineId", tillerLine, "asset", Map.of("name", "Power tiller", "kind", "EQUIPMENT",
                "usefulLifeMonths", 60, "inUseFrom", "2024-07-01", "depreciationBefore", 46000))).andExpect(status().isCreated())).get("id").asLong();
        // May and June depreciation (tractor only)
        for (String m : List.of("2026-05", "2026-06")) {
            money(body(post(a("/depreciation-runs"), owner, Map.of("month", m)).andExpect(status().isCreated())).at("/summary/totalAmount"), "4500.00");
        }

        long murugan = worker("Murugan", "DAILY", 500);
        long selvi = worker("Selvi", "DAILY", 400);
        long ravi = worker("Ravi", "MONTHLY", 12000);

        // 1 Jul: advance to Murugan
        post(w("/advances"), owner, Map.of("workerId", murugan, "date", "2026-07-01", "amount", 2000, "financialAccountId", cash))
                .andExpect(status().isCreated());
        // July attendance
        for (int d = 1; d <= 20; d++) {
            List<Map<String, Object>> day = new ArrayList<>();
            day.add(entry(murugan, "PRESENT", d <= 12 ? samba : null));
            if (d <= 15) day.add(entry(selvi, "PRESENT", samba));
            if (d == 16 || d == 17) day.add(entry(selvi, "HALF_DAY", samba));
            attendance(LocalDate.of(2026, 7, d), day);
        }
        // 25 Jul: power tiller sold for Rs 80,000 cash
        JsonNode sold = body(post(a("/" + tiller + "/disposal"), owner, Map.of("date", "2026-07-25", "kind", "SOLD", "proceeds", 80000,
                "financialAccountId", cash)).andExpect(status().isCreated()));
        assertThat(sold.get("status").asText()).isEqualTo("SOLD");

        // 31 Jul: pay run
        JsonNode preview = body(post(w("/pay-runs/preview"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isOk()));
        money(preview.get("total"), "28400.00");
        JsonNode m = find(preview.get("workers"), "name", "Murugan");
        money(m.get("gross"), "10000.00");
        money(m.get("advance"), "2000.00");
        money(find(preview.get("workers"), "name", "Selvi").get("days"), "16");
        money(find(preview.get("workers"), "name", "Ravi").get("gross"), "12000.00");
        JsonNode run = body(post(w("/pay-runs"), owner, Map.of("from", "2026-07-01", "to", "2026-07-31")).andExpect(status().isCreated()));
        money(run.at("/summary/totalAmount"), "28400.00");
        // Wages paid, Murugan's advance recovered
        JsonNode paid = body(post(w("/wage-payments"), owner, Map.of("date", "2026-07-31", "financialAccountId", cash, "payments", List.of(
                Map.of("workerId", murugan, "cash", 8000, "advanceRecovered", 2000),
                Map.of("workerId", selvi, "cash", 6400, "advanceRecovered", 0),
                Map.of("workerId", ravi, "cash", 12000, "advanceRecovered", 0)))).andExpect(status().isCreated()));
        assertThat(paid).hasSize(3);
        // July depreciation: tractor only (tiller sold, cow not depreciated)
        money(body(post(a("/depreciation-runs"), owner, Map.of("month", "2026-07")).andExpect(status().isCreated())).at("/summary/totalAmount"), "4500.00");

        // Balances
        JsonNode mur = body(get(w("/workers/" + murugan), owner));
        money(mur.get("owed"), "0");
        money(mur.get("advance"), "0");
        JsonNode stmt = body(get(w("/workers/" + murugan + "/statement?from=2026-07-01&to=2026-07-31"), owner));
        money(stmt.at("/lines/0/advanceGiven"), "2000.00");
        money(stmt.at("/lines/1/wages"), "10000.00");
        money(stmt.at("/lines/2/paid"), "10000.00");
        money(stmt.at("/lines/2/advanceRecovered"), "2000.00");
        money(stmt.get("closing"), "0");
        JsonNode rec = body(get(w("/reports/reconciliation"), owner));
        money(rec.get("ledgerWagesPayable"), "0");
        money(rec.get("ledgerAdvances"), "0");

        // Labour cost by activity
        JsonNode labour = body(get(w("/reports/labour?from=2026-07-01&to=2026-07-31"), owner));
        money(labour.get("totalCost"), "28400.00");
        JsonNode sambaRow = find(labour.get("rows"), "name", "Samba 2026");
        money(sambaRow.get("cost"), "12400.00");
        money(sambaRow.get("days"), "28");
        money(find(labour.get("rows"), "kind", "OVERHEAD").get("cost"), "16000.00");

        // Profit and loss, July
        JsonNode pl = report("income-statement?from=2026-07-01&to=2026-07-31");
        money(pl.get("operatingCostTotal"), "32900.00");
        money(find(pl.get("operatingCosts"), "categoryCode", "LABOUR").get("amount"), "28400.00");
        money(pl.get("otherIncomeTotal"), "6000.00");
        // Cash flow, July
        JsonNode cf = report("cash-flow?from=2026-07-01&to=2026-07-31");
        money(cf.get("operating"), "-28400");
        money(cf.get("investing"), "80000");

        // Register
        JsonNode t = body(get(a("/" + tractor.get("id").asLong()), owner));
        money(t.get("accumulated"), "13500.00");
        money(t.get("bookValue"), "586500.00");
        JsonNode all = body(get(a("?includeDisposed=true"), owner));
        JsonNode cow = find(all, "name", "Milch cow");
        assertThat(cow.get("monthlyDepreciation").isNull()).isTrue();
        money(cow.get("bookValue"), "60000.00");
        assertThat(find(all, "name", "Power tiller").get("status").asText()).isEqualTo("SOLD");
        JsonNode recon = body(get(a("/reports/reconciliation"), owner));
        money(recon.get("ledgerCost"), "660000.00");
        money(recon.get("ledgerAccumulated"), "13500.00");
        money(recon.get("unexplained"), "0");
        assertThat(journalImbalance()).isZero();
    }
}
