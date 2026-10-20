package com.smartfarm360.finance;

import java.math.BigDecimal;

/**
 * A journal line supplied by the stock module (cost of goods sold, stock used, returns). Exactly one of debit and
 * credit is positive. {@code accountCode} may be null when {@code financialAccountId} names the cash or bank account.
 * Profit-and-loss lines without a cost object go to overhead.
 */
public record JournalPart(String accountCode, BigDecimal debit, BigDecimal credit, Long categoryId, Long partyId,
                          Long financialAccountId, Long costObjectId, Long farmId) {
    public static JournalPart debit(String account, BigDecimal amount, Long categoryId, Long partyId, Long costObjectId) {
        return new JournalPart(account, amount, BigDecimal.ZERO, categoryId, partyId, null, costObjectId, null);
    }

    public static JournalPart credit(String account, BigDecimal amount, Long categoryId, Long partyId, Long costObjectId) {
        return new JournalPart(account, BigDecimal.ZERO, amount, categoryId, partyId, null, costObjectId, null);
    }

    public static JournalPart cash(long financialAccountId, BigDecimal debit, BigDecimal credit) {
        return new JournalPart(null, debit, credit, null, null, financialAccountId, null, null);
    }
}
