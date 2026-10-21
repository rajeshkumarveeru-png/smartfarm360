package com.smartfarm360.reporting;

import com.smartfarm360.finance.FinanceTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard and reports design, H4: figures are computed on request, so a farm business with 50,000 journal lines must
 * get its dashboard in under one second. The data is generated in SQL (12,500 cash bills and 12,500 credit sales over a
 * year, two journal lines each) because recording it through the API would take minutes.
 */
class DashboardPerformanceTest extends FinanceTestSupport {

    @Test
    void dashboardAnswersWithinOneSecondAtFiftyThousandJournalLines() throws Exception {
        newFinanceTenant("dashboard-performance");
        long customer = party("Wholesale buyer");
        long overhead = jdbc.queryForObject("select id from sf_cost_objects where tenant_id = ? and kind = 'OVERHEAD'", Long.class, tenant);
        LocalDate asOf = LocalDate.of(2026, 9, 30);
        jdbc.update("""
                insert into sf_finance_documents (tenant_id, doc_type, status, number, doc_date, party_id, financial_account_id, total_amount, legacy_source, legacy_id)
                select ?, case when g % 2 = 0 then 'BILL' else 'SALE' end, 'POSTED', 'PERF-' || g, cast(? as date) - (g % 365),
                       case when g % 2 = 0 then null else ? end, case when g % 2 = 0 then ? else null end, 100 + (g % 50), 'perf-' || ?, g
                from generate_series(1, 25000) g""", tenant, asOf, customer, cash, tenant);
        jdbc.update("""
                insert into sf_journal_entries (tenant_id, document_id, entry_date, kind)
                select tenant_id, id, doc_date, 'POSTING' from sf_finance_documents where tenant_id = ? and legacy_source = 'perf-' || ?""", tenant, tenant);
        // Bills: Dr farm inputs (overhead) / Cr cash. Sales: Dr receivables (customer) / Cr produce sales.
        jdbc.update("""
                insert into sf_journal_lines (tenant_id, entry_id, account_code, debit, credit, party_id, financial_account_id, cost_object_id)
                select e.tenant_id, e.id, x.account_code, x.debit, x.credit, x.party_id, x.financial_account_id, x.cost_object_id
                from sf_journal_entries e join sf_finance_documents d on d.id = e.document_id
                cross join lateral (values
                    (case when d.doc_type = 'BILL' then '5000' else '1100' end, d.total_amount, 0::numeric, d.party_id, null::bigint,
                     case when d.doc_type = 'BILL' then ? else null::bigint end),
                    (case when d.doc_type = 'BILL' then '1000' else '4000' end, 0::numeric, d.total_amount, null::bigint, d.financial_account_id,
                     case when d.doc_type = 'BILL' then null::bigint else ? end)
                ) as x(account_code, debit, credit, party_id, financial_account_id, cost_object_id)
                where e.tenant_id = ? and d.legacy_source = 'perf-' || ?""", overhead, overhead, tenant, tenant);
        jdbc.execute("analyze sf_journal_lines");
        jdbc.execute("analyze sf_journal_entries");
        jdbc.execute("analyze sf_finance_documents");
        assertThat(jdbc.queryForObject("select count(*) from sf_journal_lines where tenant_id = ?", Long.class, tenant)).isGreaterThanOrEqualTo(50_000L);
        assertThat(journalImbalance()).isZero();

        String url = "/api/v1/tenants/" + tenant + "/dashboard?asOf=" + asOf;
        get(url, owner).andExpect(status().isOk());   // warm-up: first request loads classes and plans
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            get(url, owner).andExpect(status().isOk());
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        System.out.println("Dashboard at 50,000 journal lines: " + best + " ms (best of 3)");
        assertThat(best).as("dashboard milliseconds").isLessThan(1000);
        assertBooksBalance(asOf.toString());
    }
}
