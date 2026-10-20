export type Category = { id: number; code: string | null; name: string | null; kind: 'EXPENSE' | 'INCOME' | 'ASSET'; accountCode: string; system: boolean; active: boolean };
export type FinancialAccount = { id: number; name: string; kind: 'CASH' | 'BANK' | 'UPI'; active: boolean; balance: number };
export type Party = { id: number; name: string; phone?: string; village?: string; notes?: string; customer: boolean; supplier: boolean; lender: boolean; active: boolean; receivable: number; payable: number };
export type Loan = { id: number; name: string; lenderPartyId: number; lenderName: string; startDate: string; outstanding: number };
export type CostObject = { id: number; kind: 'OVERHEAD' | 'CROP_CYCLE'; name: string; cropCycleId?: number; farmId?: number; active: boolean };
export type FinanceSettings = { lockDate: string | null; fiscalYearStartMonth: number };

export type DocType = 'BILL' | 'SALE' | 'PAYMENT' | 'RECEIPT' | 'TRANSFER' | 'OWNER_IN' | 'OWNER_OUT' | 'LOAN_RECEIVED' | 'LOAN_REPAYMENT'
  | 'SETTLEMENT' | 'WRITE_OFF' | 'OPENING_BALANCE' | 'DEPRECIATION'
  | 'STOCK_ISSUE' | 'STOCK_TRANSFER' | 'WASTAGE' | 'STOCK_COUNT' | 'PRODUCE_RECEIPT' | 'PURCHASE_RETURN' | 'SALE_RETURN';
export const DOC_TYPES: DocType[] = ['BILL', 'SALE', 'PAYMENT', 'RECEIPT', 'TRANSFER', 'OWNER_IN', 'OWNER_OUT', 'LOAN_RECEIVED', 'LOAN_REPAYMENT',
  'SETTLEMENT', 'WRITE_OFF', 'OPENING_BALANCE', 'DEPRECIATION',
  'STOCK_ISSUE', 'STOCK_TRANSFER', 'WASTAGE', 'STOCK_COUNT', 'PRODUCE_RECEIPT', 'PURCHASE_RETURN', 'SALE_RETURN'];

export type DocumentSummary = {
  id: number; type: DocType; status: 'DRAFT' | 'POSTED' | 'REVERSED' | 'CANCELLED'; number: string | null; docDate: string; dueDate: string | null;
  partyId: number | null; partyName: string | null; financialAccountName: string | null; totalAmount: number; settledAmount: number;
  openAmount: number; paymentStatus: 'UNPAID' | 'PARTLY_PAID' | 'PAID' | null; needsReview: boolean; createdAt: string;
};
export type DocumentLine = {
  id: number; lineNo: number; categoryId: number | null; categoryCode: string | null; categoryName: string | null; accountCode: string; side: string | null;
  description: string | null; quantity: number | null; unit: string | null; rate: number | null; amount: number; costObjectId: number | null;
  costObjectName: string | null; farmId: number | null; partyId: number | null; financialAccountId: number | null; loanId: number | null;
  itemId: number | null; itemName: string | null; locationId: number | null; lotNo: string | null; baseQuantity: number | null;
};
/** A stock movement shown on a record (quantity in the item's base unit, value in rupees; negative is stock going out). */
export type StockMovement = { id: number; itemId: number; itemName: string; baseUnit: string; locationId: number; locationName: string; lotNo: string | null;
  movementType: string; quantity: number; value: number; unitCost: number; costObjectId: number | null };
export type Allocation = { id: number; sourceDocumentId: number; sourceNumber: string; sourceType: DocType; targetDocumentId: number; targetNumber: string;
  targetType: DocType; amount: number; allocationDate: string };
export type FinanceDocument = {
  summary: DocumentSummary; farmId: number | null; financialAccountId: number | null; toFinancialAccountId: number | null; toFinancialAccountName: string | null;
  loanId: number | null; loanName: string | null; principalAmount: number | null; interestAmount: number | null; notes: string | null; referenceNo: string | null;
  reversalDate: string | null; postedAt: string | null; originalDocumentId: number | null; lines: DocumentLine[]; allocations: Allocation[];
  movements: StockMovement[];
};

export type AmountRow = { accountCode: string; categoryId: number | null; categoryCode: string | null; categoryName: string | null; amount: number };
export type IncomeStatement = { from: string; to: string; basis: 'ACCRUAL' | 'CASH'; actual: boolean; operatingRevenue: AmountRow[]; operatingRevenueTotal: number;
  operatingCosts: AmountRow[]; operatingCostTotal: number; operatingProfit: number; otherIncome: AmountRow[]; otherIncomeTotal: number; interest: number;
  losses: number; netProfit: number };
export type CashFlow = { from: string; to: string; opening: number; operating: number; investing: number; financing: number; openingBalances: number;
  netChange: number; closing: number; details: { section: string; accountCode: string; amount: number }[] };
export type PartyDues = { partyId: number; partyName: string; balance: number; notDue: number; days1to30: number; days31to60: number; days61to90: number;
  over90: number; unmatched: number; advance: number };
export type Dues = { asOf: string; receivables: PartyDues[]; receivableTotal: number; payables: PartyDues[]; payableTotal: number };
export type Activity = { from: string; to: string; rows: { costObjectId: number; kind: string; name: string; revenue: number; costs: number; contribution: number }[] };
export type Summary = { fiscalYearFrom: string; asOf: string; yearRevenue: number; yearCosts: number; yearOperatingProfit: number; yearNetProfit: number;
  monthRevenue: number; monthCosts: number; cashTotal: number; receivableTotal: number; overdueReceivables: number; payableTotal: number };
