package com.smartfarm360.finance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the journal for a finance record exactly as the posting rules (section 5) describe, and writes it.
 * The journal is append-only; the database also refuses unbalanced entries at commit.
 */
@Component
public class PostingEngine {
    private final JdbcClient jdbc;

    public PostingEngine(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Doc(long id, long tenantId, DocType type, LocalDate docDate, Long partyId, Long farmId, Long financialAccountId,
               Long toFinancialAccountId, Long loanId, BigDecimal total, BigDecimal principal, BigDecimal interest) {}

    record Line(Long categoryId, String accountCode, String side, BigDecimal amount, Long costObjectId, Long farmId,
                Long partyId, Long financialAccountId, Long loanId) {}

    record JournalLine(String accountCode, BigDecimal debit, BigDecimal credit, Long categoryId, Long financialAccountId,
                       Long partyId, Long costObjectId, Long farmId, Long loanId) {}

    /** The journal lines for a document; {@code overheadId} is used for profit-and-loss lines without an activity. */
    List<JournalLine> journal(Doc d, List<Line> lines, long overheadId) {
        List<JournalLine> out = new ArrayList<>();
        switch (d.type()) {
            case BILL -> {
                for (Line l : lines) out.add(debit(l.accountCode(), l.amount(), l.categoryId(), null, null, costObject(l, overheadId), farm(l, d), null));
                if (d.partyId() != null) out.add(credit(LedgerAccount.PAYABLES.code(), d.total(), null, null, d.partyId(), null, d.farmId(), null));
                else out.add(credit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, d.farmId(), null));
            }
            case SALE -> {
                if (d.partyId() != null) out.add(debit(LedgerAccount.RECEIVABLES.code(), d.total(), null, null, d.partyId(), null, d.farmId(), null));
                else out.add(debit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, d.farmId(), null));
                for (Line l : lines) out.add(credit(l.accountCode(), l.amount(), l.categoryId(), null, null, costObject(l, overheadId), farm(l, d), null));
            }
            case PAYMENT -> {
                out.add(debit(LedgerAccount.PAYABLES.code(), d.total(), null, null, d.partyId(), null, null, null));
                out.add(credit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
            }
            case RECEIPT -> {
                out.add(debit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
                out.add(credit(LedgerAccount.RECEIVABLES.code(), d.total(), null, null, d.partyId(), null, null, null));
            }
            case TRANSFER -> {
                out.add(debit(cashCode(d.tenantId(), d.toFinancialAccountId()), d.total(), null, d.toFinancialAccountId(), null, null, null, null));
                out.add(credit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
            }
            case OWNER_IN -> {
                out.add(debit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
                out.add(credit(LedgerAccount.OWNER_CAPITAL.code(), d.total(), null, null, null, null, null, null));
            }
            case OWNER_OUT -> {
                out.add(debit(LedgerAccount.OWNER_DRAWINGS.code(), d.total(), null, null, null, null, null, null));
                out.add(credit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
            }
            case LOAN_RECEIVED -> {
                out.add(debit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
                out.add(credit(LedgerAccount.LOANS.code(), d.total(), null, null, d.partyId(), null, null, d.loanId()));
            }
            case LOAN_REPAYMENT -> {
                if (d.principal().signum() > 0) out.add(debit(LedgerAccount.LOANS.code(), d.principal(), null, null, d.partyId(), null, null, d.loanId()));
                if (d.interest().signum() > 0) out.add(debit(LedgerAccount.LOAN_INTEREST.code(), d.interest(), null, null, d.partyId(), overheadId, null, d.loanId()));
                out.add(credit(cashCode(d.tenantId(), d.financialAccountId()), d.total(), null, d.financialAccountId(), null, null, null, null));
            }
            case SETTLEMENT -> {
                out.add(debit(LedgerAccount.PAYABLES.code(), d.total(), null, null, d.partyId(), null, null, null));
                out.add(credit(LedgerAccount.RECEIVABLES.code(), d.total(), null, null, d.partyId(), null, null, null));
            }
            case WRITE_OFF -> {
                out.add(debit(LedgerAccount.LOSSES.code(), d.total(), null, null, d.partyId(), overheadId, null, null));
                out.add(credit(LedgerAccount.RECEIVABLES.code(), d.total(), null, null, d.partyId(), null, null, null));
            }
            case DEPRECIATION -> {
                Long costObject = lines.isEmpty() || lines.get(0).costObjectId() == null ? overheadId : lines.get(0).costObjectId();
                out.add(debit(LedgerAccount.DEPRECIATION.code(), d.total(), null, null, null, costObject, d.farmId(), null));
                out.add(credit(LedgerAccount.ACCUMULATED_DEPRECIATION.code(), d.total(), null, null, null, null, d.farmId(), null));
            }
            case OPENING_BALANCE -> {
                BigDecimal net = BigDecimal.ZERO;
                for (Line l : lines) {
                    boolean isDebit = "DEBIT".equals(l.side());
                    JournalLine j = new JournalLine(l.accountCode(), isDebit ? l.amount() : BigDecimal.ZERO, isDebit ? BigDecimal.ZERO : l.amount(),
                            null, l.financialAccountId(), l.partyId(), null, null, l.loanId());
                    out.add(j);
                    net = isDebit ? net.add(l.amount()) : net.subtract(l.amount());
                }
                if (net.signum() > 0) out.add(credit(LedgerAccount.OPENING_BALANCES.code(), net, null, null, null, null, null, null));
                if (net.signum() < 0) out.add(debit(LedgerAccount.OPENING_BALANCES.code(), net.negate(), null, null, null, null, null, null));
            }
        }
        requireBalanced(out);
        return out;
    }

    /** Writes a journal entry and its lines. */
    long write(long tenantId, long documentId, LocalDate date, String kind, List<JournalLine> lines) {
        requireBalanced(lines);
        long entryId = jdbc.sql("insert into sf_journal_entries (tenant_id, document_id, entry_date, kind) values (:t, :d, :date, :kind) returning id")
                .param("t", tenantId).param("d", documentId).param("date", date).param("kind", kind).query(Long.class).single();
        for (JournalLine l : lines) {
            jdbc.sql("""
                            insert into sf_journal_lines (tenant_id, entry_id, account_code, debit, credit, category_id, financial_account_id,
                                                          party_id, cost_object_id, farm_id, loan_id)
                            values (:t, :e, :a, :dr, :cr, :cat, :fin, :party, :co, :farm, :loan)""")
                    .param("t", tenantId).param("e", entryId).param("a", l.accountCode()).param("dr", l.debit()).param("cr", l.credit())
                    .param("cat", l.categoryId()).param("fin", l.financialAccountId()).param("party", l.partyId())
                    .param("co", l.costObjectId()).param("farm", l.farmId()).param("loan", l.loanId())
                    .update();
        }
        return entryId;
    }

    /** Writes the mirror image of a document's posting entry (posting rule P18). */
    void reverse(long tenantId, long documentId, LocalDate date) {
        List<JournalLine> original = jdbc.sql("""
                        select l.account_code, l.debit, l.credit, l.category_id, l.financial_account_id, l.party_id, l.cost_object_id, l.farm_id, l.loan_id
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        where e.document_id = :d and e.tenant_id = :t and e.kind = 'POSTING' order by l.id""")
                .param("d", documentId).param("t", tenantId)
                .query((rs, i) -> new JournalLine(rs.getString(1), rs.getBigDecimal(3), rs.getBigDecimal(2), (Long) rs.getObject(4),
                        (Long) rs.getObject(5), (Long) rs.getObject(6), (Long) rs.getObject(7), (Long) rs.getObject(8), (Long) rs.getObject(9)))
                .list();
        if (original.isEmpty()) throw new IllegalStateException("Document " + documentId + " has no posting entry");
        write(tenantId, documentId, date, "REVERSAL", original);
    }

    private String cashCode(long tenantId, Long financialAccountId) {
        return jdbc.sql("select account_code from sf_financial_accounts where id = :id and tenant_id = :t")
                .param("id", financialAccountId).param("t", tenantId).query(String.class).single();
    }

    private static Long costObject(Line l, long overheadId) {
        boolean profitAndLoss = LedgerAccount.find(l.accountCode()).map(LedgerAccount::isProfitAndLoss).orElse(false);
        if (!profitAndLoss) return null;
        return l.costObjectId() == null ? overheadId : l.costObjectId();
    }

    private static Long farm(Line l, Doc d) { return l.farmId() != null ? l.farmId() : d.farmId(); }

    private static JournalLine debit(String account, BigDecimal amount, Long category, Long fin, Long party, Long costObject, Long farm, Long loan) {
        return new JournalLine(account, amount, BigDecimal.ZERO, category, fin, party, costObject, farm, loan);
    }

    private static JournalLine credit(String account, BigDecimal amount, Long category, Long fin, Long party, Long costObject, Long farm, Long loan) {
        return new JournalLine(account, BigDecimal.ZERO, amount, category, fin, party, costObject, farm, loan);
    }

    static void requireBalanced(List<JournalLine> lines) {
        BigDecimal debit = lines.stream().map(JournalLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lines.stream().map(JournalLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (lines.isEmpty() || debit.compareTo(credit) != 0) {
            throw new IllegalStateException("Unbalanced journal: debit " + debit + " credit " + credit);
        }
    }
}
