# Workforce and assets design (Phase 6)

- Status: **Approved** (2026-10-20, G1–G15 as recommended) and implemented on `feature/phase-6-workforce`; see section 14.
- Builds on: the approved [finance posting rules](../finance/posting-rules.md) (accounts 1150 Advances to workers,
  2100 Wages payable, 1300 Farm assets, 1310 Accumulated depreciation, 4900 Gain on sale, 5100 Labour, 5800
  Depreciation, 5980 Losses; D8), the [stock design](../inventory/stock-movements.md) and the
  [crop design](../crops/crop-management.md) (cost objects, overhead allocation P16).
- Decisions marked **G1–G15** need an answer; each has a recommendation.

## 1. What this phase fixes

- Labour is only money today: a bill for *Labour*, or the *paid work* part of a field activity. There is no list of
  workers, no attendance, no record of who is owed wages or who has taken an advance, and no way to see how many days
  went into the Samba crop.
- A tractor or pump bought on a bill lands in *Farm assets* (1300), but nothing remembers it as a machine: no useful
  life, no automatic depreciation (it is typed by hand each month), no sale or scrapping, no fuel, repair or service
  history.
- The *Workforce* and *Assets* modules exist as switches but are marked unavailable.

## 2. Principles

1. **One ledger.** Workers are parties, so what a worker is owed (2100) or owes back (1150) uses the same party ledger,
   statements and dues as traders. Assets post to 1300/1310 through finance records, so the register always agrees
   with the balance sheet (a reconciliation, like stock's).
2. **Labour cost follows the work.** Wages are a cost of the crop the worker worked on that day (from attendance),
   otherwise farm overhead, which can then be allocated (P16).
3. **Record once.** Attendance drives the pay run; the pay run is the wage record; paying it settles the balance and
   recovers advances in the same step. Registering an asset happens on the bill that bought it.
4. **Mistakes are reversed, not edited,** for anything posted. Attendance can be corrected until the pay run that used
   it is posted; after that, reverse the pay run.
5. **No compliance claims.** The app does not calculate PF, ESI, TDS or minimum wages in this phase (G6), and says so.

## 3. Workers (G1, G2)

| Field | Notes |
|---|---|
| Name, phone, village | The worker is also a **party** (flag *worker*), so balances and statements work as for traders (G1) |
| Pay type (G2) | **Daily** (rate per day; half day = half rate), **Monthly** (fixed salary per month), **Piece rate** (rate per unit of work, e.g. ₹ per kg picked, per tree, per bag) |
| Rate | Current rate; rate changes are dated so past pay runs keep their rate |
| Joined / left | A worker who has left cannot get new attendance; their balance must be settled to close them |
| Usual work, notes | Free text |

## 4. Attendance and work (G3, G5)

- **One entry per worker per day**: present, half day, absent or leave (paid or unpaid), with optional hours and the
  **crop cycle or activity** worked on. No cycle means farm overhead.
- **Piece work** entries: worker, date, quantity, unit, rate (defaults to the worker's), cycle.
- **Extra pay** (overtime, bonus, food allowance) is an amount added in the pay run, with a reason; the app does not
  calculate overtime by itself (G5).
- Attendance is entered for a group in one screen (a muster roll for the day), on web and phone (G15).

## 5. Wages, advances and payment (G4, G7)

| # | Record | Journal |
|---|---|---|
| W1 | **Advance** to a worker | Dr 1150 Advances (worker) / Cr 1000/1010 |
| W2 | **Pay run** for a period (week, fortnight, month; the farm chooses) | For each worker and cycle: Dr 5100 Labour (cycle, or overhead) / Cr 2100 Wages payable (worker) |
| W3 | **Wage payment** to one or more workers | Dr 2100 (worker) / Cr 1150 Advances (worker), for the advance recovered / Cr 1000/1010 for the cash paid |
| W4 | **Advance repaid in cash** (a worker leaving) | Dr 1000/1010 / Cr 1150 (worker) |
| W5 | **Reversal** of any of these | Mirror (P18) |

- **Pay run amounts:** daily workers: days present × day rate (half day = 0.5); monthly workers: the month's salary, less
  unpaid leave at salary ÷ days in the month per day; piece work: quantity × rate; plus extra pay. Each amount is rounded
  to the paisa. A worker's days can be split across cycles; the cost is split by days on each.
- A pay run shows each worker's gross, the advance outstanding, and the suggested net to pay. Payment can be for all
  workers at once (one record per worker, so each can be reversed alone) or one at a time.
- **One pay run per worker per period:** attendance used by a posted pay run is locked until the run is reversed.
- **Casual labour paid on the spot** (not in the register) keeps using the *paid work* part of a field activity, or a
  bill. A worker in the register is paid through pay runs only; the screens warn if the same day is entered both ways (G7).

## 6. Assets (G8–G13)

### Register

| Field | Notes |
|---|---|
| Name, kind | Equipment, vehicle, irrigation (pump, drip), structure (shed, well, borewell), breeding or milch animal, other |
| Farm, serial or registration number | |
| Bought on, cost, from | Cost comes from the bill line or opening balance that bought it (G8) |
| Useful life (months), salvage value | Used for depreciation; editable until the first depreciation is posted |
| In use from | Defaults to the purchase date |
| Status | In use, sold, scrapped |
| Depreciation already taken | For assets brought in from opening balances or old records; counted in book value |

- **Registering (G8):** a bill line in an asset category (*Equipment*, *Breeding animals*, *Buildings*) asks for the
  asset details and creates the register entry with the bill. Opening-balance asset lines do the same. Existing 1300
  lines from earlier phases can be registered from a list of unregistered asset costs.
- **Reconciliation:** register cost − accumulated depreciation must equal the 1300 − 1310 balance; any difference
  (manual depreciation records from Phase 3, unregistered lines) is listed so it can be explained.

### Depreciation (G9, G10)

- **Straight line, monthly:** (cost − salvage) ÷ useful life in months, from the month after the asset comes into use,
  for every month the asset is still in use on the month's last day, until the book value reaches the salvage value.
  The last month takes the remainder exactly.
- A **monthly depreciation run** (owner or accountant) posts one depreciation record for the month:
  Dr 5800 Depreciation (farm overhead, or the asset's fixed activity) / Cr 1310, with one line per asset. One run per
  month; missed months can be run in order; a run can be reversed. Written-down value (WDV) is not offered.
- **Breeding and milch animals (G10, D8):** registered, never depreciated until the livestock phase decides.
- Manual depreciation records (Phase 3) remain possible for assets not in the register.

### Sale or scrapping (G11)

| Record | Journal |
|---|---|
| **Sold** for proceeds *P* (cash, bank, or owed by a buyer) | Dr 1000/1010 or 1100 *P*; Dr 1310 accumulated depreciation; Cr 1300 cost; difference to Cr 4900 Gain or Dr 5980 Loss |
| **Scrapped** | Same with *P* = 0 (scrap sold for a little is a sale) |

Depreciation stops: the month of disposal is not depreciated if the asset is gone by its last day.

### Running costs, use and maintenance (G12, G13)

- **Running costs:** diesel used from stock, repair bills and spare parts can name the asset. They still go to the
  activity chosen (a crop, or overhead); the asset tag gives *cost per asset* reports (fuel per hour, repairs per year).
  Spreading a machine's cost to crops by hours used is not in this phase (G12).
- **Use log:** hours, kilometres or acres per day, optionally per cycle.
- **Maintenance plan:** every N days or N hours (oil change every 250 hours, service every 6 months), with *due* and
  *overdue* alerts; a maintenance log records what was done, linking the repair bill when there is one.

## 7. Worked example (July 2026)

Workers: **Murugan** (daily, ₹500), **Selvi** (daily, ₹400), **Ravi** (monthly, ₹12,000). Assets: **tractor** bought
1 April 2026 for ₹6,00,000 (life 120 months, salvage ₹60,000; May and June already depreciated); **power tiller**
bought 1 July 2024 for ₹1,20,000 (life 60 months, no salvage; depreciated through June 2026); a **milch cow** bought for
₹60,000 (D8).

| Step | Record | Journal |
|---|---|---|
| 1 Jul | Advance to Murugan ₹2,000 cash (W1) | Dr 1150 (Murugan) 2,000 / Cr 1000 2,000 |
| July | Attendance: Murugan 20 days (12 on Samba, 8 general); Selvi 15 full days + 2 half days, all on Samba; Ravi present all month, general work | — |
| 25 Jul | Power tiller sold for ₹80,000 cash (G11). 23 months' depreciation (Aug 2024 – Jun 2026) = ₹46,000; book value ₹74,000 | Dr 1000 80,000; Dr 1310 46,000 / Cr 1300 1,20,000; Cr 4900 6,000 |
| 31 Jul | Pay run 1–31 July (W2): Murugan 20 × 500 = 10,000 (Samba 6,000, overhead 4,000); Selvi 16 × 400 = 6,400 (Samba); Ravi 12,000 (overhead) | Dr 5100 Samba 12,400; Dr 5100 overhead 16,000 / Cr 2100 Murugan 10,000, Selvi 6,400, Ravi 12,000 |
| 31 Jul | Wages paid (W3): Murugan 10,000 less 2,000 advance = 8,000 cash; Selvi 6,400; Ravi 12,000 | Dr 2100 28,400 / Cr 1150 (Murugan) 2,000; Cr 1000 26,400 |
| 31 Jul | Depreciation run for July (G9): tractor (6,00,000 − 60,000) ÷ 120 = 4,500; tiller sold, cow not depreciated | Dr 5800 (overhead) 4,500 / Cr 1310 4,500 |

Checks the implementation must reproduce exactly (as a golden test):

- **Balances 31 July:** wages payable ₹0; advances ₹0; Murugan's statement shows advance 2,000, wages 10,000, paid 8,000.
- **Labour cost:** ₹28,400 for July; **Samba ₹12,400** (28 worker-days: Murugan 12, Selvi 16), overhead ₹16,000 (before any allocation).
- **Profit and loss, July:** labour ₹28,400 and depreciation ₹4,500 in operating costs; **gain on sale ₹6,000** in
  other income.
- **Cash flow, July:** operating −₹28,400 (advance 2,000 + wages 26,400); investing +₹80,000.
- **Register 31 July:** tractor cost 6,00,000, accumulated 13,500, book value **₹5,86,500**; tiller sold (gain 6,000);
  cow 60,000, no depreciation. Register book values equal 1300 − 1310 in the ledger.

## 8. Reports

- **Workers:** muster roll for a month (days, half days, leave), wages and payments per worker, balances (owed or
  advance), labour days and cost per crop cycle.
- **Assets:** register with cost, accumulated depreciation and book value at a date; depreciation schedule (past and
  future months); cost per asset (fuel, repairs, depreciation) for a period; maintenance due; reconciliation with the
  ledger.
- CSV for these (XLSX/PDF with Phase 7).

## 9. Screens

- **Web, Workforce:** workers; daily muster roll (mark everyone present, change the few who are not, choose the
  cycle); piece work; advances; pay runs (preview, post, pay); worker statement.
- **Web, Assets:** register; asset page (details, depreciation schedule, use log, maintenance plan and log, costs);
  monthly depreciation run; sale or scrapping.
- **Mobile (G15):** mark today's attendance and see what each worker is owed; online now, offline in Phase 13.
- Tamil and English.

## 10. Modules and permissions (G14)

- *Workforce* and *Assets* become available modules, switched on for existing and new farm businesses (owners can
  switch them off).
- New permissions: `WORKFORCE_READ`, `WORKFORCE_WRITE` (workers, attendance), `PAYROLL_MANAGE` (pay runs, payments,
  advances), `ASSETS_READ`, `ASSETS_WRITE` (register, use, maintenance), with depreciation runs and disposals needing
  `FINANCE_MANAGE`. Proposed role grants: owner and farm admin all; farm manager workforce and assets read/write and
  payroll; accountant read, payroll and finance; field supervisor workforce read/write (attendance) and assets read/write
  (use log); worker none; viewer read.

## 11. Data model (for implementation)

`sf_workers` (party, pay type, joined, left) · `sf_worker_rates` (dated) · `sf_attendance` (worker, date, status, hours,
cost object, activity; unique per worker and day; locked by pay run) · `sf_piece_work` · finance record types *Advance*,
*Pay run*, *Wage payment*, *Asset disposal* (and depreciation runs as *Depreciation* records with asset lines) ·
`sf_assets` (kind, farm, source document line, cost, salvage, life, in use from, status, depreciation already taken) ·
`sf_asset_usage` · `sf_maintenance_plans` · `sf_maintenance_logs` · an *asset* column on bill and stock-use lines. All
tenant-owned with composite tenant foreign keys. No Phase 1 data exists for these; existing 1300 lines are offered for
registration rather than migrated automatically.

## 12. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **G1** | Make each worker a party, so wages owed (2100) and advances (1150) use the party ledger and statements? | **Yes** |
| **G2** | Pay types: daily (half day = half), monthly (less unpaid leave at salary ÷ days in month), piece rate? | **Yes** |
| **G3** | Attendance records the cycle worked on, and labour cost follows it (else overhead)? | **Yes** |
| **G4** | Wages through pay runs (W2), paid separately with advances recovered at payment (W3); one pay run per worker per period? | **Yes** |
| **G5** | Overtime and bonuses as amounts added to a pay run, not calculated automatically? | **Yes** |
| **G6** | No PF, ESI, TDS or minimum-wage calculations in this phase (documented as a compliance gap)? | **Yes**; revisit before larger farms use payroll |
| **G7** | Casual labour paid on the spot stays as *paid work* or bills; registered workers only through pay runs? | **Yes**, with a warning if a day is entered both ways |
| **G8** | Register assets on the bill or opening balance that bought them; offer existing 1300 lines for registration? | **Yes** |
| **G9** | Straight-line monthly depreciation from the month after use starts, posted by a monthly run (one per month, reversible); no WDV? | **Yes** |
| **G10** | Breeding and milch animals registered but not depreciated (D8) until the livestock phase? | **Yes** |
| **G11** | Disposal removes cost and accumulated depreciation; gain to 4900, loss to 5980; disposal month not depreciated? | **Yes** |
| **G12** | Tag running costs to assets for reports, but leave machine-cost allocation to crops by hours for later? | **Yes** |
| **G13** | Maintenance plans by days or hours, with due and overdue alerts, and a use log? | **Yes** |
| **G14** | Turn on *Workforce* and *Assets* modules for all farm businesses, with the permissions and role grants in section 10? | **Yes** |
| **G15** | Phone: mark attendance and see worker balances (online now, offline in Phase 13)? | **Yes** |

## 13. What Phase 6 delivers once this is approved

Workers as parties with dated rates; daily muster roll and piece work by cycle; advances; pay runs with labour cost by
cycle; wage payments recovering advances; worker statements and balances; asset register from bills and opening
balances; monthly straight-line depreciation runs; sale and scrapping with gain or loss; use log, maintenance plans and
alerts; cost per asset; reconciliations of wages, advances and assets with the ledger; CSV reports; web screens and phone
attendance in Tamil and English. Tests: section 7 as a golden scenario, pay-run arithmetic and rounding, attendance
locking, advance recovery, depreciation schedules (first and last month, salvage, disposal month, catch-up and
reversal), disposal gain and loss, the ledger reconciliations, idempotent retries, cross-tenant and permission tests for
every new endpoint.

## 14. Implementation notes (2026-10-20)

- **Modules.** `workforce` and `assets` are new backend packages. Only their own code uses them, and `workforce` does not
  depend on `assets` (`ModuleBoundaryTest`). Finance stays independent of both. They post through
  `DocumentService.recordModule`, which is allowed only for module record types (`DocType.isModuleRecord`).
- **Reversal guards.** A new finance SPI, `ReversalGuard`, lets modules refuse a reversal that would break their records:
  - `WAGES_ALREADY_PAID`: a pay run with payments after it;
  - `ADVANCE_ALREADY_RECOVERED`: an advance already recovered;
  - `DEPRECIATION_LATER_MONTHS`: a depreciation run with later months posted;
  - `ASSET_DISPOSED`: depreciation of a sold asset;
  - `ASSET_REGISTERED`: a bill whose cost is in the register.

  The guards are separate beans, so there is no constructor cycle with `DocumentService`.
- **Locking.** Attendance and piece work are locked once a pay run covering them is posted. This is derived from
  `sf_pay_run_workers` and the document's status, so reversing the pay run unlocks them without a second flag.
- **Pay run serialisation.** Posting locks the tenant row, so two pay runs posted at the same moment cannot overlap.
- **G7 warning.** The muster flags a worker who is also on a posted labour bill (account 5100) addressed to their party for
  the same day (`paidByBill`). Bills paid in cash with no party cannot be matched.
- **Asset tags.** Expense bill lines and stock issues take an optional asset (`asset_id` on documents and lines). Asset
  running costs and cost per hour use these tags (G12).
- **Opening balances with depreciation.** An asset brought in through an opening balance has its earlier depreciation
  entered as a manual depreciation record. It is then registered with `depreciationBefore` set to the same amount. The
  register reconciliation shows manual depreciation as its own line, so the unexplained difference stays zero.
- **Not built (by decision).** Not included in this phase:
  - PF, ESI, TDS and minimum-wage rules (G6), a compliance gap before larger farms use payroll;
  - WDV depreciation (G9);
  - machine-hour cost allocation to crops (G12);
  - depreciation of breeding animals, which waits for the livestock phase (G10);
  - offline attendance on the phone, which waits for Phase 13.
