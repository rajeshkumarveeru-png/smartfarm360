package com.smartfarm360.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Posting rules sections 5–7: lifecycle, settlement, duplicates, rollback, locking, rounding and journal guards. */
class FinanceRulesTest extends FinanceTestSupport {
    @Autowired PlatformTransactionManager transactions;
    private long trader;

    @BeforeEach
    void setUp() throws Exception {
        newFinanceTenant("rules-" + UUID.randomUUID().toString().substring(0, 6));
        trader = party("Trader");
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class, tenant); }

    @Test
    void numbersAreGapFreePerTypeAndFiscalYearAprilToMarch() throws Exception {
        String n1 = invoice("bills", "2026-04-01", null, cash, line("SEEDS", 10, null)).at("/summary/number").asText();
        String n2 = invoice("bills", "2027-03-31", null, cash, line("SEEDS", 10, null)).at("/summary/number").asText();
        String n3 = invoice("bills", "2027-04-01", null, cash, line("SEEDS", 10, null)).at("/summary/number").asText();
        String sale = invoice("sales", "2026-05-01", null, cash, line("MILK_SALE", 10, null)).at("/summary/number").asText();
        assertThat(List.of(n1, n2, n3, sale)).containsExactly("BILL/2026-27/0001", "BILL/2026-27/0002", "BILL/2027-28/0001", "SALE/2026-27/0001");
    }

    @Test
    void draftsHaveNoNumberOrJournalUntilPosted() throws Exception {
        Map<String, Object> draft = new HashMap<>(Map.of("docDate", "2026-07-01", "partyId", trader, "post", false,
                "lines", List.of(line("FERTILIZER", 500, null))));
        long id = id(post(f("/bills"), owner, draft).andExpect(status().isCreated()).andExpect(jsonPath("$.summary.status").value("DRAFT"))
                .andExpect(jsonPath("$.summary.number").doesNotExist()));
        assertThat(count("select count(*) from sf_journal_entries where tenant_id = ?")).isZero();

        draft.put("lines", List.of(line("FERTILIZER", 750, null)));
        put(f("/documents/" + id), owner, draft).andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalAmount").value(750.00));
        post(f("/documents/" + id + "/post"), owner, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("POSTED")).andExpect(jsonPath("$.summary.number").value("BILL/2026-27/0001"));
        put(f("/documents/" + id), owner, draft).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_A_DRAFT"));
        money(body(get(f("/parties/" + trader), owner)).get("payable"), "750");

        long second = id(post(f("/bills"), owner, draft).andExpect(status().isCreated()));
        post(f("/documents/" + second + "/cancel"), owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.summary.status").value("CANCELLED"));
        money(body(get(f("/parties/" + trader), owner)).get("payable"), "750");
    }

    @Test
    void reversalCancelsTheEffectAndProtectsMatchedRecords() throws Exception {
        long bill = invoice("bills", "2026-07-02", trader, null, line("SEEDS", 1000, null)).at("/summary/id").asLong();
        long payment = settle("payments", "2026-07-03", trader, cash, 400, bill, 400).at("/summary/id").asLong();

        post(f("/documents/" + bill + "/reverse"), owner, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HAS_ALLOCATIONS"));
        post(f("/documents/" + payment + "/reverse"), owner, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("REVERSED")).andExpect(jsonPath("$.allocations").isEmpty());
        post(f("/documents/" + bill + "/reverse"), owner, Map.of("reversalDate", "2026-07-10")).andExpect(status().isOk());
        post(f("/documents/" + bill + "/reverse"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_POSTED"));

        JsonNode p = body(get(f("/parties/" + trader), owner));
        money(p.get("payable"), "0");
        money(report("income-statement?from=2026-07-01&to=2026-07-31").get("operatingCostTotal"), "0");
        // The original stays visible in July 2 – 9; the reversal takes effect on 10 July.
        money(report("income-statement?from=2026-07-01&to=2026-07-09").get("operatingCostTotal"), "1000");
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void lockDateBlocksChangesOnOrBeforeIt() throws Exception {
        long june = invoice("bills", "2026-06-15", null, cash, line("SEEDS", 100, null)).at("/summary/id").asLong();
        put(f("/settings"), owner, Map.of("lockDate", "2026-06-30", "fiscalYearStartMonth", 4)).andExpect(status().isOk());

        post(f("/bills"), owner, Map.of("docDate", "2026-06-20", "financialAccountId", cash, "lines", List.of(line("SEEDS", 1, null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_LOCKED"));
        post(f("/documents/" + june + "/reverse"), owner, Map.of("reversalDate", "2026-06-20")).andExpect(status().isConflict());
        // Without a date the reversal lands today, outside the locked period, and June's figures stay as reported.
        JsonNode reversed = body(post(f("/documents/" + june + "/reverse"), owner, Map.of()).andExpect(status().isOk()));
        assertThat(reversed.get("reversalDate").asText()).isEqualTo(LocalDate.now().toString());
        money(report("income-statement?from=2026-06-01&to=2026-06-30").get("operatingCostTotal"), "100");
    }

    @Test
    void allocationsAreCheckedAgainstOpenAmountsPartiesAndTypes() throws Exception {
        long bill = invoice("bills", "2026-07-02", trader, null, line("SEEDS", 1000, null)).at("/summary/id").asLong();
        long other = party("Someone else");
        long otherBill = invoice("bills", "2026-07-02", other, null, line("SEEDS", 50, null)).at("/summary/id").asLong();
        long sale = invoice("sales", "2026-07-02", trader, null, line("CROP_SALE", 300, null)).at("/summary/id").asLong();

        Map<String, Object> over = Map.of("docDate", "2026-07-05", "partyId", trader, "financialAccountId", cash, "amount", 1500,
                "allocations", List.of(Map.of("documentId", bill, "amount", 1200)));
        post(f("/payments"), owner, over).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OVER_ALLOCATED"));
        post(f("/payments"), owner, Map.of("docDate", "2026-07-05", "partyId", trader, "financialAccountId", cash, "amount", 50,
                "allocations", List.of(Map.of("documentId", otherBill, "amount", 50)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_PARTY_MISMATCH"));
        post(f("/payments"), owner, Map.of("docDate", "2026-07-05", "partyId", trader, "financialAccountId", cash, "amount", 50,
                "allocations", List.of(Map.of("documentId", sale, "amount", 50)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_TYPE_MISMATCH"));

        // An unmatched payment is an advance until it is matched later.
        long advance = settle("payments", "2026-07-06", trader, cash, 600, null, null).at("/summary/id").asLong();
        money(body(get(f("/parties/" + trader), owner)).get("payable"), "400");
        post(f("/documents/" + advance + "/allocations"), owner, Map.of("documentId", bill, "amount", 600)).andExpect(status().isOk());
        JsonNode b = body(get(f("/documents/" + bill), owner));
        assertThat(b.at("/summary/paymentStatus").asText()).isEqualTo("PARTLY_PAID");
        money(b.at("/summary/openAmount"), "400");
    }

    @Test
    void failedRecordsLeaveNoTrace() throws Exception {
        long bill = invoice("bills", "2026-07-02", trader, null, line("SEEDS", 100, null)).at("/summary/id").asLong();
        long docs = count("select count(*) from sf_finance_documents where tenant_id = ?");
        long entries = count("select count(*) from sf_journal_entries where tenant_id = ?");
        // The payment and its journal are written before the allocation fails; everything must roll back.
        post(f("/payments"), owner, Map.of("docDate", "2026-07-05", "partyId", trader, "financialAccountId", cash, "amount", 150,
                "allocations", List.of(Map.of("documentId", bill, "amount", 150)))).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from sf_finance_documents where tenant_id = ?")).isEqualTo(docs);
        assertThat(count("select count(*) from sf_journal_entries where tenant_id = ?")).isEqualTo(entries);
        assertThat(count("select count(*) from sf_payment_allocations where tenant_id = ?")).isZero();
    }

    @Test
    void retriesWithTheSameKeyNeverRecordMoneyTwice() throws Exception {
        String body = json.writeValueAsString(Map.of("docDate", "2026-07-02", "financialAccountId", cash, "lines", List.of(line("LABOUR", 800, null))));
        long first = body(mvc.perform(MockMvcRequestBuilders.post(f("/bills")).header("Authorization", "Bearer " + owner.token())
                .header("Idempotency-Key", "tap-123").contentType(MediaType.APPLICATION_JSON).content(body))).at("/summary/id").asLong();
        long second = body(mvc.perform(MockMvcRequestBuilders.post(f("/bills")).header("Authorization", "Bearer " + owner.token())
                .header("Idempotency-Key", "tap-123").contentType(MediaType.APPLICATION_JSON).content(body))).at("/summary/id").asLong();
        assertThat(second).isEqualTo(first);
        assertThat(count("select count(*) from sf_journal_entries where tenant_id = ?")).isEqualTo(1);

        String different = json.writeValueAsString(Map.of("docDate", "2026-07-02", "financialAccountId", cash, "lines", List.of(line("LABOUR", 900, null))));
        mvc.perform(MockMvcRequestBuilders.post(f("/bills")).header("Authorization", "Bearer " + owner.token())
                        .header("Idempotency-Key", "tap-123").contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentPaymentsCannotOverPayOneBill() throws Exception {
        long bill = invoice("bills", "2026-07-02", trader, null, line("SEEDS", 1000, null)).at("/summary/id").asLong();
        Map<String, Object> pay = Map.of("docDate", "2026-07-05", "partyId", trader, "financialAccountId", cash, "amount", 600,
                "allocations", List.of(Map.of("documentId", bill, "amount", 600)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return post(f("/payments"), owner, pay).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> r : results) statuses.add(r.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        money(body(get(f("/documents/" + bill), owner)).at("/summary/openAmount"), "400");
    }

    @Test
    void lineAmountsAreRoundedHalfUpPerLineAndCategoriesMustFit() throws Exception {
        JsonNode bill = body(post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "financialAccountId", cash,
                "lines", List.of(Map.of("categoryId", categoryId("FERTILIZER"), "quantity", 3, "rate", 33.335),
                        Map.of("categoryId", categoryId("FERTILIZER"), "quantity", 1, "rate", 0.005)))).andExpect(status().isCreated()));
        money(bill.at("/summary/totalAmount"), "100.02");
        post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "financialAccountId", cash, "lines", List.of(line("CROP_SALE", 10, null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_MISMATCH"));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "financialAccountId", cash,
                "lines", List.of(Map.of("categoryId", categoryId("SEEDS"), "quantity", 2, "rate", 10, "amount", 25))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "lines", List.of(line("SEEDS", 10, null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARTY_OR_ACCOUNT_REQUIRED"));
    }

    @Test
    void paidNowAndWriteOffAndOpeningLoansFollowTheRules() throws Exception {
        JsonNode sale = body(post(f("/sales"), owner, Map.of("docDate", "2026-07-02", "partyId", trader, "lines", List.of(line("MILK_SALE", 1000, null)),
                "paidNow", Map.of("financialAccountId", cash, "amount", 400))).andExpect(status().isCreated()));
        assertThat(sale.at("/summary/paymentStatus").asText()).isEqualTo("PARTLY_PAID");
        money(sale.at("/summary/openAmount"), "600");

        post(f("/write-offs"), owner, Map.of("docDate", "2026-07-20", "partyId", trader, "amount", 700)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRITE_OFF_TOO_LARGE"));
        post(f("/write-offs"), owner, Map.of("docDate", "2026-07-20", "partyId", trader, "amount", 600)).andExpect(status().isCreated());
        JsonNode pl = report("income-statement?from=2026-07-01&to=2026-07-31");
        money(pl.get("operatingRevenueTotal"), "1000");
        money(pl.get("losses"), "600");
        money(pl.get("netProfit"), "400");

        long lender = party("Cooperative bank");
        post(f("/opening-balances"), owner, Map.of("docDate", "2026-03-31", "lines", List.of(
                Map.of("kind", "CASH", "financialAccountId", cash, "amount", 5000),
                Map.of("kind", "LOAN", "partyId", lender, "loanName", "Tractor loan", "amount", 30000),
                Map.of("kind", "ASSET", "description", "Tractor", "amount", 25000)))).andExpect(status().isCreated());
        JsonNode loans = body(get(f("/loans"), owner));
        money(loans.get(0).get("outstanding"), "30000");
        // Opening balances never count as profit or as a cash movement of the period.
        money(report("income-statement?from=2026-03-01&to=2026-03-31").get("netProfit"), "0");
        assertThat(journalImbalance()).isZero();
    }

    @Test
    void everyFinanceReadEndpointAnswers() throws Exception {
        long bill = invoice("bills", "2026-07-02", trader, null, line("SEEDS", 100, null)).at("/summary/id").asLong();
        for (String path : List.of("/settings", "/categories", "/accounts", "/accounts?asOf=2026-07-31", "/parties", "/parties/" + trader,
                "/loans", "/cost-objects", "/documents", "/documents/" + bill, "/reports/income-statement?from=2026-07-01&to=2026-07-31",
                "/reports/income-statement?from=2026-07-01&to=2026-07-31&basis=CASH", "/reports/cash-flow?from=2026-07-01&to=2026-07-31",
                "/reports/dues", "/reports/dues?asOf=2026-09-15", "/reports/balances", "/reports/activity?from=2026-07-01&to=2026-07-31",
                "/reports/summary")) {
            get(f(path), owner).andExpect(status().isOk());
        }
        // Ageing: due on the record date (no due date), so on 15 Sep it is 75 days old
        JsonNode dues = report("dues?asOf=2026-09-15");
        money(dues.at("/payables/0/days61to90"), "100");
        money(dues.get("payableTotal"), "100");
        get(f("/reports/cash-flow?from=2026-07-31&to=2026-07-01"), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void recordsCanBeListedAndFilteredByTypeStatusPartyAndDate() throws Exception {
        invoice("bills", "2026-07-02", trader, null, line("SEEDS", 100, null));
        invoice("sales", "2026-07-03", trader, null, line("CROP_SALE", 300, null));
        invoice("bills", "2026-08-01", null, cash, line("LABOUR", 50, null));
        post(f("/bills"), owner, Map.of("docDate", "2026-07-04", "partyId", trader, "post", false, "lines", List.of(line("SEEDS", 1, null))));

        get(f("/documents"), owner).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items[0].docDate").value("2026-08-01"));
        get(f("/documents?type=BILL&status=POSTED"), owner).andExpect(jsonPath("$.totalItems").value(2));
        get(f("/documents?partyId=" + trader + "&from=2026-07-01&to=2026-07-31"), owner).andExpect(jsonPath("$.totalItems").value(3));
        get(f("/documents?status=DRAFT"), owner).andExpect(jsonPath("$.items[0].number").doesNotExist());
        get(f("/documents?size=2&page=1"), owner).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        get(f("/documents?size=500"), owner).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    void aTenantCanHaveManyOwnCategoriesButNotTwoWithTheSameName() throws Exception {
        post(f("/categories"), owner, Map.of("name", "Tractor diesel", "kind", "EXPENSE", "accountCode", "5000")).andExpect(status().isCreated());
        post(f("/categories"), owner, Map.of("name", "Drone spraying", "kind", "EXPENSE", "accountCode", "5600")).andExpect(status().isCreated());
        post(f("/categories"), owner, Map.of("name", "tractor DIESEL", "kind", "EXPENSE", "accountCode", "5000")).andExpect(status().isConflict());
        post(f("/categories"), owner, Map.of("name", "Odd", "kind", "INCOME", "accountCode", "5000")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT"));
    }

    @Test
    void theDatabaseRefusesEditedOrUnbalancedJournalEntries() throws Exception {
        invoice("bills", "2026-07-02", null, cash, line("SEEDS", 100, null));
        assertThatThrownBy(() -> jdbc.update("update sf_journal_lines set debit = debit + 1 where tenant_id = ?", tenant))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from sf_journal_entries where tenant_id = ?", tenant))
                .hasMessageContaining("append-only");
        long doc = jdbc.queryForObject("select id from sf_finance_documents where tenant_id = ? limit 1", Long.class, tenant);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
            long entry = jdbc.queryForObject("insert into sf_journal_entries (tenant_id, document_id, entry_date, kind) values (?, ?, current_date, 'REVERSAL') returning id",
                    Long.class, tenant, doc);
            jdbc.update("insert into sf_journal_lines (tenant_id, entry_id, account_code, debit, credit) values (?, ?, '5000', 10, 0)", tenant, entry);
        })).hasStackTraceContaining("does not balance");
    }

    @Test
    void stockBoughtOnABillIsPostedToStockNotExpense() throws Exception {
        String base = "/api/v1/tenants/" + tenant;
        long supplier = party("Agro depot");
        long item = body(post(base + "/stock/items", owner, Map.of("name", "Urea", "kind", "INPUT", "baseUnit", "kg", "reorderLevel", 10))
                .andExpect(status().isCreated())).get("id").asLong();
        post(f("/bills"), owner, Map.of("docDate", "2026-07-02", "partyId", supplier,
                "lines", List.of(Map.of("itemId", item, "quantity", 50, "rate", 6)),
                "paidNow", Map.of("financialAccountId", cash, "amount", 100))).andExpect(status().isCreated());

        JsonNode party = StreamSupportHelper.find(body(get(f("/parties"), owner)), "name", "Agro depot");
        money(party.get("payable"), "200");
        // Bought stock is not an expense until used (ADR 0005).
        money(report("income-statement?from=2026-07-01&to=2026-07-31").get("operatingCostTotal"), "0");
        money(report("cash-flow?from=2026-07-01&to=2026-07-31").get("operating"), "-100");
        // The quantity is in stock at its cost, and the ledger agrees with the store.
        JsonNode stock = body(get(base + "/stock/items/" + item, owner));
        money(stock.get("quantity"), "50");
        money(stock.get("value"), "300");
        money(body(get(base + "/stock/reports/reconciliation", owner)).get("difference"), "0");
    }

    /** Small helper to find a node in a JSON array by a text field. */
    static final class StreamSupportHelper {
        static JsonNode find(JsonNode array, String field, String value) {
            for (JsonNode n : array) if (value.equals(n.path(field).asText())) return n;
            throw new AssertionError("No element with " + field + "=" + value);
        }
    }
}
