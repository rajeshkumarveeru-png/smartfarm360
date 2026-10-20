# Finance posting rules (Phase 3)

- Status: **Draft for product-owner review** (2026-10-20). No finance code is written until this is approved.
- Implements: [ADR 0004 — Accounting model](../adr/0004-accounting-model.md). Inventory details: [ADR 0005](../adr/0005-inventory-valuation.md).
- Decisions marked **D1–D12** need an answer; each has a recommendation.

## 1. Purpose and principles

SmartFarm360 must tell a farmer, for any period and farm, **what they earned, what they spent, what cash moved, who
owes them and whom they owe** — without counting anything twice. Farmers never see debits and credits. They record
everyday things (a bill, a sale, a payment, a loan instalment) and the system writes balanced journal entries behind
the scenes.

1. **Every report comes from one journal.** Income statement, cash flow, receivables, payables and balances are all
   sums over the same immutable journal lines. There are no separate money tables to keep in step.
2. **A journal entry always balances** (total debits = total credits, to the paisa), enforced in the database.
3. **Posted records are never edited or deleted.** Mistakes are fixed with a reversal plus a new record.
4. **Earned/spent and received/paid are separate events.** A sale on credit is revenue today and cash when the buyer
   pays; the reports show both truthfully.
5. **Moving value between your own things is never income.** This covers transfers between your cash and bank, manure
   used on your own fields, and produce sent to your own processing.
6. **Actual figures only.** Estimates and plans are never posted to the journal.

## 2. Ledger accounts

Each tenant gets a fixed set of system accounts (seeded, not editable). Farmers work with **categories** (section 3),
which map to these accounts.

| Code | Account | Type | Normal balance | Notes |
|---|---|---|---|---|
| 1000 | Cash in hand | Asset | Debit | One ledger account per cash box / person holding cash (via financial accounts) |
| 1010 | Bank and UPI accounts | Asset | Debit | One per bank account / UPI wallet |
| 1100 | Receivables (money owed to us) | Asset | Debit | Control account; per-party detail kept on each line |
| 1150 | Advances to workers | Asset | Debit | Phase 6 uses it; rules defined now |
| 1200 | Stock (purchased inputs and goods for resale) | Asset | Debit | Phase 4 |
| 1300 | Farm assets (equipment, structures, breeding animals — see **D8**) | Asset | Debit | |
| 1310 | Accumulated depreciation | Contra-asset | Credit | |
| 2000 | Payables (money we owe) | Liability | Credit | Control account; per-party detail kept on each line |
| 2100 | Wages payable | Liability | Credit | Phase 6 |
| 2200 | Loans | Liability | Credit | One sub-ledger per loan |
| 3000 | Owner's capital | Equity | Credit | Money the owner puts into the farm |
| 3100 | Owner's drawings | Equity | Debit | Household and personal spending from farm money (**D2**) |
| 3900 | Opening balances | Equity | Credit | Balancing account for starting balances |
| 4000 | Produce sales | Income | Credit | Crops, milk, eggs, animals, fish, manure, processed goods |
| 4100 | Other farm income | Income | Credit | Machine hire, land lease received, services |
| 4200 | Subsidies and grants | Income | Credit | Below operating profit (**D4**) |
| 4900 | Gain on sale of farm assets | Income | Credit | Below operating profit |
| 5000 | Farm inputs | Expense | Debit | Seed, fertilizer, pesticide, feed, medicine, fuel… |
| 5100 | Labour | Expense | Debit | |
| 5200 | Repairs and maintenance | Expense | Debit | |
| 5300 | Rent and lease | Expense | Debit | |
| 5400 | Electricity and water | Expense | Debit | |
| 5500 | Transport and marketing | Expense | Debit | Includes market/commission charges |
| 5600 | Other operating expenses | Expense | Debit | |
| 5700 | Cost of goods sold | Expense | Debit | Phase 4: purchased goods resold, at moving average cost |
| 5800 | Depreciation | Expense | Debit | Operating |
| 5900 | Loan interest | Expense | Debit | Below operating profit |
| 5950 | Bank charges | Expense | Debit | Operating |
| 5980 | Losses and write-offs | Expense | Debit | Bad debts, stock loss, asset loss; below operating profit |

## 3. Categories

- System categories in Tamil and English, for example: *Seeds → 5000*, *Fertilizer → 5000*, *Animal feed → 5000*,
  *Veterinary → 5000*, *Diesel → 5000*, *Labour → 5100*, *Tractor hire → 5600*, *Crop sale → 4000*,
  *Milk sale → 4000*, *Egg sale → 4000*, *Govt. subsidy → 4200*.
- A tenant can add its own categories. Each one must point to a system account of the matching type, so reports
  stay correct whatever the farmer calls a category.
- Legacy free-text categories are matched by name (case-insensitive) or created as tenant categories under
  *Other operating expenses* (5600) or *Other farm income* (4100).

## 4. Records farmers create

| Record | What it means | Needs a party? |
|---|---|---|
| **Expense / bill** | Something bought or a service received | Yes if any amount is left unpaid |
| **Sale / income** | Something sold or earned | Yes if any amount is left unreceived |
| **Payment** (out) / **Receipt** (in) | Money actually paid or received, allocated to bills/sales | Yes |
| **Transfer** | Cash ↔ bank between the farm's own accounts | No |
| **Owner money in / out** | Capital put in, or household spending (drawings) | No |
| **Loan received / loan repayment** | Repayment splits principal and interest | Lender (a party) |
| **Settlement** | Nets what one trader owes us against what we owe them | Yes |
| **Opening balance** | Starting cash, bank, dues and loans on the start date | Depends |
| **Write-off** | A due amount that will never be received | Yes |
| *Phase 4:* stock purchase, stock sale, consumption, adjustment | Stock movements with their money effect | — |
| *Phase 6:* wages, worker advance, asset purchase, depreciation, asset sale | — | — |

**Parties.** Customers and suppliers are one list of **parties**, with "buys from us" and "sells to us" flags. In
Indian farming the same trader often supplies inputs and buys the harvest; one party record makes settlement
possible (**D11**).

**Paid now.** Bills and sales can be entered as "paid now from/to account X". The system then creates the payment and
its allocation in the same transaction, so a farmer still records a cash purchase in one step.

## 5. Posting rules

`Dr` = debit, `Cr` = credit. Every line can also carry **farm**, **cost object** (crop cycle, animal group, poultry
batch, pond batch, manure batch, processing batch, or *farm overhead*) and **party**.

| # | Event | Journal | Effect on reports |
|---|---|---|---|
| P1 | Bill with a party (paid or not) | Dr expense account per line (cost object) / Cr 2000 Payables (party) | Expense on the bill date |
| P2 | Cash expense without a party (fully paid) | Dr expense per line / Cr 1000/1010 | Expense and cash out on the same date |
| P3 | Payment to a party | Dr 2000 Payables (party) / Cr 1000/1010 | Cash out; payables reduced |
| P4 | Sale with a party | Dr 1100 Receivables (party) / Cr 4000/4100 per line (cost object) | Revenue on the sale date |
| P5 | Cash sale without a party | Dr 1000/1010 / Cr 4000/4100 per line | Revenue and cash in |
| P6 | Receipt from a party | Dr 1000/1010 / Cr 1100 Receivables (party) | Cash in; receivables reduced |
| P7 | Transfer between own accounts | Dr receiving account / Cr sending account | **No** income or expense; not a cash flow |
| P8 | Owner puts money in | Dr 1000/1010 / Cr 3000 Owner's capital | Not income (financing cash flow) |
| P9 | Household/personal spending from farm money | Dr 3100 Drawings / Cr 1000/1010 | **Not** an expense (financing cash flow) |
| P10 | Loan received | Dr 1000/1010 / Cr 2200 Loan | Not income (financing) |
| P11 | Loan repayment | Dr 2200 Loan (principal) + Dr 5900 Interest / Cr 1000/1010 | Only the interest is an expense |
| P12 | Settlement with one trader | Dr 2000 Payables (party) / Cr 1100 Receivables (party), for the smaller balance | No profit or cash effect |
| P13 | Write-off of an uncollectable due | Dr 5980 Losses / Cr 1100 Receivables (party) | Loss, below operating profit |
| P14 | Opening balances | Dr/Cr each balance / Cr/Dr 3900 Opening balances | Never in any period's profit |
| P15 | Subsidy received in cash | Dr 1000/1010 / Cr 4200 | Other income |
| P16 | Overhead allocation (management) | Dr expense (target cost object) / Cr same expense (overhead cost object) | Net zero in total; moves cost to activities |
| P17 | Internal transfer of own produce or manure | Quantity moves in stock; cost moved with a P16-style net-zero line | **Never** revenue |
| P18 | Reversal of any posted record | Mirror of the original entry, linked to it | Cancels the original's effect on the reversal date |

### Unallocated payments and advances

- A payment or receipt does not have to match a bill exactly. The unallocated part stays on the party's balance.
- If a party's balance flips sign (we paid a supplier before the bill, or a buyer paid in advance), reports show it
  as an **advance**, not as negative payables or receivables.
- Partial payments are normal. A bill's status (*unpaid*, *partly paid*, *paid*) is computed from its allocations,
  never typed in.

### Stock and crops (Phase 4–5, defined now so Phase 3 does not have to change)

- Buying stock items: Dr 1200 Stock / Cr 2000 or cash. **Not** an expense yet.
- Using inputs on a crop, herd or pond: Dr 5000 Farm inputs (that cost object) / Cr 1200 Stock, at moving average cost.
- Selling purchased goods: Dr 5700 Cost of goods sold / Cr 1200 Stock, plus the sale (P4/P5).
- **Farm-produced stock** (harvest, milk, eggs, manure) is tracked by quantity but **carried at nil value** (**D3**).
  Its growing costs are already expenses tagged to the cost object. Revenue is recognised only when it is sold.

## 6. Document lifecycle

| State | Journal? | Can change? | How it ends |
|---|---|---|---|
| **Draft** | No | Yes, freely | Post, or cancel (deleted from lists; kept for audit) |
| **Posted** | Yes | No | Reverse (optionally "correct" = reverse + new draft prefilled) |
| **Reversed** | Original + reversal | No | — |

- A posted bill or sale with allocated payments cannot be reversed until those allocations are removed (or the
  payments reversed). The UI explains this.
- The reversal date defaults to the original date when that period is open, otherwise today.
- **Period lock** (**D9**): an owner or accountant can set a *lock date*. Nothing can be posted, reversed or
  allocated on or before it.

## 7. Calculations and reports

All reports state their date range, farm/cost-object filter, and whether they show actual figures (always, in Phase 3).

| Figure | Definition |
|---|---|
| **Operating revenue** | Credits − debits on 4000 + 4100, by entry date |
| **Operating costs** | Debits − credits on 5000–5800 and 5950 |
| **Operating profit** | Operating revenue − operating costs |
| **Other income** | 4200 Subsidies + 4900 Gains |
| **Net profit** | Operating profit + other income − 5900 Interest − 5980 Losses |
| **Cash flow** | Movements on cash and bank accounts only, grouped by the other side of each entry: *operating* (income, expense, receivables, payables, advances, wages), *investing* (farm assets), *financing* (loans incl. interest, capital, drawings). Own-account transfers are excluded |
| **Receivables / payables** | Open party balances, aged by due date (0–30, 31–60, 61–90, 90+ days); sign flips show as advances |
| **Balances** | Each cash/bank account at a date |
| **Activity profitability** | Revenue − costs per cost object, split into *direct*, *allocated overhead*, and (shown separately) *unallocated overhead* |
| **Cash-basis view** (**D10**) | Optional toggle: revenue and expenses counted when paid/received, using allocations |

- **Fiscal year** (**D1**): April–March by default (tenant setting); monthly and custom ranges supported.
- **Money:** `NUMERIC(14,2)` rupees. Line amount = quantity × rate, rounded half-up to the paisa **per line**;
  document total = sum of rounded lines. INR only. GST is out of scope (**D12**).
- **Numbering:** gap-free per tenant, per record type, per fiscal year, e.g. `BILL/2026-27/0001`.
- **Duplicates:** every create/post/payment call takes an `Idempotency-Key`. The same key with the same content
  returns the first result; with different content it is refused (409). This protects double taps and mobile retries.

## 8. Worked example (June, one farm, Samba paddy cycle)

Start: owner has ₹20,000 cash (opening balance, P14).

| Date | What happened | Journal |
|---|---|---|
| 2 Jun | Seed from trader Raman on credit, ₹4,000, for Samba cycle | Dr 5000 Inputs (Samba) 4,000 / Cr 2000 Payables (Raman) 4,000 |
| 5 Jun | Pay Raman ₹1,500 cash | Dr 2000 (Raman) 1,500 / Cr 1000 Cash 1,500 |
| 10 Jun | Crop loan ₹1,00,000 into bank | Dr 1010 Bank 1,00,000 / Cr 2200 Loan 1,00,000 |
| 12 Jun | Buy a pump from bank, ₹24,000, 5-year life | Dr 1300 Assets 24,000 / Cr 1010 Bank 24,000 |
| 20 Jun | Sell paddy to Raman ₹50,000; ₹30,000 received in bank | Dr 1100 Receivables (Raman) 50,000 / Cr 4000 Sales (Samba) 50,000; Dr 1010 Bank 30,000 / Cr 1100 (Raman) 30,000 |
| 25 Jun | Labour paid in cash ₹6,000 | Dr 5100 Labour (Samba) 6,000 / Cr 1000 Cash 6,000 |
| 28 Jun | Household spending ₹3,000 from farm cash | Dr 3100 Drawings 3,000 / Cr 1000 Cash 3,000 |
| 30 Jun | Loan instalment ₹10,000 (₹8,000 principal + ₹2,000 interest) | Dr 2200 Loan 8,000 + Dr 5900 Interest 2,000 / Cr 1010 Bank 10,000 |
| 30 Jun | Pump depreciation (₹24,000 ÷ 60 months) | Dr 5800 Depreciation (overhead) 400 / Cr 1310 Acc. depreciation 400 |

**Profit (June, accrual):** revenue ₹50,000; operating costs ₹4,000 + ₹6,000 + ₹400 = ₹10,400;
**operating profit ₹39,600**; interest ₹2,000; **net profit ₹37,600**. The loan, the pump purchase, the
principal repayment and household spending are correctly **not** income or expenses.

**Cash flow (June):** operating +₹30,000 − ₹1,500 − ₹6,000 = **+₹22,500**; investing **−₹24,000**; financing
+₹1,00,000 − ₹8,000 − ₹2,000 − ₹3,000 = **+₹87,000**. Net change ₹85,500, so cash + bank goes from ₹20,000 to
**₹1,05,500** (cash ₹9,500, bank ₹96,000).

**Dues:** Raman owes us ₹20,000 and we owe Raman ₹2,500. A settlement (P12) leaves **Raman owes ₹17,500**, with no
change to profit or cash.

**Samba cycle:** revenue ₹50,000 − direct costs ₹10,000 = **₹40,000 contribution**. The ₹400 depreciation is
unallocated overhead until an allocation rule assigns it (P16).

Phase 3 will implement this exact scenario as an automated test, and every figure above must match.

## 9. Moving existing data (forward-only changesets, totals verified)

Your local database has no legacy finance rows today (checked 2026-10-20); these rules protect any other installation.

| Legacy data | Becomes |
|---|---|
| `sf_finance_entries`, PAID | Posted expense/sale paid from/to a per-tenant financial account *"Cash (migrated)"* (P2/P5) |
| `sf_finance_entries`, UNPAID | Posted bill/sale against a per-tenant party *"Unknown (migrated)"*, left open (P1/P4) |
| `sf_finance_entries`, PARTIAL | As UNPAID plus a *needs review* flag, because the paid amount was never recorded (**D6**) |
| `sf_purchases` (money side) | Posted bill Dr 1200 Stock / Cr Payables (supplier), with the paid part as a payment from *"Cash (migrated)"*. Quantities stay in the old table until Phase 4 |
| `sf_suppliers` | Parties with the "sells to us" flag (same ids kept in a mapping column) |
| `sf_farm_activities.cost` | Not posted; shown in crop views as *recorded activity cost (not in accounts)* (**D7**) |
| `sf_harvest_records.sale_value` | Not revenue; shown as *recorded harvest value (memo)*. Revenue comes only from sales |

The migration checks that row counts and totals by type, status and tenant match before and after. Old tables become
read-only and are dropped in a later contract step. Until Phase 4, the existing "Record purchase" screen also
creates the bill.

## 10. Permissions

| Action | Permission | Roles (default) |
|---|---|---|
| View finance and reports | `FINANCE_READ` | Owner, farm admin, manager, accountant, viewer |
| Create drafts, post, record payments | `FINANCE_WRITE` | Owner, farm admin, manager, accountant |
| Reverse posted records, write off dues | `FINANCE_REVERSE` *(new)* | Owner, farm admin, accountant |
| Set lock date, opening balances, categories | `FINANCE_MANAGE` *(new)* | Owner, farm admin, accountant |

Every post, reversal, write-off, settlement and lock-date change is audited.

## 11. Data model (for implementation)

`sf_ledger_accounts` (seeded system accounts) · `sf_finance_categories` (system + tenant, mapped to an account) ·
`sf_financial_accounts` (cash/bank/UPI, each with its own ledger account) · `sf_parties` · `sf_cost_objects` ·
`sf_finance_documents` + `sf_finance_document_lines` · `sf_payment_allocations` · `sf_journal_entries` +
`sf_journal_lines` (append-only: no UPDATE/DELETE, enforced by trigger; balanced per entry by a deferred constraint
trigger) · `sf_document_sequences` · `sf_idempotency_keys` · lock date on `sf_tenant_settings`.
Every table has `tenant_id` and composite tenant foreign keys, as today.

## 12. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **D1** | Default fiscal year? | **April–March** (Indian financial year), changeable per tenant |
| **D2** | Treat household/personal spending from farm money as *owner's drawings*, not an expense? | **Yes.** Otherwise farm profit looks far lower than it is |
| **D3** | Value farm-produced stock (harvest, milk, manure) at nil, expensing costs as incurred? | **Yes, nil.** Simple and conservative; per-cycle profitability still comes from cost objects. Cost-absorption can be added later |
| **D4** | Subsidies below operating profit as *other income*? | **Yes**, so operating profit shows the farm's own performance |
| **D5** | Record loan interest when it is charged or paid, without month-end accruals? | **Yes** for now; accruals can come later |
| **D6** | Legacy PARTIAL entries: keep open with a *needs review* flag? | **Yes**; we cannot invent the paid amount |
| **D7** | Legacy activity costs: memo only, not posted? | **Yes**; posting them could double count bills already entered |
| **D8** | Breeding/milch animals bought as farm assets (not expenses)? | **Yes**, capitalised without depreciation until the livestock phase decides |
| **D9** | Add a lock date in Phase 3? | **Yes**, owner/accountant only |
| **D10** | Default report basis? | **Accrual**, with a cash-basis toggle |
| **D11** | One *parties* list for customers and suppliers, with settlement? | **Yes** |
| **D12** | GST? | **Out of scope** for Phase 3; revisit with processing/trading |

## 13. What Phase 3 delivers once this is approved

Ledger, categories, financial accounts, parties, cost objects, bills, sales, payments/receipts with allocations,
transfers, owner money, loans, settlements, write-offs, opening balances, reversal, lock date, numbering,
idempotency, legacy migration, and reports (income statement, cash flow, receivables/payables aging, balances,
activity profitability) on web and mobile in Tamil and English. Tests include section 8 as a golden scenario,
journal-balance and immutability checks, duplicate-payment tests, rollback tests, cross-tenant tests and the
legacy-migration totals check.
