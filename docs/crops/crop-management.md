# Crop management v2 design (Phase 5)

- Status: **Approved** (2026-10-20): F1–F12 as recommended. Implemented in Phase 5; see section 15 for implementation notes.
- Builds on: the approved [finance posting rules](../finance/posting-rules.md) (cost objects, D3, D7, P16) and the
  approved [stock movement design](../inventory/stock-movements.md) (stock use S3, farm produce S7, lots).
- Decisions marked **F1–F12** need an answer; each has a recommendation.

## 1. What this phase fixes

Today (Phase 1 code, unchanged since):

- A crop cycle is a row with a status nobody moves. Nothing can be edited or closed, and lists return untyped data
  without paging.
- An **activity** has a free-text input name, a quantity in free-text units and a typed-in `cost`. Using urea on a
  field does not take it out of stock, and the cost is not in the accounts (D7 made it a memo).
- A **harvest** has a quantity and a typed-in `sale_value`. The produce never enters stock, and the value is not
  revenue (it is a memo since Phase 3).
- There is no way to see what a crop cost per acre or per quintal, or whether it made money after shared farm costs.
- The mobile *Crops* tab is a placeholder.

The finance and stock work in Phases 3–4 already provide the pieces: every cycle is a cost object; bills and sales
can name a cycle; stock can be used on a cycle and produce can be put into stock. Phase 5 joins them up so that a
farmer records the field work once and the money, stock and crop reports all follow.

## 2. Principles

1. **Record field work once.** An activity that uses stock *is* the stock use: it creates the stock record (S3) with
   the cycle as the activity. A harvest *is* the farm-produce record (S7). There is no second place to type the same
   quantity.
2. **Money comes only from finance records.** Costs are bills, stock used and (new) overhead allocations; revenue is
   sales. Crop reports read the journal by cost object, so they always agree with the profit and loss statement.
   Nothing typed on a crop screen is counted as money unless it creates a finance record.
3. **Produce is traceable.** Each harvest is a lot. Selling from that lot links the sale to the harvest and its cycle.
4. **Mistakes are reversed, not edited,** for anything with stock or money. Notes-only activities and observations can
   be edited, with an audit trail.
5. **Estimates are labelled.** Anything not in the accounts (the value of unsold produce, old memo figures) is shown
   separately and marked as an estimate or a memo.

## 3. The cycle and its life (F1)

| Status | Meaning | How it changes |
|---|---|---|
| **Planned** | Field and crop chosen, dates planned | Created |
| **Growing** | Sowing or transplanting has happened | Automatically when a sowing/transplanting activity is recorded, or by hand |
| **Harvesting** | At least one harvest recorded | Automatically on the first harvest |
| **Closed** | Season over; figures final | By hand. Closing records the closing date |
| **Cancelled** | Never grown (crop failure before sowing, plan dropped) | By hand, only when it has no records |

- A closed cycle accepts no new activities or harvests. Money can still be assigned to it (a late bill for harvest
  labour), because the finance lock date, not the crop status, protects checked periods. A closed cycle can be
  reopened by an owner or farm admin.
- The cycle keeps its planned dates and gains **actual sowing date** and **closed on** date, so reports can compare
  plan and actual.
- **Area:** a cycle's cultivated area defaults to its field's area and cannot be more than it. Two cycles may share a
  field at the same time (intercropping, F11 asks how to treat it); the app warns when their areas add up to more
  than the field.

## 4. Activities (F2, F3)

An activity is dated field work on a cycle: land preparation, sowing, transplanting, fertilizer, pesticide,
irrigation, weeding, harvesting labour, other. It can carry three optional parts:

| Part | What it records | Effect |
|---|---|---|
| **Inputs from stock** | Items and quantities (urea 3 bags, seed 30 kg) | A *stock used* record (S3) for this cycle, created with the activity: stock goes down at average cost, the cost lands on the cycle |
| **Paid work** (**F3**) | Labour or machine hire paid for this work, with amount, cash/bank account or party | A bill with the cycle as the activity (cash, or owed to the party) |
| **Notes** | Workers, hours, method, observations | None |

- The activity shows links to the records it created. Reversing an activity reverses those records (refused if the
  period is locked).
- An activity with no stock and no paid work is just a note and can be edited or deleted (audited).
- **Old activities (D7).** Phase 1 activities keep their typed-in cost as a memo, shown as *recorded before accounts
  (not in profit)*. They are not posted, because the same money may already be in a bill.

## 5. Harvests and produce (F4, F5)

- Each crop names its **produce item** (paddy → stock item *Paddy*, farm produce, counted in kg). It is created the
  first time it is needed if the crop has none, using the crop's name and kg (both can be changed).
- Recording a harvest creates a **farm-produce record (S7)** into stock, at nil value (D3), for the cycle, as a new
  **lot**: the harvest batch (for example `SAMBA-2026-H1`), with the harvest date and optional grade and moisture.
  The quantity can be entered in any unit of the item (quintal, kg, bag).
- **Selling from a harvest (F5).** A sale line that names a harvest lot gets the lot's cycle as its activity unless the
  user picks another. That is how revenue reaches the cycle without asking the farmer.
- **Old harvests (F4).** Phase 1 harvests stay as harvest history (quantity and date count towards yield), but are
  **not** put into stock: their produce was almost certainly sold or eaten long ago, and adding it now would show
  stock that is not there. Their `sale_value` stays a memo, as since Phase 3.

## 6. Shared farm costs: overhead allocation (F6, P16)

Costs that belong to the whole farm (electricity for the motor, a watchman, repairs, depreciation) sit in *farm
overhead*. The owner can run an **allocation** for a period, which spreads overhead to the cycles that were growing
during it. This is the approved posting rule P16: for each overhead cost line, Dr the same expense on the cycle /
Cr the same expense on overhead, so totals do not change; only the split between activities does.

- **Basis (F6):** cultivated area × days growing in the period. Other bases (share of direct cost, fixed
  percentages) can come later.
- An allocation is a finance record (new type *Allocation*, `ALLOC/2026-27/0001`): numbered, dated, lock-date checked,
  reversible. Running a second allocation for the same period is refused until the first is reversed.
- Rounding: each cycle's share is rounded to the paisa; the last cycle takes the remainder so the total is exact.
- Interest, losses and owner drawings are never allocated (they are below operating profit or not costs).

## 7. Crop reports (F7, F8)

For a cycle, or all cycles of a season or farm:

| Figure | Source |
|---|---|
| Revenue | Sales assigned to the cycle (journal, by cost object) |
| Direct costs | Bills and stock used assigned to the cycle, by category |
| Allocated overhead | Allocation records (shown as its own line) |
| Contribution / profit | Revenue − direct costs − allocated overhead |
| Cost per acre | Total cost ÷ cultivated area in acres (areas in cents, hectares or sq ft are converted) |
| Yield | Harvested quantity, per acre, against the plan if one was entered |
| Cost per unit of produce | Total cost ÷ harvested quantity (₹/kg, ₹/quintal) |
| Unsold produce | Quantity of the cycle's harvest lots still in stock |
| **Estimate** (**F7**) | Unsold produce × a price the user enters, shown separately as *estimate, not in the accounts* |
| Memo (old data) | Phase 1 activity costs and harvest values, shown separately |

- Figures are actual (from posted records) for the chosen dates, like the finance reports; the accrual/cash toggle
  works the same way.
- **Comparison (F8):** the same crop across seasons, and plan vs actual dates and yield.
- Export as CSV (the stock-report format); XLSX/PDF in Phase 7.

## 8. Field notes: pests, diseases and weather (F9, F10)

- **Observations (F9):** dated notes on a cycle: pest, disease, nutrient deficiency, weed, water stress or other,
  with severity (low/medium/high), the part of the field and what was done. An observation can link to the activity
  that treated it. Photos wait for attachment storage (ADR 0009), which is not built yet.
- **Weather (F10):** dated rainfall (mm) and notes per farm, typed in by the farmer. No external weather service in
  this phase (no new infrastructure, as the spec requires).

## 9. Worked example (one farm, July)

Main farm: **Samba paddy** on North field, 2 acres; **Groundnut** on South field, 1 acre. Both grow all of July.
Urea is in stock at ₹6.2222/kg (the stock design's example).

| Step | Recorded as | Effect on Samba |
|---|---|---|
| 1 | Activity *Sowing*, with paid work: seed bought from Agro depot ₹4,000 (bill) | Seed ₹4,000 |
| 2 | Activity *Fertilizer*, inputs from stock: urea 300 kg | Stock used `ISSUE/…` ₹1,866.67; urea leaves stock |
| 3 | Activity *Weeding*, paid work: labour ₹6,000 cash | Labour ₹6,000 |
| 4 | Activity *Land preparation*, paid work: tractor hire ₹3,000 cash | Machine hire ₹3,000 |
| 5 | Electricity bill ₹1,200 for the farm (no activity) | — (farm overhead) |
| 6 | Overhead allocation for July (area × days: Samba 2 × 31, Groundnut 1 × 31) | Samba ₹800.00, Groundnut ₹400.00 |
| 7 | Harvest 40 quintal paddy, lot `SAMBA-2026-H1` | 4,000 kg in stock at nil value |
| 8 | Sale to Raman, 3,000 kg from lot `SAMBA-2026-H1` at ₹22/kg | Revenue ₹66,000 (activity Samba from the lot) |
| 9 | User enters ₹22/kg to estimate the unsold paddy | Estimate shown separately |

Samba report, July (the implementation must reproduce these as a golden test):

| Figure | Amount |
|---|---|
| Revenue | ₹66,000.00 |
| Direct costs | ₹14,866.67 (seed 4,000.00 + urea 1,866.67 + labour 6,000.00 + machine hire 3,000.00) |
| Allocated overhead | ₹800.00 |
| **Profit for the cycle** | **₹50,333.33** |
| Total cost | ₹15,666.67 |
| Cost per acre | ₹7,833.34 (15,666.67 ÷ 2, rounded to the paisa) |
| Yield | 40 quintal; 20 quintal per acre |
| Cost per kg | ₹3.9167 (₹391.67 per quintal) |
| Unsold produce | 1,000 kg; **estimate** ₹22,000.00, *not in the accounts* |

Checks: the farm's July profit and loss is unchanged by the allocation (₹1,200 of electricity before and after);
before the allocation Samba's profit is ₹51,133.33 and overhead shows ₹1,200; after it, overhead shows ₹0 and
Groundnut carries ₹400.00. Stock: paddy 1,000 kg in lot `SAMBA-2026-H1`; urea as in the stock design.

## 10. Screens

- **Web, Crops:** cycles list (filter by farm, season, status) → cycle page with timeline of activities, harvests and
  observations, the cycle report, and actions: record activity, record harvest, add observation, close/reopen.
  Crop and variety setup, seasons, weather notes, overhead allocation (in Finance), season comparison.
- **Mobile, Crops tab:** the placeholder is replaced: active cycles with stage and days since sowing, record an
  activity (with stock inputs) and a harvest, add an observation, and the cycle's profit. Online only in this phase;
  recording offline arrives with Phase 13.
- Tamil and English throughout.

## 11. Moving the Phase 1 crop data (forward-only, verified)

Your local database has 1 crop and 1 cycle, no activities or harvests (checked 2026-10-20).

| Phase 1 data | Becomes |
|---|---|
| Cycle `status` (`PLANNED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`) | Planned, Growing, Closed (closed on = expected harvest date, or the migration date), Cancelled |
| Activities | Kept as activities with no stock or money parts; `cost` stays a memo (D7); free-text input, quantity and unit kept as text |
| Harvests | Kept as harvest history for yield; not put into stock (F4); `sale_value` stays a memo |
| Free-text area unit | Already one of acre, hectare, cent, sq ft (checked by the API since Phase 1) |

The migration verifies counts and that no money, stock or memo value changed.

## 12. Data model (for implementation)

`sf_cultivation_cycles` gains actual sowing date, closed on, harvest lot sequence and a status check; `sf_crop_masters`
gains `produce_item_id`; `sf_farm_activities` gains `stock_document_id`, `bill_document_id`, workers, hours, edited
at/by, and a flag for Phase 1 memo rows; `sf_harvest_records` gains `lot_id`, `document_id`, grade, moisture;
new `sf_crop_observations` and `sf_weather_notes`; finance gains the *Allocation* record type with lines per cycle.
All tenant-owned with composite tenant foreign keys. Crop API responses become typed records with paging.

## 13. Decisions (approved as recommended, 2026-10-20)

| # | Question | Recommendation |
|---|---|---|
| **F1** | Cycle stages Planned → Growing → Harvesting → Closed (and Cancelled), moved automatically by sowing and harvest; closed cycles take no new field records but can still receive money? | **Yes** |
| **F2** | An activity's stock inputs create the stock use record, so field work is recorded once? | **Yes** |
| **F3** | Let an activity also record paid labour or machine hire, creating a bill for the cycle? | **Yes** (optional part of the form) |
| **F4** | Keep Phase 1 harvests as history only, without putting them into stock? | **Yes**; their produce is long gone |
| **F5** | A sale line from a harvest lot is assigned to that lot's cycle by default? | **Yes** |
| **F6** | Overhead allocation by area × days growing, run by hand per period, as a reversible finance record? | **Yes**; other bases later |
| **F7** | Show unsold produce at a user-entered price as a labelled estimate, outside the accounts (D3 unchanged)? | **Yes** |
| **F8** | Season-to-season comparison and plan vs actual in this phase? | **Yes**, on screen and CSV |
| **F9** | Pest and disease observations as text with severity now, photos when attachment storage is built? | **Yes** |
| **F10** | Weather as farmer-entered rainfall and notes, no external weather service yet? | **Yes** |
| **F11** | Intercropping (two cycles on one field at once): allow with a warning when areas exceed the field, and allocate by each cycle's own area? | **Yes** |
| **F12** | Mobile Crops tab: real cycles, activities, harvests and observations online now; offline recording in Phase 13? | **Yes** |

## 14. What Phase 5 delivers once this is approved

Cycle stages, editing and closing; activities with stock inputs and paid work; harvests as produce lots; sales linked
to harvests; overhead allocation (P16); cycle reports with cost per acre, yield, cost per unit, unsold produce and
labelled estimates; season comparison and CSV; observations and weather notes; typed, paged crop API; web and mobile
crop screens in Tamil and English; the verified Phase 1 crop migration. Tests: section 9 as a golden scenario,
allocation totals and rounding, stage changes, reversal of activities, closed-cycle rules, idempotent retries,
cross-tenant and permission tests for every new endpoint.

## 15. Implementation notes (Phase 5)

Choices made while building, within the approved decisions. None changes a figure in section 9, which the golden test
(`CropGoldenScenarioTest`) reproduces exactly.

- **Where things live.** The crop module keeps crops, cycles, stages, field records, observations and weather, and
  never depends on finance or stock. A new **operations** module records field work that moves stock or money: it calls
  the stock module for inputs and harvests and finance for paid work, all in one transaction. Nothing depends on
  operations (`ModuleBoundaryTest`). Overhead allocation lives in finance and reads each cycle's area and dates from its
  cost object, which the crop module keeps up to date.
- **Permissions.** Recording field work needs `CROP_ACTIVITY_WRITE`; using stock inputs also needs `INVENTORY_WRITE`,
  paid work `FINANCE_WRITE`, and reversing `FINANCE_REVERSE`. Harvests need only `CROP_ACTIVITY_WRITE`: farm produce
  enters stock at nil value, so workers can record it. With today's roles, field supervisors and workers can record
  notes-only work and harvests but not stock inputs (see the open question in PROGRESS).
- **Produce item.** The first harvest of a crop uses a farm-produce stock item with the crop's name, or creates one
  (counted in kg, tracking lots, sale category *Crop sales*). Each harvest is a lot named after the cycle
  (`SAMBA-2026-H1`), unique per item. If a crop's chosen item does not track lots, harvests still enter stock but
  without a lot, so sales from them are not linked to the cycle automatically.
- **Stages.** A harvest moves a Planned or Growing cycle to Harvesting even if no sowing was recorded; reversing the
  last harvest moves it back to Growing. The cycle's activity dates for allocation are its actual sowing date (or
  planned, until sown) to its closing date.
- **Allocation.** Overhead lines that name a farm are spread only over that farm's cycles; the rest over all cycles.
  Cost of goods sold, interest and losses are never allocated. Periods are at most one year; a second allocation that
  overlaps a posted one is refused until that one is reversed.
- **Reports.** The cycle report's default period runs from six months before planned sowing to today (or the closing
  or expected harvest date if later), because inputs are often bought before sowing. In the cash view, allocated
  overhead is not shown (allocations move no money). Yield and cost per unit use the crop's main produce item in its base
  unit (kg); the screens convert units for display.
- **Phase 1 data.** Cycle statuses map to stages (In progress → Growing, Completed → Closed on the expected harvest
  date, anything unknown → Planned). Cycles without a cost object get one. Activities and harvests are kept as history
  with their memo values; the migration verifies that no journal line or stock movement was added.
- **API.** Crop lists are typed and paged (`/cultivation-cycles`, `/activities`, `/harvests` return pages). A crop of
  another farm business now answers 404 instead of 400, like every other cross-tenant reference.
