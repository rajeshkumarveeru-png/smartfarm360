package com.smartfarm360.workforce;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.ReversalGuard;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Keeps payroll records in order when one is reversed (workforce design, principle 4). */
@Component
class PayrollReversalGuard implements ReversalGuard {
    private final JdbcClient jdbc;

    PayrollReversalGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * A pay run cannot be reversed once its wages are paid, nor an advance once it has been recovered: reverse the
     * payment first, so wages payable and advances never turn the wrong way.
     */
    @Override
    public void checkReversal(TenantContext ctx, long documentId, DocType type) {
        if (type != DocType.PAY_RUN && type != DocType.ADVANCE) return;
        String account = type == DocType.PAY_RUN ? "2100" : "1150";
        String sign = type == DocType.PAY_RUN ? "credit - debit" : "debit - credit";
        List<Map<String, Object>> rows = jdbc.sql("""
                        select l.party_id, sum(l.%1$s) as amount,
                               (select coalesce(sum(x.%1$s), 0) from sf_journal_lines x where x.tenant_id = l.tenant_id and x.party_id = l.party_id
                                  and x.account_code = :a) as balance
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where l.tenant_id = :t and e.document_id = :d and e.kind = 'POSTING' and l.account_code = :a group by l.tenant_id, l.party_id"""
                        .formatted(sign))
                .param("t", ctx.tenantId()).param("d", documentId).param("a", account).query().listOfRows();
        for (Map<String, Object> r : rows) {
            if (((BigDecimal) r.get("balance")).compareTo((BigDecimal) r.get("amount")) < 0) {
                throw type == DocType.PAY_RUN
                        ? ApiException.conflict("WAGES_ALREADY_PAID", "Wages from this pay run are already paid. Reverse the payments first.")
                        : ApiException.conflict("ADVANCE_ALREADY_RECOVERED", "This advance is already recovered. Reverse the recovery first.");
            }
        }
    }

}
