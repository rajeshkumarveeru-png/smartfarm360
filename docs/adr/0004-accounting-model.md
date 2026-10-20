# ADR 0004 — Accounting model

- Status: Accepted (2026-10-19). Detailed posting rules: [docs/finance/posting-rules.md](../finance/posting-rules.md) (draft, awaiting review).

## Context

The product must distinguish revenue from cash received, expenses from payments made, receivables, payables,
inventory value, capital expenditure, depreciation, loan principal and interest, operating profit, net profit and
cash flow — and avoid double counting internal transfers and stock that is bought but not yet used.

The existing schema stores money in three unrelated places (`sf_finance_entries`, `sf_purchases.paid_amount`,
`sf_farm_activities.cost`, plus `sf_harvest_records.sale_value`), so totals can double count or miss amounts.

## Decision

**Farmer-friendly documents on top of an internal, append-only journal.**

- Users work with documents: expense, income, purchase, sale, payment/receipt with allocations, loan, transfer.
  States: `DRAFT → POSTED → (CANCELLED | REVERSED)`. Only posted documents affect reports.
- Posting a document writes balanced journal lines (debits = credits) against a small built-in chart of accounts
  (cash/bank, receivables, payables, inventory, fixed assets, accumulated depreciation, loans, equity, revenue
  categories, expense categories). Farmers never see account codes; categories map to accounts.
- Journal lines are immutable. Corrections are reversal entries, never updates.
- Every journal line can carry a **cost object** (crop cycle, animal group, poultry batch, pond batch, manure batch,
  processing batch, or farm overhead) for activity profitability.
- Reports are computed from the journal:
  - **Accrual P&L** — revenue and expense accounts by period.
  - **Cash flow** — movements on cash/bank accounts only.
  - **Receivables / payables** — open balances from document totals minus allocated payments.
- Rules that prevent double counting:
  - Buying inputs debits inventory; the expense is recognised when stock is **consumed** (ADR 0005).
  - Internal transfers (manure to fields, produce to processing) move cost between cost objects and never create revenue.
  - Loan principal and capital contributions are balance-sheet movements, not income; interest is an expense.
  - Capital purchases are assets; depreciation is the expense.
- Estimates and forecasts are stored and labelled separately and never mixed into actual figures.

## Consequences

- Phase 3 starts with a written posting-rules document reviewed by the product owner before code.
- Existing `sf_finance_entries` and `sf_purchases` rows are migrated into posted documents by forward-only changesets
  with row-count verification.
