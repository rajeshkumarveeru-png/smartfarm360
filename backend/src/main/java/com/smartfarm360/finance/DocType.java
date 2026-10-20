package com.smartfarm360.finance;

/** Finance record types (posting rules, section 4; stock movement design, section 6) and their number prefixes. */
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
    DEPRECIATION("DEPR"),
    STOCK_ISSUE("ISSUE"),
    STOCK_TRANSFER("STRF"),
    WASTAGE("WASTE"),
    STOCK_COUNT("COUNT"),
    PRODUCE_RECEIPT("PROD"),
    PURCHASE_RETURN("PRET"),
    SALE_RETURN("SRET"),
    ALLOCATION("ALLOC"),
    ADVANCE("ADV"),
    ADVANCE_REPAYMENT("ADVR"),
    PAY_RUN("PAYRUN"),
    WAGE_PAYMENT("WAGE"),
    ASSET_DISPOSAL("DISP");

    private final String prefix;

    DocType(String prefix) { this.prefix = prefix; }

    public String prefix() { return prefix; }

    /** Bills and sales carry lines, can be drafts, and are settled through allocations. */
    public boolean isInvoice() { return this == BILL || this == SALE; }

    /** Records created by the stock module; they always move stock and post at once. */
    public boolean isStockRecord() {
        return this == STOCK_ISSUE || this == STOCK_TRANSFER || this == WASTAGE || this == STOCK_COUNT || this == PRODUCE_RECEIPT
                || this == PURCHASE_RETURN || this == SALE_RETURN;
    }

    /**
     * Records other modules build and post through {@link DocumentService#recordModule}: stock records, payroll
     * (workforce design W1–W3) and asset disposals and depreciation runs (G9, G11).
     */
    public boolean isModuleRecord() {
        return isStockRecord() || this == ADVANCE || this == ADVANCE_REPAYMENT || this == PAY_RUN || this == WAGE_PAYMENT
                || this == ASSET_DISPOSAL || this == DEPRECIATION;
    }

    /** Records that settle bills or sales through allocations. */
    public boolean allocates() {
        return this == PAYMENT || this == RECEIPT || this == SETTLEMENT || this == WRITE_OFF || this == PURCHASE_RETURN || this == SALE_RETURN;
    }

    /** Whether a settling record of this type may be allocated to a target of the given type. */
    public boolean canSettle(DocType target) {
        return switch (this) {
            case PAYMENT, PURCHASE_RETURN -> target == BILL;
            case RECEIPT, WRITE_OFF, SALE_RETURN -> target == SALE;
            case SETTLEMENT -> target == BILL || target == SALE;
            default -> false;
        };
    }

    /** Settlement by these counts as "paid" for the cash-basis view; write-offs and returns do not. */
    public boolean countsAsPaid() { return this == PAYMENT || this == RECEIPT || this == SETTLEMENT; }
}
