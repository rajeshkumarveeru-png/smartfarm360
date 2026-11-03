package com.smartfarm360.finance;

import java.util.Arrays;
import java.util.Optional;

/**
 * System ledger accounts (posting rules, section 2). Codes match {@code sf_ledger_accounts} (changeset 033);
 * a migration test enforces this.
 */
public enum LedgerAccount {
    CASH("1000", Type.ASSET, Flow.CASH),
    BANK("1010", Type.ASSET, Flow.CASH),
    RECEIVABLES("1100", Type.ASSET, Flow.OPERATING),
    WORKER_ADVANCES("1150", Type.ASSET, Flow.OPERATING),
    STOCK("1200", Type.ASSET, Flow.OPERATING),
    FARM_ASSETS("1300", Type.ASSET, Flow.INVESTING),
    ACCUMULATED_DEPRECIATION("1310", Type.CONTRA_ASSET, Flow.INVESTING),
    PAYABLES("2000", Type.LIABILITY, Flow.OPERATING),
    WAGES_PAYABLE("2100", Type.LIABILITY, Flow.OPERATING),
    LOANS("2200", Type.LIABILITY, Flow.FINANCING),
    OWNER_CAPITAL("3000", Type.EQUITY, Flow.FINANCING),
    OWNER_DRAWINGS("3100", Type.EQUITY, Flow.FINANCING),
    OPENING_BALANCES("3900", Type.EQUITY, Flow.OPENING),
    PRODUCE_SALES("4000", Type.INCOME, Flow.OPERATING),
    OTHER_FARM_INCOME("4100", Type.INCOME, Flow.OPERATING),
    SUBSIDIES("4200", Type.INCOME, Flow.OPERATING),
    ASSET_SALE_GAIN("4900", Type.INCOME, Flow.INVESTING),
    FARM_INPUTS("5000", Type.EXPENSE, Flow.OPERATING),
    LABOUR("5100", Type.EXPENSE, Flow.OPERATING),
    REPAIRS("5200", Type.EXPENSE, Flow.OPERATING),
    RENT_LEASE("5300", Type.EXPENSE, Flow.OPERATING),
    ELECTRICITY_WATER("5400", Type.EXPENSE, Flow.OPERATING),
    TRANSPORT_MARKETING("5500", Type.EXPENSE, Flow.OPERATING),
    OTHER_OPERATING("5600", Type.EXPENSE, Flow.OPERATING),
    /** Own produce moved between activities (P17); nets to zero for the farm. */
    OWN_FARM_INPUTS("5650", Type.EXPENSE, Flow.OPERATING),
    COST_OF_GOODS_SOLD("5700", Type.EXPENSE, Flow.OPERATING),
    DEPRECIATION("5800", Type.EXPENSE, Flow.OPERATING),
    LOAN_INTEREST("5900", Type.EXPENSE, Flow.FINANCING),
    BANK_CHARGES("5950", Type.EXPENSE, Flow.OPERATING),
    LOSSES("5980", Type.EXPENSE, Flow.OPERATING);

    public enum Type { ASSET, CONTRA_ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

    /** Cash-flow section that cash movements against this account belong to (posting rules, section 7). */
    public enum Flow { CASH, OPERATING, INVESTING, FINANCING, OPENING }

    private final String code;
    private final Type type;
    private final Flow flow;

    LedgerAccount(String code, Type type, Flow flow) {
        this.code = code;
        this.type = type;
        this.flow = flow;
    }

    public String code() { return code; }
    public Type type() { return type; }
    public Flow flow() { return flow; }

    public boolean isCash() { return flow == Flow.CASH; }
    public boolean isProfitAndLoss() { return type == Type.INCOME || type == Type.EXPENSE; }

    /** Operating revenue: produce sales and other farm income. */
    public boolean isOperatingRevenue() { return this == PRODUCE_SALES || this == OTHER_FARM_INCOME; }

    /** Other income shown below operating profit: subsidies (D4) and gains on asset sales. */
    public boolean isOtherIncome() { return this == SUBSIDIES || this == ASSET_SALE_GAIN; }

    /** Operating costs: inputs to depreciation, plus bank charges. Interest and losses are below operating profit. */
    public boolean isOperatingCost() { return type == Type.EXPENSE && this != LOAN_INTEREST && this != LOSSES; }

    public static LedgerAccount ofCode(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Unknown ledger account " + code));
    }

    public static Optional<LedgerAccount> find(String code) {
        return Arrays.stream(values()).filter(a -> a.code.equals(code)).findFirst();
    }
}
