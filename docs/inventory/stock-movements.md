# Stock movement design (Phase 4)

- Status: **Approved** (2026-10-20): E1–E12 as recommended. Implemented in Phase 4; see section 14 for implementation notes.
- Builds on: [ADR 0005 — Inventory movements and valuation](../adr/0005-inventory-valuation.md) and the approved
  [finance posting rules](../finance/posting-rules.md) (Stock is ledger account 1200; D3 values farm-produced stock at nil).
- Decisions marked **E1–E12** need an answer; each has a recommendation.

## 1. What this phase fixes

Today (Phase 1 code, kept alive in Phase 3):

- An item's quantity is a number that is overwritten: creating an item sets it, a purchase adds to it, nothing ever
  takes stock out, and there is no history of why it changed.
- Average cost is rounded to 2 decimals on every purchase, so value drifts.
- Units are free text ("kg", "Kg", "bag"), so quantities cannot be added up or converted.
- Purchases have one line; sales cannot reference stock at all.

Phase 3 already posts each purchase's money to Stock (1200). Phase 4 makes the quantities just as trustworthy.

## 2. Principles

1. **Stock only changes through a movement.** A movement records: item, location, optional lot, signed quantity in
   the item's base unit, value, type, date, and the record it came from.
2. **Movements are never edited or deleted.** A mistake is reversed with opposite movements, like finance records.
3. **The on-hand balance is updated in the same transaction** as the movement, under a row lock. A database check and
   a test confirm that on-hand always equals the sum of movements.
4. **Stock value in the ledger equals stock value in the store.** The balance of account 1200 always equals the
   total value of all on-hand stock. An automated check enforces this.
5. **Every stock record is a finance record.** It uses the same numbering, lock date, reversal, idempotency keys,
   permissions and audit trail (**E7**). Records with a money effect post their journal entry in the same transaction.

## 3. Units (E1)

- A system **unit catalog**, grouped by dimension: mass (g, kg, quintal = 100 kg, tonne = 1,000 kg), volume (ml,
  litre), count (piece/nos, dozen = 12), length (metre), area (cent, acre, hectare, sq ft; used by land, not stock).
- Each item has **one base unit** in which stock is kept, for example urea in kg and eggs in pieces.
- An item can also have **pack units** with a fixed factor, for example "Urea bag = 45 kg" or "Egg tray = 30
  pieces". Purchases and sales can be entered in any of the item's units; the movement is always stored in the base
  unit.
- Conversion results are kept to 4 decimals. Money is always rounded to the paisa on the line, as in finance.

## 4. Items, locations and lots

| Concept | Meaning |
|---|---|
| **Item** | Something counted in stock: a purchased input (seed, fertilizer, feed, medicine, diesel), a farm product (paddy, milk, eggs, compost), or goods bought for resale. It has a base unit, pack units, category, reorder level, whether lots are tracked, and default finance categories for buying and selling it. |
| **Location** | Where stock is kept (main store, a field shed, a cold room). Every tenant gets a default "Main store". Locations can belong to a farm. |
| **Lot** (optional, per item) | A batch with a lot number, an optional expiry date, and the received or produced date. Used for seed, chemicals, medicine and feed, and later to trace harvest batches into processing. |

- **Valuation is per item across all locations** (**E2**): one moving-average cost for urea, wherever it is stored.
  Moving stock between locations does not change its value.
- **Expiry** (**E6**): expired or soon-to-expire lots are flagged (within 30 days by default), and issues suggest
  first-expiring lots first. Using an expired lot gives a warning but is not blocked.

## 5. Valuation: moving weighted average (ADR 0005)

For each item the system keeps **total quantity and total value**; the unit cost is value ÷ quantity.

- **Receipt** at cost *c* for quantity *q*: quantity += *q*, value += *q* × *c* (rounded to the paisa).
- **Issue** of quantity *q*: value out = *q* × (value ÷ quantity), rounded to the paisa. If *q* is everything left,
  value out is exactly the remaining value, so the store never ends with a few paise and no stock.
- **Farm-produced stock** (harvest, milk, eggs, compost) comes in at **nil value** (D3). Selling or using it
  therefore costs nothing in stock terms; its growing costs were already expenses of the crop or animal activity.
- **Order** (**E3**): movements are valued in the order they are posted, not by document date. A back-dated
  purchase does not re-cost earlier issues. The lock date prevents back-dating into checked periods.
- **No negative stock** (**E4**): an issue larger than what is on hand at that location (and lot) is refused.

## 6. Stock records and their effects

`Dr` = debit, `Cr` = credit. "At average" means valued as in section 5.

| # | Record | Stock movement | Journal (same transaction) |
|---|---|---|---|
| S1 | **Purchase bill line for an item** (a bill line can now be an item) | + quantity at the line's unit cost | Dr 1200 Stock / Cr 2000 Payables or cash (Phase 3 rule P1/P2 with account 1200) |
| S2 | **Sale line for an item** | − quantity at average | Sale as P4/P5 **plus** Dr 5700 Cost of goods sold / Cr 1200 at the issued value (₹0 for farm produce) |
| S3 | **Use on an activity** (inputs to a crop cycle, feed to a herd) | − quantity at average | Dr 5000 Farm inputs (that activity) / Cr 1200 |
| S4 | **Transfer between locations** | − at one location, + at the other, same value | None (same account) |
| S5 | **Wastage / spoilage** (reason required) | − quantity at average | Dr 5980 Losses and write-offs / Cr 1200 (**E5**) |
| S6 | **Stock count adjustment** | ± the difference between counted and recorded quantity, at average | Shortage: Dr 5980 / Cr 1200; surplus: Dr 1200 / Cr 5980 (**E5**) |
| S7 | **Farm produce into stock** (harvest, milk, eggs, compost; Phase 5 automates harvests) | + quantity at nil value | None |
| S8 | **Return to supplier** | − quantity at average | Dr 2000 Payables (the agreed credit) / Cr 1200 (stock value); any difference goes to 5000 Farm inputs (**E10**) |
| S9 | **Return from customer** | + quantity at the cost it left with | Dr 4000/4100 (revenue reversed) / Cr 1100 or cash; Dr 1200 / Cr 5700 |
| S10 | **Opening stock** | + quantity at the given unit cost | Dr 1200 / Cr 3900 Opening balances (part of the opening-balance record) |
| S11 | **Reversal of any stock record** | Opposite movements, at the original values | Mirror of the original journal (P18). Refused if it would make stock negative. |

Two consequences worth stating plainly:

- **Buying stock is not an expense; using or selling it is.** A farmer who buys a year's urea in June sees no
  ₹-cost in June until the urea is used or wasted. This is ADR 0005 and posting rule section 5.
- **Item lines are optional.** A sale of produce that was never put into stock is still recorded exactly as in
  Phase 3 (category line, no movement) (**E11**). Item lines are for farmers who want stock control.

## 7. Worked example (one farm, July)

Urea's base unit is kg, with a pack unit "bag = 45 kg". Paddy is kg, farm-produced.

| Step | Record | Stock after (qty · value) | Journal |
|---|---|---|---|
| 1 | Buy 10 bags urea from Agro depot on credit @ ₹270/bag | Urea 450 kg · ₹2,700.00 (₹6.0000/kg) | Dr 1200 2,700.00 / Cr 2000 (Agro depot) 2,700.00 |
| 2 | Buy 5 more bags @ ₹300/bag, paid cash | Urea 675 kg · ₹4,200.00 (₹6.2222/kg) | Dr 1200 1,500.00 / Cr 1000 1,500.00 |
| 3 | Use 300 kg on the Samba cycle | Urea 375 kg · ₹2,333.33 | Dr 5000 (Samba) 1,866.67 / Cr 1200 1,866.67 |
| 4 | Wastage: 25 kg wet and spoiled | Urea 350 kg · ₹2,177.77 | Dr 5980 155.56 / Cr 1200 155.56 |
| 5 | Count finds 348 kg | Urea 348 kg · ₹2,165.33 | Dr 5980 12.44 / Cr 1200 12.44 |
| 6 | Harvest 40 quintal paddy into stock (Samba) | Paddy 4,000 kg · ₹0.00 | — |
| 7 | Sell 3,000 kg paddy to Raman @ ₹22/kg on credit | Paddy 1,000 kg · ₹0.00 | Dr 1100 (Raman) 66,000 / Cr 4000 (Samba) 66,000; Dr 5700 0 / Cr 1200 0 (no line written) |

Checks the implementation must reproduce exactly (as a golden test):

- **Ledger equals store:** account 1200 = 2,700 + 1,500 − 1,866.67 − 155.56 − 12.44 = **₹2,165.33** = urea ₹2,165.33 +
  paddy ₹0.00.
- **Profit (July):** revenue ₹66,000; operating costs ₹1,866.67 (urea used); losses ₹168.00; **net profit ₹63,965.33**.
  The ₹4,200 of urea bought is not an expense; only the part used (₹1,866.67) or lost (₹168.00) is.
- **Samba cycle:** revenue ₹66,000 − urea ₹1,866.67 = **₹64,133.33 contribution**.
- **Cash flow:** −₹1,500 operating (the cash bag purchase). The credit purchase and credit sale move no cash.
- **Dues:** we owe Agro depot ₹2,700; Raman owes ₹66,000.
- **Stock report:** urea 348 kg (7.73 bags), ₹2,165.33, unit cost ₹6.2222/kg; paddy 1,000 kg, ₹0.00 (farm produce).

## 8. Low stock and expiry

- Each item has a reorder level in its base unit. The stock screen and the finance overview list items at or below it.
- Lots expiring within 30 days (tenant setting later) are listed, with expired lots highlighted.
- Notifications (SMS/push) are part of Phase 14; Phase 4 shows alerts in the app only.

## 9. Reports and exports

- **Stock on hand:** by item and location (and lot), with quantity, base and pack units, unit cost and value.
- **Stock value at a date:** recomputed from movements, so it can be reported for any past date.
- **Movement history:** per item, with each record, date, quantity in/out, value and running balance.
- **Ledger reconciliation:** account 1200 vs total stock value, which must be zero difference.
- **Exports:** CSV for these reports in Phase 4 (same permissions and tenant checks as the API). XLSX/PDF arrive with
  the Phase 7 reporting work.

## 10. Moving the Phase 1 stock data (forward-only, verified)

Your local database has no stock data (checked 2026-10-20); these rules protect other installations.

| Phase 1 data | Becomes |
|---|---|
| `sf_inventory_items` | Stock items. The free-text unit is matched to the unit catalog (case-insensitive: "kg", "Kg", "kgs" → kg; "litre"/"ltr"/"l" → litre; "nos"/"pcs" → piece). Unmatched units become the item's own custom base unit (count dimension) and are flagged for review. |
| Each legacy purchase | A purchase receipt movement linked to the bill created in Phase 3 (quantity at its unit cost) |
| Quantity not explained by purchases (the starting quantity typed when the item was created) | An opening-stock movement. Its value = final value − purchases' value (the original cost was overwritten in Phase 1), and an opening-balance journal Dr 1200 / Cr 3900 so the ledger matches the store |
| `sf_suppliers` | Already parties since Phase 3 |

The migration verifies that each item's on-hand equals its movements and that account 1200 equals total stock value.
Because Phase 1 rounded the average to 2 decimals, the reconstructed opening value can differ from the true original
by a few paise; any difference is reported in the migration output and held in the opening balance, never hidden.
Afterwards `sf_inventory_items` and `sf_purchases` become read-only, and the Phase 1 `/inventory/items`, `/suppliers` and
`/purchases` endpoints and the "Stock & purchases" page are replaced (**E12**).

## 11. Data model (for implementation)

`sf_units` (system catalog: code, dimension, factor to the dimension's base) · `sf_stock_items` (tenant, name, kind,
base unit, category, reorder level, track lots, default purchase/sale categories, active) · `sf_item_units` (pack
units with factor) · `sf_stock_locations` · `sf_stock_lots` (item, lot no., expiry, produced/received date) ·
`sf_stock_movements` (append-only: tenant, document, document line, item, location, lot, signed base quantity, signed
value, unit cost, type, date, posting sequence, cost object) · `sf_stock_balances` (tenant, item, location, lot →
quantity, value; quantity ≥ 0 check) · `sf_stock_item_totals` (item-wide quantity and value for averaging).
Finance document types are extended (stock issue, transfer, wastage, count adjustment, produce receipt, supplier
return, customer return), and bill/sale lines gain item, unit and location columns. All tables carry `tenant_id` with
composite tenant foreign keys.

## 12. Decisions (approved as recommended, 2026-10-20)

| # | Question | Recommendation |
|---|---|---|
| **E1** | Keep each item's stock in one base unit, with fixed pack units (bag = 45 kg)? | **Yes** |
| **E2** | One average cost per item across all locations? | **Yes**; per-location costing adds little for a farm |
| **E3** | Value movements in posting order (no re-costing when something is back-dated)? | **Yes**; the lock date protects checked periods |
| **E4** | Refuse issues that would make stock negative? | **Yes** (a tenant setting to allow it can come later) |
| **E5** | Wastage and count differences go to *Losses and write-offs* (5980, below operating profit), as the approved posting rules already say for stock loss? | **Yes** |
| **E6** | Expired lots: warn but allow? | **Yes**, with first-expiring-first suggestions |
| **E7** | Stock records are finance records (shared numbering, lock date, reversal, audit, idempotency)? | **Yes** |
| **E8** | Purchase orders: build now, or later? | **Later.** Bills with item lines cover how most farms buy; purchase orders can follow if traders need them |
| **E9** | CSV exports now, XLSX/PDF in Phase 7? | **Yes** |
| **E10** | On a return to supplier, put any difference between the credit received and the stock value into *Farm inputs* (5000)? | **Yes** |
| **E11** | Keep item lines optional, so produce sold without stock tracking is recorded as in Phase 3? | **Yes** |
| **E12** | Replace the Phase 1 stock endpoints and page with the new ones (after migrating their data)? | **Yes**; they have no outside users |

## 13. What Phase 4 delivers once this is approved

Unit catalog and pack units; items, locations and lots; append-only movements with on-hand balances; item lines on
bills and sales; use on activities, transfers, wastage, stock counts, produce receipts, returns both ways, opening
stock, reversal; moving-average valuation; low-stock and expiry alerts; stock on hand, value at a date, movement
history, ledger reconciliation and CSV exports; web stock screens and mobile stock view, in Tamil and English; the
verified legacy migration. Tests: section 7 as a golden scenario, on-hand = Σ movements and 1200 = stock value as
invariants after every test, negative-stock refusal, concurrent issues, reversal, unit conversion and rounding,
cross-tenant and permission tests for every new endpoint.

## 14. Implementation notes (Phase 4)

Choices made while building, within the approved decisions. None changes a figure in section 7, which the golden
test (`StockGoldenScenarioTest`) reproduces exactly.

- **Where things live.** Finance owns records, numbering, journal and reversal; the stock module owns items, units,
  locations, lots and movements. Finance reaches stock only through the `StockHook` interface it defines, so the module
  boundary (finance never depends on stock) still holds.
- **Item totals.** The item row keeps the item-wide quantity and value (one average per item, E2); per location and lot
  only the quantity is kept. A location's share of value in reports is its quantity at the item's average cost.
- **Reversing stock that came in at a different average.** A reversal takes stock out at its original value, except
  when that would leave value with no quantity (or a negative value). Then it takes what is there, and the difference is
  posted in the reversal entry against farm inputs (5000), cost of goods sold (5700) for customer returns, or losses
  (5980) for counts and wastage, so the ledger still equals the store. If the stock has already been used, sold or
  moved, the reversal is refused (`STOCK_ALREADY_USED`); record a return or a count instead.
- **Count surplus with nothing on hand** is valued at the item's last purchase cost (0 if it was never bought).
- **Lots.** Lot numbers are required on every movement of an item that tracks lots. A lot's expiry is set when it is
  first received. Expiry warnings cover 30 days; the screens list lots first-expiring-first.
- **Returns.** A supplier return reduces the party's payable and is matched first to the bill it names, then to the
  party's oldest open bills; a cash refund goes to a cash or bank account. A customer return must name the sale: stock
  comes back at the value it left with, in proportion, and the last units take exactly what is left.
- **Cash-basis view.** It counts money for categories, so cost of goods sold (non-cash) is left out and a cash refund
  of a cash sale reduces revenue. Stock bought is not a cost in either view.
- **Item categories.** Using stock posts to the item's farm-input category (account 5000), or *Other inputs* when none
  is set. An item line on a sale uses the line's category, or the item's sale category.
- **Phase 1 data.** Opening stock is dated when the item was created (never after its first purchase). Where the Phase 1
  quantity was below what had been bought, the migration records the shortfall as a stock count to review
  (Dr 5980 / Cr 1200 at purchase cost) instead of inventing an opening value; the item and record are marked
  *needs review*. Phase 1 names that differed only in case get the old id appended (for example "urea (14)").
- **Exports.** CSV files start with a byte-order mark so spreadsheet programs read Tamil correctly, and text starting
  with `=`, `+`, `-` or `@` is prefixed with `'` so it cannot run as a formula.
