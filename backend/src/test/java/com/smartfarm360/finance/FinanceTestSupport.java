package com.smartfarm360.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.support.ApiIntegrationTest;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Helpers for finance API tests: a fresh tenant per test, and short ways to record common records. */
public abstract class FinanceTestSupport extends ApiIntegrationTest {
    protected User owner;
    protected long tenant;
    protected long cash;

    protected void newFinanceTenant(String name) throws Exception {
        owner = newUser("fin-" + name);
        tenant = newTenant(owner, "Finance " + name);
        cash = cashAccountId(owner, tenant);
    }

    protected String f(String path) { return "/api/v1/tenants/" + tenant + "/finance" + path; }

    protected long account(String name, String kind) throws Exception {
        return id(post(f("/accounts"), owner, Map.of("name", name, "kind", kind)).andExpect(status().isCreated()));
    }

    protected long party(String name) throws Exception {
        return id(post(f("/parties"), owner, Map.of("name", name, "customer", true, "supplier", true)).andExpect(status().isCreated()));
    }

    protected Map<String, Object> line(String category, Object amount, Long costObjectId) {
        Map<String, Object> m = new HashMap<>();
        m.put("categoryId", categoryId(category));
        m.put("amount", amount);
        if (costObjectId != null) m.put("costObjectId", costObjectId);
        return m;
    }

    /** A posted bill or sale; {@code partyId} null means a cash record paid from/to {@code accountId}. */
    protected JsonNode invoice(String kind, String date, Long partyId, Long accountId, Map<String, Object>... lines) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("docDate", date);
        body.put("lines", List.of(lines));
        if (partyId != null) body.put("partyId", partyId);
        if (accountId != null) body.put("financialAccountId", accountId);
        return body(post(f("/" + kind), owner, body).andExpect(status().isCreated()));
    }

    protected JsonNode settle(String kind, String date, long partyId, long accountId, Object amount, Long targetId, Object allocated) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("docDate", date, "partyId", partyId, "financialAccountId", accountId, "amount", amount));
        if (targetId != null) body.put("allocations", List.of(Map.of("documentId", targetId, "amount", allocated)));
        return body(post(f("/" + kind), owner, body).andExpect(status().isCreated()));
    }

    protected JsonNode report(String path) throws Exception {
        return body(get(f("/reports/" + path), owner).andExpect(status().isOk()));
    }

    /** Id of a created record: documents nest it under "summary", master data has it at the top level. */
    protected long id(ResultActions r) throws Exception {
        JsonNode node = body(r);
        return node.has("summary") ? node.at("/summary/id").asLong() : node.get("id").asLong();
    }

    protected static void money(JsonNode node, String expected) {
        assertThat(new BigDecimal(node.asText())).as(node.toString()).isEqualByComparingTo(expected);
    }

    /**
     * Dashboard and reports design, section 13: after any scenario the balance sheet balances and the trial balance
     * debits equal its credits.
     */
    protected void assertBooksBalance(String asOf) throws Exception {
        JsonNode sheet = report("balance-sheet?asOf=" + asOf);
        money(sheet.get("difference"), "0");
        money(sheet.get("assetTotal"), sheet.get("liabilitiesAndEquity").asText());
        JsonNode trial = report("trial-balance?from=2000-01-01&to=" + asOf);
        money(trial.get("debitTotal"), trial.get("creditTotal").asText());
        money(trial.get("closingTotal"), "0");
    }

    protected long journalImbalance() {
        return jdbc.queryForObject("select count(*) from (select entry_id from sf_journal_lines where tenant_id = ? group by entry_id having sum(debit) <> sum(credit)) x",
                Long.class, tenant);
    }
}
