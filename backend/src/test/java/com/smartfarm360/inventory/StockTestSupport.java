package com.smartfarm360.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.AfterEach;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for stock API tests. After every test the stock invariants are checked for the test's tenant: on-hand
 * equals the sum of movements, per location and lot as well, and Stock (1200) equals the value of stock on hand.
 */
public abstract class StockTestSupport extends FinanceTestSupport {

    protected String s(String path) { return "/api/v1/tenants/" + tenant + "/stock" + path; }

    protected long item(String name, String kind, String baseUnit, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", kind, "baseUnit", baseUnit));
        body.putAll(extra);
        return body(post(s("/items"), owner, body).andExpect(status().isCreated())).get("id").asLong();
    }

    protected Map<String, Object> qty(long itemId, Object quantity, String unit) {
        Map<String, Object> m = new HashMap<>(Map.of("itemId", itemId, "quantity", quantity));
        if (unit != null) m.put("unit", unit);
        return m;
    }

    protected Map<String, Object> itemLine(long itemId, Object quantity, String unit, Object rate) {
        Map<String, Object> m = qty(itemId, quantity, unit);
        m.put("rate", rate);
        return m;
    }

    /** A posted bill or sale with item lines; {@code partyId} null means paid from/to cash. */
    protected JsonNode stockInvoice(String kind, String date, Long partyId, Map<String, Object>... lines) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("docDate", date, "lines", List.of(lines)));
        if (partyId != null) body.put("partyId", partyId);
        else body.put("financialAccountId", cash);
        return body(post(f("/" + kind), owner, body).andExpect(status().isCreated()));
    }

    protected JsonNode record(String path, Map<String, Object> body) throws Exception {
        return body(post(s(path), owner, body).andExpect(status().isCreated()));
    }

    protected JsonNode stockItem(long id) throws Exception { return body(get(s("/items/" + id), owner).andExpect(status().isOk())); }

    protected long defaultLocation() throws Exception { return body(get(s("/locations"), owner)).get(0).get("id").asLong(); }

    protected BigDecimal accountBalance(String code) {
        return jdbc.queryForObject("select coalesce(sum(debit - credit), 0) from sf_journal_lines where tenant_id = ? and account_code = ?",
                BigDecimal.class, tenant, code);
    }

    protected static void money(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    @AfterEach
    protected void stockInvariantsHold() {
        if (tenant == 0) return;
        assertThat(jdbc.queryForObject("""
                select count(*) from sf_stock_items i where i.tenant_id = ? and (i.on_hand_quantity, i.on_hand_value) <>
                  (select coalesce(sum(m.quantity), 0), coalesce(sum(m.value), 0) from sf_stock_movements m where m.item_id = i.id)""",
                Long.class, tenant)).as("item totals equal the sum of movements").isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from (select item_id, location_id, coalesce(lot_id, 0) lot, sum(quantity) q from sf_stock_movements
                                      where tenant_id = ? group by 1, 2, 3) m
                full join (select item_id, location_id, coalesce(lot_id, 0) lot, quantity q from sf_stock_balances where tenant_id = ?) b
                  using (item_id, location_id, lot)
                where coalesce(m.q, 0) <> coalesce(b.q, 0)""", Long.class, tenant, tenant))
                .as("balances per location and lot equal the sum of movements").isZero();
        assertThat(accountBalance("1200")).as("Stock (1200) equals the value of stock on hand")
                .isEqualByComparingTo(jdbc.queryForObject("select coalesce(sum(on_hand_value), 0) from sf_stock_items where tenant_id = ?",
                        BigDecimal.class, tenant));
        assertThat(journalImbalance()).isZero();
    }
}
