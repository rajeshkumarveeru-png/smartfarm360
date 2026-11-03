package com.smartfarm360.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worked example in docs/finance/posting-rules.md, section 8, recorded through the API. Every figure the document
 * promises is asserted here; if this test changes, the document must change too.
 */
class GoldenScenarioTest extends FinanceTestSupport {

    @Test
    void juneExampleMatchesThePostingRulesDocument() throws Exception {
        newFinanceTenant("golden");
        long bank = account("SBI crop account", "BANK");
        long raman = party("Raman (trader)");
        long stateBank = id(post(f("/parties"), owner, Map.of("name", "State Bank", "lender", true)).andExpect(status().isCreated()));

        // Samba paddy cycle: farm, field, crop and cycle through the crop API; the cycle becomes a cost object.
        long farm = newFarm(owner, tenant, "Main farm");
        long parcel = body(post("/api/v1/tenants/" + tenant + "/farms/" + farm + "/land-parcels", owner, Map.of("name", "North field", "area", 2))).get("id").asLong();
        long crop = body(post("/api/v1/tenants/" + tenant + "/crops", owner, Map.of("name", "Paddy"))).get("id").asLong();
        long cycle = body(post("/api/v1/tenants/" + tenant + "/cultivation-cycles", owner, Map.of("farmId", farm, "landParcelId", parcel,
                "cropId", crop, "name", "Samba 2026", "plannedSowingDate", "2026-06-01"))).get("id").asLong();
        long samba = StreamSupport.stream(body(get(f("/cost-objects"), owner)).spliterator(), false)
                .filter(c -> c.path("cropCycleId").asLong() == cycle).findFirst().orElseThrow().get("id").asLong();

        // Start: ₹20,000 cash (opening balance, P14), dated before June so June's cash flow opens with it.
        post(f("/opening-balances"), owner, Map.of("docDate", "2026-05-31",
                "lines", List.of(Map.of("kind", "CASH", "financialAccountId", cash, "amount", 20000)))).andExpect(status().isCreated());

        long seedBill = invoice("bills", "2026-06-02", raman, null, line("SEEDS", 4000, samba)).at("/summary/id").asLong();
        settle("payments", "2026-06-05", raman, cash, 1500, seedBill, 1500);
        post(f("/loans"), owner, Map.of("docDate", "2026-06-10", "lenderPartyId", stateBank, "loanName", "Crop loan 2026",
                "financialAccountId", bank, "amount", 100000)).andExpect(status().isCreated());
        invoice("bills", "2026-06-12", null, bank, line("EQUIPMENT", 24000, null));
        long paddySale = invoice("sales", "2026-06-20", raman, null, line("CROP_SALE", 50000, samba)).at("/summary/id").asLong();
        settle("receipts", "2026-06-20", raman, bank, 30000, paddySale, 30000);
        invoice("bills", "2026-06-25", null, cash, line("LABOUR", 6000, samba));
        post(f("/owner-money"), owner, Map.of("docDate", "2026-06-28", "direction", "OUT", "financialAccountId", cash, "amount", 3000))
                .andExpect(status().isCreated());
        long loan = body(get(f("/loans"), owner)).get(0).get("id").asLong();
        post(f("/loan-repayments"), owner, Map.of("docDate", "2026-06-30", "loanId", loan, "financialAccountId", bank,
                "principal", 8000, "interest", 2000)).andExpect(status().isCreated());
        post(f("/depreciation"), owner, Map.of("docDate", "2026-06-30", "amount", 400)).andExpect(status().isCreated());

        // Profit (June, accrual)
        JsonNode pl = report("income-statement?from=2026-06-01&to=2026-06-30");
        money(pl.get("operatingRevenueTotal"), "50000");
        money(pl.get("operatingCostTotal"), "10400");
        money(pl.get("operatingProfit"), "39600");
        money(pl.get("interest"), "2000");
        money(pl.get("netProfit"), "37600");
        assertThat(pl.get("actual").asBoolean()).isTrue();

        // Cash flow (June)
        JsonNode cf = report("cash-flow?from=2026-06-01&to=2026-06-30");
        money(cf.get("opening"), "20000");
        money(cf.get("operating"), "22500");
        money(cf.get("investing"), "-24000");
        money(cf.get("financing"), "87000");
        money(cf.get("netChange"), "85500");
        money(cf.get("closing"), "105500");

        // Balances at month end: cash ₹9,500, bank ₹96,000, loan ₹92,000
        JsonNode balances = report("balances?asOf=2026-06-30");
        Map<String, String> byName = Map.of("Cash in hand", "9500", "SBI crop account", "96000");
        for (JsonNode a : balances.get("accounts")) if (byName.containsKey(a.get("name").asText())) money(a.get("balance"), byName.get(a.get("name").asText()));
        money(balances.get("cashTotal"), "105500");
        money(balances.get("loanTotal"), "92000");

        // Dues before settlement: Raman owes ₹20,000; we owe Raman ₹2,500
        JsonNode raman1 = body(get(f("/parties/" + raman), owner));
        money(raman1.get("receivable"), "20000");
        money(raman1.get("payable"), "2500");

        // Samba cycle: revenue ₹50,000 − direct costs ₹10,000 = ₹40,000; depreciation stays in overhead
        JsonNode activity = report("activity?from=2026-06-01&to=2026-06-30");
        JsonNode sambaRow = StreamSupport.stream(activity.get("rows").spliterator(), false).filter(r -> r.get("costObjectId").asLong() == samba).findFirst().orElseThrow();
        money(sambaRow.get("revenue"), "50000");
        money(sambaRow.get("costs"), "10000");
        money(sambaRow.get("contribution"), "40000");
        JsonNode overhead = StreamSupport.stream(activity.get("rows").spliterator(), false).filter(r -> "OVERHEAD".equals(r.get("kind").asText())).findFirst().orElseThrow();
        money(overhead.get("costs"), "400");

        // Cash basis (D10): only what was actually received or paid: ₹30,000 in; ₹1,500 seed + ₹6,000 labour out; interest ₹2,000
        JsonNode cashBasis = report("income-statement?from=2026-06-01&to=2026-06-30&basis=CASH");
        money(cashBasis.get("operatingRevenueTotal"), "30000");
        money(cashBasis.get("operatingCostTotal"), "7500");
        money(cashBasis.get("interest"), "2000");
        money(cashBasis.get("netProfit"), "20500");

        // Settlement (P12) leaves Raman owing ₹17,500, with no change to profit or cash
        post(f("/settlements"), owner, Map.of("docDate", "2026-06-30", "partyId", raman, "amount", 2500)).andExpect(status().isCreated());
        JsonNode raman2 = body(get(f("/parties/" + raman), owner));
        money(raman2.get("receivable"), "17500");
        money(raman2.get("payable"), "0");
        money(report("income-statement?from=2026-06-01&to=2026-06-30").get("netProfit"), "37600");
        money(report("cash-flow?from=2026-06-01&to=2026-06-30").get("closing"), "105500");
        JsonNode bill = body(get(f("/documents/" + seedBill), owner));
        assertThat(bill.at("/summary/paymentStatus").asText()).isEqualTo("PAID");

        assertThat(journalImbalance()).isZero();
        assertBooksBalance("2026-12-31");
    }
}
