package com.smartfarm360.finance;

/** Finance record types (posting rules, section 4) and their number prefixes. */
public enum DocType {
    BILL("BILL"),
    SALE("SALE"),
    PAYMENT("PAY"),
    RECEIPT("RCPT"),
    TRANSFER("TRF"),
    OWNER_IN("OWNIN"),
    OWNER_OUT("OWNOUT"),
    LOAN_RECEIVED("LOAN"),
    LOAN_REPAYMENT("LREP"),
    SETTLEMENT("SETL"),
    WRITE_OFF("WOFF"),
    OPENING_BALANCE("OPEN"),
    DEPRECIATION("DEPR");

    private final String prefix;

    DocType(String prefix) { this.prefix = prefix; }

    public String prefix() { return prefix; }

    /** Bills and sales carry lines, can be drafts, and are settled through allocations. */
    public boolean isInvoice() { return this == BILL || this == SALE; }

    /** Records that settle bills or sales through allocations. */
    public boolean allocates() { return this == PAYMENT || this == RECEIPT || this == SETTLEMENT || this == WRITE_OFF; }

    /** Whether a settling record of this type may be allocated to a target of the given type. */
    public boolean canSettle(DocType target) {
        return switch (this) {
            case PAYMENT -> target == BILL;
            case RECEIPT, WRITE_OFF -> target == SALE;
            case SETTLEMENT -> target == BILL || target == SALE;
            default -> false;
        };
    }

    /** Settlement by these counts as "paid" for the cash-basis view; write-offs do not. */
    public boolean countsAsPaid() { return this == PAYMENT || this == RECEIPT || this == SETTLEMENT; }
}
