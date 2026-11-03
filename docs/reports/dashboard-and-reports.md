# Dashboard and core reports design (Phase 7)

- Status: **Approved** (2026-10-21, H1–H14 as recommended) and implemented on `feature/phase-7-reports`; see section 14.
- Builds on:
  - the approved [finance posting rules](../finance/posting-rules.md): the chart of accounts, accrual and cash basis, and
    the fiscal year setting;
  - the [stock design](../inventory/stock-movements.md): valuation and alerts;
  - the [crop design](../crops/crop-management.md): cycle reports and estimates;
  - the [workforce and assets design](../workforce/workforce-and-assets.md): labour, wages, depreciation and maintenance.
- Decisions marked **H1–H14** need an answer. Each has a recommendation.

## 1. What this phase fixes

- **Reports are scattered.** Finance, stock, crops, workers and assets each have their own report tab. Nothing shows the
  whole farm business on one screen.
- **Some reports are missing:**
  - no balance sheet;
  - no trial balance;
  - no month-by-month profit and loss;
  - no breakdown of where the money went by category;
  - no statement of account for a trader;
  - no wages report by worker;
  - no maintenance cost report.
- **Exports are CSV only,** and only for stock, crops and the asset register. There is no XLSX or PDF, and finance
  reports cannot be exported at all.
- **The phone home screen is out of date.** It adds up the entries saved on the phone, not the farm business's accounts.

## 2. Principles

1. **One calculation.** Every figure comes from the module that owns it:
   - finance: the ledger;
   - stock: balances and lots;
   - crops, workers and assets: their own reports.

   The dashboard and exports call the same services as the report screens, so a figure is never computed twice in two
   ways. A test checks that each dashboard tile equals the matching report.
2. **Every report says what it is.** Each report shows:
   - the farm business;
   - the date range or "as of" date;
   - the basis (accrual or cash);
   - any farm or crop filter;
   - what it includes and leaves out (for example, "draft records are not included");
   - whether figures are **actual** or a labelled **estimate**.

   Exports carry the same header.
3. **Permissions apply to every tile, report and export,** checked on the server. A field supervisor's dashboard shows
   crops, attendance, stock alerts and maintenance, but no money totals. An export is refused exactly where the screen
   would be.
4. **Tenant-checked exports.** An export is built inside the tenant context, like any other request. It is recorded in
   the activity history (which report and which range, never the figures). It is never cached across tenants.
5. **Exact money.** Totals are BigDecimal sums. XLSX cells hold numbers with two decimals, not text. Rounding happens only
   where the source report already rounds.

## 3. Dashboard (H1–H4)

One screen, the new landing page of a farm business (H1). It has three parts.

**A. Money at a glance** (needs `FINANCE_READ`):
- cash and bank balance, by account;
- receivables, with the overdue amount;
- payables;
- this month's income, costs and operating profit;
- this fiscal year's income, costs and net profit;
- loans outstanding.

**B. Trends** (needs `FINANCE_READ`):
- the last 12 months of income and costs, as columns, with operating profit as a line;
- this month's five largest cost categories.

Charts are small built-in SVG drawings, with no chart library (H3). Each has a table view for screen readers and
printing.

**C. Needs attention.** Each item appears only when the user has the permission and the module is on:

| Item | From | Permission |
|---|---|---|
| Overdue sales over 30 days, by customer | dues | `FINANCE_READ` |
| Bills due in the next 7 days | dues | `FINANCE_READ` |
| Low stock and lots expiring within 30 days | stock alerts | `INVENTORY_READ` |
| Crops in the field: stage, days since sowing, profit to date | crop reports | `CROP_READ` |
| Wages owed and advances outstanding | workforce | `WORKFORCE_READ` |
| Attendance not marked yesterday (workers employed, no entries) | workforce | `WORKFORCE_READ` |
| Maintenance due or overdue | assets | `ASSETS_READ` |
| Depreciation not posted for a month that has ended | assets | `FINANCE_MANAGE` |
| Asset costs not in the register; stock, worker or asset reconciliation not agreeing | reconciliations | the module's read permission |
| Draft records older than 7 days | finance | `FINANCE_READ` |

Each item links to the screen where it is dealt with.

**Endpoint.** One endpoint, `GET /dashboard?asOf=`, returns the tiles the user may see (H2).

- It lives in a new read-only `reporting` package. That package may call the finance, inventory, operations, workforce
  and assets services.
- Nothing may depend on `reporting`, which `ModuleBoundaryTest` enforces.
- If one section fails, the rest still return. The failed tile reports an error code and is shown as "could not load".

**Freshness.** Figures are computed on request; there is no cache table (H4). A dashboard for a farm business with
50,000 journal lines must answer in under one second on the test database. A performance test with generated data
guards this.

## 4. Report catalogue (H5, H6)

Existing reports keep their screens and gain exports. **New** marks a report this phase adds.

| Report | Contents | Permission |
|---|---|---|
| Income statement | As now (operating revenue, costs, other income, interest, losses, net profit). Accrual or cash; farm or crop filter. Gains comparison columns (H10). | `FINANCE_READ` |
| **Monthly profit and loss** | Income statement lines as rows, months of the chosen range (up to 12) as columns, plus a total column. The total equals the income statement for the range. | `FINANCE_READ` |
| **Category breakdown** | Costs (or income) by category for a range, with share of total, the previous period and the change. Can be filtered by farm or crop. | `FINANCE_READ` |
| **Balance sheet** | As of a date: cash and bank, receivables, worker advances, stock, farm assets less accumulated depreciation, payables, wages payable, loans, owner's capital less drawings, opening balances, and profit to date (this year and earlier years). Must balance, or it shows the difference as an error. | `FINANCE_READ` |
| **Trial balance** | Every ledger account's debit and credit totals for a range and its closing balance. For accountants (H6). | `FINANCE_MANAGE` |
| Cash flow | As now (operating, investing, financing, opening, closing). | `FINANCE_READ` |
| Receivables and payables ageing | As now (dues by age band). | `FINANCE_READ` |
| **Party statement** | One customer, supplier, lender or worker: opening balance, each record with running balance, closing balance, open items. Printable to hand over. | `FINANCE_READ` (workers: `WORKFORCE_READ`) |
| Activity profitability | As now, adding area, profit per acre, and own costs versus allocated overhead. | `FINANCE_READ` |
| Crop cycle and season comparison | As now. The unsold produce estimate is labelled. | `CROP_READ` |
| Stock on hand and valuation | As now. | `INVENTORY_READ` |
| **Wages** | For a range: by worker (days, wages, paid, advance given and recovered, owed at the end) and by crop (days, cost). | `WORKFORCE_READ`; amounts need `PAYROLL_MANAGE` or `FINANCE_READ` |
| **Maintenance and running costs** | For a range, by asset: depreciation, running costs by category, hours used, cost per hour, maintenance done, plans due. | `ASSETS_READ`; amounts need `FINANCE_READ` |
| Asset register | As now. | `ASSETS_READ` |

A **Reports** page in the web menu lists every report the user may open, grouped by money, crops, stock, workers and
assets. The reports still open inside their own sections.

## 5. Exports (H7–H9)

- **CSV** for every report (H7): UTF-8 with a byte-order mark so Excel shows Tamil correctly, then header lines, then
  the table. Numbers use plain digits with a dot ("125000.50"), never Indian grouping, so spreadsheets read them as
  numbers.
- **XLSX** for every report (H7):
  - Built on the server with Apache POI in streaming mode. This is one new library; it adds no new service.
  - The first rows are the report header (principle 2). Then comes the table, with real number cells formatted
    `#,##,##0.00`, bold totals and frozen header rows.
  - Labels are in the user's language.
- **PDF** through the browser's "Print / Save as PDF" (H8):
  - Every report and party statement has a print layout: A4, the header block, page numbers, and repeated table
    headings.
  - Server PDF libraries cannot shape Tamil script correctly without a heavy font-shaping stack. The browser can.
  - This gives correct Tamil and English PDFs with no new server dependency. The trade-off: a PDF cannot be produced
    without a browser, for example for emailed reports. Scheduled emailed reports are left for after Phase 14.
- **Limits** (H9):
  - A range covers at most 5 years.
  - Exports are rate limited to 30 a minute per user, with the existing in-memory limiter.
  - Each export is recorded in the activity history as `REPORT_EXPORTED`, with the report, format and range.

## 6. Comparisons, periods and filters (H10, H11)

- **Comparison columns** (H10): the income statement and the category breakdown can add "previous period" (the same
  length, just before) or "same period last year". Each shows the change in rupees and in percent. When the earlier
  amount is zero, the percent is shown as "—".
- **Quick ranges** (H11) on every report:
  - this month and last month;
  - this quarter;
  - this fiscal year and last fiscal year, following the fiscal year start in finance settings (April by default);
  - custom.

  The fiscal year label is written "2026-27".
- **Filters** are only those the source report already supports: farm, crop, basis. No new filter dimensions in this
  phase.

## 7. Actual and estimated figures (H12)

- Every Phase 7 report is **actual**: posted records only. Drafts and reversed records cancel out; drafts are never
  counted.
- The only estimates anywhere are the existing labelled ones: unsold produce value in crop reports.
- The dashboard never adds an estimate into a total. A crop tile shows "profit to date (actual)" and, separately and
  labelled, "unsold produce (estimate)".

## 8. Phone (H13)

The Home tab shows the farm business's real figures from the dashboard endpoint, limited to what the role may see:
- cash and bank;
- this month's income and costs;
- receivables and overdue;
- payables;
- the "needs attention" list.

Entries waiting on the phone are shown separately as "not yet sent".

Offline, the last dashboard fetched is shown with "as of <time>". There are no exports on the phone.

## 9. Worked example (fiscal year 2026-27, as of 31 July 2026)

The design is checked against this scenario. It becomes the golden test.

| Date | Record |
|---|---|
| 1 Apr | Owner brings ₹2,00,000 into the bank (owner's capital) |
| 10 Apr | Tractor ₹1,20,000 bought on credit from Raman Traders and registered: 60 months, no salvage, ₹2,000 a month from May |
| Apr–Jul | Seed and fertilizer bills paid from the bank: ₹30,000 |
| Apr–Jul | Pay runs ₹24,000; wages paid from the bank ₹20,000 |
| 15 Jul | Paddy sold to Selvam ₹90,000; ₹50,000 received into the bank |
| 20 Jul | ₹70,000 paid to Raman Traders |
| May–Jul | Depreciation runs ₹6,000 |

**Income statement, April to July:**

| | Amount |
|---|---|
| Revenue | ₹90,000 |
| Costs: inputs ₹30,000 + labour ₹24,000 + depreciation ₹6,000 | ₹60,000 |
| Net profit | **₹30,000** |

**Balance sheet as of 31 July:**

| Assets | Amount | Liabilities and equity | Amount |
|---|---|---|---|
| Bank | ₹1,30,000 | Payable to Raman Traders | ₹50,000 |
| Receivable from Selvam | ₹40,000 | Wages payable | ₹4,000 |
| Tractor ₹1,20,000 less depreciation ₹6,000 | ₹1,14,000 | Owner's capital | ₹2,00,000 |
| | | Profit this year | ₹30,000 |
| **Total** | **₹2,84,000** | **Total** | **₹2,84,000** |

The bank balance is 2,00,000 − 30,000 − 20,000 + 50,000 − 70,000 = ₹1,30,000.

**Dashboard as of 31 July:**
- cash and bank ₹1,30,000;
- receivables ₹40,000 (16 days old, so not yet in the "over 30 days" attention list);
- payables ₹50,000;
- year net profit ₹30,000;
- wages owed ₹4,000.

The monthly profit and loss columns for April to July add up to ₹30,000. The trial balance totals agree.

## 10. Screens

**Web:**
- *Dashboard*: the landing page; replaces "Farms & land" as the first page after sign-in.
- *Reports*: the catalogue.
- New report screens: monthly profit and loss, category breakdown, balance sheet, trial balance, party statement
  (opened from a party), wages, and maintenance and running costs.
- Every report screen gets *Export CSV*, *Export Excel* and *Print / Save as PDF*.

**Mobile:** the Home tab as in section 8.

## 11. Data model and API (for implementation)

**No new tables.** One forward-only migration adds:
- the `REPORT_EXPORTED` audit action;
- indexes that the performance test shows are needed, for example on journal lines by tenant, account and date.

**API:**
- New endpoints, under `/api/v1/tenants/{tenantId}`:
  - `GET /dashboard`
  - `GET /finance/reports/monthly`
  - `GET /finance/reports/categories`
  - `GET /finance/reports/balance-sheet`
  - `GET /finance/reports/trial-balance`
  - `GET /finance/parties/{id}/statement`
  - `GET /workforce/reports/wages`
  - `GET /assets/reports/running-costs`
- Every report endpoint accepts `format=csv|xlsx` (JSON by default) and `compare=previous|lastYear` where it applies.
- Each new endpoint gets cross-tenant and permission tests, like the existing ones.

## 12. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **H1** | Make the dashboard the landing page of a farm business? | **Yes** |
| **H2** | One dashboard endpoint in a new read-only `reporting` package that only reads other modules, with tiles filtered by permission and failing one at a time? | **Yes** |
| **H3** | Draw charts as small built-in SVG (with a table view) rather than adding a chart library? | **Yes**; it keeps the bundle smaller |
| **H4** | Compute figures on request (no summary tables or caches), guarded by a performance test at 50,000 journal lines? | **Yes**; add summaries only if the test fails |
| **H5** | Add the new reports in section 4: monthly profit and loss, category breakdown, balance sheet, party statement, wages, maintenance and running costs? | **Yes** |
| **H6** | Add a trial balance, for accountants only (`FINANCE_MANAGE`)? | **Yes** |
| **H7** | CSV and XLSX for every report; XLSX built with Apache POI on the server? | **Yes** |
| **H8** | PDF through the browser's print and save (correct Tamil, no server PDF library), leaving emailed and scheduled reports for later? | **Yes** |
| **H9** | Exports limited to 5-year ranges and 30 a minute per user, and recorded in the activity history? | **Yes** |
| **H10** | Comparison with the previous period or the same period last year on the income statement and category breakdown? | **Yes** |
| **H11** | Quick ranges following the fiscal year setting (April–March by default), labelled like "2026-27"? | **Yes** |
| **H12** | Reports are actual only; existing estimates stay labelled and are never added into dashboard totals? | **Yes** |
| **H13** | Phone Home tab shows the server dashboard (by role), with the last copy shown offline with its time; no exports on the phone? | **Yes** |
| **H14** | Recurring expenses (deferred since Phase 3): include them in this phase? | **No**: keep this phase to reading and exporting; schedule recurring expenses with Phase 14 or as a small follow-up |

## 13. What Phase 7 delivers once this is approved

**Features:**
- the dashboard with money tiles, a 12-month trend and the "needs attention" list, by permission;
- the report catalogue;
- the new reports: monthly profit and loss, category breakdown, balance sheet, trial balance, party statement, wages,
  and maintenance and running costs;
- comparison columns and quick ranges;
- CSV, XLSX and printable PDF for every report, in Tamil and English;
- export audit and limits;
- the phone home screen with real figures.

**Tests:**
- the section 9 golden scenario for the income statement, balance sheet, trial balance, monthly profit and loss and
  dashboard;
- the balance sheet balancing after every existing golden scenario (finance, stock, crops, workforce and assets);
- dashboard tiles equal to their source reports;
- monthly columns adding up to the period;
- comparison arithmetic, including a zero base;
- XLSX cells being numbers with the header block;
- CSV with a byte-order mark and Tamil labels;
- permission filtering of tiles, reports and exports;
- cross-tenant tests for every new endpoint;
- the export rate limit and audit;
- the dashboard performance test.

## 14. Implementation notes (2026-10-21)

- **Packages.** The dashboard lives in a new `reporting` package. It may read finance, inventory, crop, operations,
  workforce and assets. Nothing may depend on it (`ModuleBoundaryTest`, rule `nothingDependsOnReporting`). The rules
  that kept workforce, assets and operations private were widened to let `reporting` read them, as H2 requires.
- **New report code:**
  - `finance.LedgerReportService`: monthly profit and loss, category breakdown, comparisons, balance sheet, trial
    balance, party statement, and the dashboard's bills-due and old-drafts checks;
  - `WorkerService.wages`: the wages report;
  - `AssetService.runningCosts`: the running-costs report;
  - `AssetService.depreciationDue`: the dashboard's depreciation check.
- **Exports:**
  - Shared code is in `platform.export`: `ReportDocument`, `ExportService`, `ReportLabels`, `CsvWriter`.
  - Each module lays out its own reports: `FinanceExports`, `StockExports`, `CropExports`, `WorkforceExports`,
    `AssetExports`.
  - A report endpoint answers JSON, or a file when `format=csv|xlsx`. `lang=en|ta` sets the language of the labels.
  - The older `.csv` endpoints stay for existing links. The asset register has a new `reports/register?format=` endpoint.
  - Labels come from `shared/i18n`, which the build copies into the jar. A test fails if an export uses a label key
    that is not in the catalog.
- **XLSX numbers:**
  - Spreadsheet number cells are binary floating point by the file format's definition. Amounts up to ₹10¹² with two
    decimals display exactly. The exact decimal values are also in the JSON and the CSV.
  - The number format is `#,##0.00`, not the `#,##,##0.00` in section 5: Excel cannot show lakh grouping and negative
    amounts with one format.
- **Dashboard endpoint:**
  - `GET /dashboard` is allowed with `TENANT_READ`, which every role has. Each tile checks its own permission and module.
  - A tile that throws is reported in `errors` with its code; the rest still answers.
  - A crop tile shows profit only with `FINANCE_READ`. The crop report endpoint itself still shows profit to anyone with
    `CROP_READ` (Phase 5 behaviour, left as is).
- **Performance (H4):**
  - The first measurement at 50,000 journal lines was 1,073 ms.
  - Two changes brought it to 417 ms (best of 3): the 12 monthly figures now come from one grouped query that uses the
    same classification as the income statement (`ReportService.accrualByMonth`), and the dashboard ages dues once
    instead of three times.
  - Migration `009-reports.yaml` (`042-report-indexes`) adds two indexes: journal lines by cost object, and documents by
    type and status. There are no summary tables.
  - `DashboardPerformanceTest` guards the 1-second limit.
- **Hidden amounts.** The wages report hides money from readers without `PAYROLL_MANAGE` or `FINANCE_READ`. The
  running-costs report hides money from readers without `FINANCE_READ`. The older workforce labour report still shows
  labour cost to `WORKFORCE_READ`; this is listed as a gap.
- **Golden scenario.** Pay runs are limited to three months (Phase 6), so the test posts four monthly runs of ₹6,000
  instead of one of ₹24,000. Every figure in section 9 is unchanged.
- **Old drafts** are counted by record date (older than 7 days), not by creation time.
