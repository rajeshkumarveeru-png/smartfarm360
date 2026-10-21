# Processing and trading design (Phase 12)

- Status: **Draft for product-owner review** (2026-10-21). No Phase 12 code is written until this is approved.
- Builds on:
  - the [stock movement design](../inventory/stock-movements.md): items with pack units, lots, moving-average cost,
    and stock that changes only through movements (ADR 0005);
  - the [organic resources design](../organic/organic-resources.md): internal transfers of own produce between
    activities (P17), with cost moved at the source's average cost by rule;
  - the [finance posting rules](../finance/posting-rules.md): farm produce at nil value (D3), cost of goods sold for
    goods bought for resale (5700), *Processed product sales* on 4000.
- Decisions marked **M1–M15** need an answer. Each has a recommendation.

## 1. What this phase fixes

- The *Processing and trading* module is a switch marked "coming in a later release".
- A farmer who adds value to the harvest cannot record it:
  - cleaning, drying and grading paddy, milling it into rice, pressing groundnut for oil, packing turmeric;
  - what went in, what came out (rice, broken rice, bran, husk) and what was lost;
  - the **outturn** (how much rice from a quintal of paddy), and the **cost per kg** of the finished product;
  - whether processing paid compared with selling the raw harvest.
- Nothing links a sold bag of rice back to the field and harvest it came from, so a buyer's complaint or a
  certification question cannot be answered.
- A farmer who buys produce from neighbours to sell on (aggregation) has the bills, sales and cost of goods sold, but
  no view of the trading margin by product.

## 2. Principles

1. **A processing batch is an activity.** Milling, drying, pressing or packing one lot of produce is a cost object, like
   a crop cycle or a compost batch. Its costs are the raw material, labour, hired machine work, fuel and packing
   material; its revenue is the finished goods sold.
2. **The raw material carries its cost.** Own harvest moved into processing brings its growing cost with it (the
   average-cost rule of P17), so the crop is not left with costs and no sale, and the batch is not shown as pure profit.
3. **Weight is accounted for.** Every kilogram that goes in comes out as a product, a byproduct, waste or a recorded
   loss. Yields are worked out, never typed.
4. **Every finished lot can be traced.** Back to its batch, the input lots, and the crop cycle or supplier; forward to
   the customers it was sold to.
5. **Finished goods follow the stock rules.** Processed goods are farm produce at nil value (D3). Revenue is recognised
   when they are sold. No standard costs, no inventory revaluation.

## 3. Processing batches (M1, M2)

A **batch** is one run of one process on a defined lot of produce:

| Field | Notes |
|---|---|
| Code | Short and unique, for example *RM-2611-01*. Used in output lot numbers. |
| Name | For example *Rice milling Nov 2026* |
| Kind | Cleaning and grading, drying, milling, oil pressing, packing, other |
| Farm | Required |
| Started on, expected end | The end date is optional, for reminders |
| Main product | The stock item the batch is for (rice), which the cost per kg is worked out for |

- A batch is a cost object (new kind *Processing batch*). Its states are worked out from its records: *Running*,
  *Output taken*, *Finished*.
- A finished batch refuses new inputs, steps and output; it can be reopened.

**Inputs (M2).**

| Input | How it is recorded |
|---|---|
| Own harvest (paddy, groundnut, turmeric) | An **internal transfer** from stock to the batch, at average cost by default (M3) |
| Produce bought from other farmers | Bought into stock on a bill, then used on the batch (existing records), at moving-average cost |
| Packing material (bags, labels), fuel | Used from stock on the batch, or a bill to the batch |
| Hired milling or drying, labour, power | A bill or attendance charged to the batch |

## 4. Steps (M4)

The batch has a dated **step log**: cleaning, drying, grading, milling, pressing, packing, other. A step can record:

- kg in and kg out, so the loss of the step (for example drying loss) is shown;
- moisture % before and after (drying);
- the grade or sieve used;
- notes.

Steps are a record of what was done. They do not move stock; output does.

## 5. Output (M5, M7)

Output is recorded in parts. Each output line has:

| Field | Notes |
|---|---|
| Item | A farm-produce item (rice, broken rice, bran, oil, oil cake, graded turmeric) |
| Quantity | In the item's unit; pack units (50 kg bag, 1 litre can) allowed |
| Role | **Main product**, **byproduct** or **waste** |
| Grade | Optional label, for example *Grade A* |

- Main products and byproducts come into stock as farm produce at nil value (D3). For items that track lots, the lot
  number is made from the batch code (*RM-2611-01-1*, *-2* and so on), so every finished lot is traceable.
- **Waste** (husk burnt for fuel, stones and dust) is recorded in kg for the weight balance, and does not enter stock.
- Marking the last output finishes the batch.

**Weight balance and yields (M7).**
- Input kg = own produce moved in + bought produce used, in kg.
- Each output's **yield** = its kg ÷ input kg. For rice this is the miller's **outturn**.
- **Process loss** = input kg − all output kg (including waste). It is shown, never hidden.

## 6. Cost per kg and profit (M6)

- Batch costs come from its cost object: raw material moved or used, labour, hired work, packing.
- **Cost per kg of the main product** uses the byproduct method common in milling:
  (costs − byproduct sales) ÷ main product kg. Byproduct sales are those assigned to the batch, up to the report date.
- **Profit** = revenue (all finished goods sold, assigned to the batch) − costs.
- **Compared with selling the raw produce (M6):** the report shows the raw material's cost per kg next to the main
  product's cost per kg. The farmer judges the market price; the app does not fetch prices.

## 7. Traceability (M8)

A **lot trace** answers two questions from the stock movements and lots:

- **Backward**, from a finished lot: the batch → its input lots → the crop cycle and harvest (own produce) or the bill
  and supplier (bought produce).
- **Forward**, from a harvest or bought lot: the batches it went into → their output lots → the sales and customers.

Sales of lot-tracked items already record the lot on the sale line. The trace is a report: printable and exported.

## 8. Packing (M9)

- Finished goods are packed with **pack units** on the item (for example *50 kg bag*, *1 kg pouch*), which stock
  already supports.
- Bags, pouches and labels are stock items used on the batch.
- Printing labels and barcodes is not in this phase.

## 9. Trading (M10)

Buying produce from other farmers to sell on already works with existing records: a bill into stock for an item bought
for resale, and a sale that posts the cost of goods sold (5700) at moving-average cost. This phase adds only a
**trading margin report**, by item and period:

| Column | Source |
|---|---|
| Bought (quantity, amount) | Bills for resale items |
| Sold (quantity, revenue) | Sales of resale items |
| Cost of goods sold | 5700 on those sales |
| **Gross margin** and margin % | Revenue − cost of goods sold |
| Direct trading costs | Transport and market charges assigned to the item's sales, when recorded on the sale |
| In stock | Quantity and value at the end of the period |

Produce bought and then processed is not trading; it is a batch input.

## 10. Worked example (November 2026)

**Rice milling, batch RM-2611-01.**
- Samba 2026 produced 4,000 kg of paddy (lot SAMBA-2026-H1). Its costs so far: ₹60,000, so ₹15.00 per kg.
- 1 November: the batch *Rice milling Nov 2026* (code RM-2611-01, main product *Rice*) is started.
- 2,000 kg of paddy is moved into the batch from lot SAMBA-2026-H1 at average cost: **₹30,000** moves from Samba to the
  batch (principle 2).
- Drying step: 2,000 kg at 17% moisture → 1,920 kg at 13.5% (80 kg of drying loss, recorded on the step).
- Hired milling ₹4,000; 26 bags at ₹40 (₹1,040) used from stock; labour ₹1,960.
- 5 November: output

| Output | Role | kg | Yield |
|---|---|---|---|
| Rice (26 × 50 kg bags), lot RM-2611-01-1 | Main | 1,300 | 65.0% |
| Broken rice, lot RM-2611-01-2 | Byproduct | 100 | 5.0% |
| Bran, lot RM-2611-01-3 | Byproduct | 160 | 8.0% |
| Husk (burnt in the dryer) | Waste | 340 | 17.0% |
| **Process loss** (80 kg drying, 20 kg milling) | | **100** | **5.0%** |
| **Input** | | **2,000** | |

- November sales assigned to the batch: rice 1,300 kg at ₹60 (₹78,000), 1,000 kg of it to *Lakshmi Stores*; broken
  rice 100 kg at ₹30 (₹3,000); bran 160 kg at ₹15 (₹2,400).

| Figure (batch) | Value |
|---|---|
| Costs | 30,000 + 4,000 + 1,040 + 1,960 = **₹37,000** |
| Revenue | 78,000 + 3,000 + 2,400 = **₹83,400** |
| Profit | **₹46,400** |
| Cost per kg of rice | (37,000 − 3,000 − 2,400) ÷ 1,300 = **₹24.31** |
| Raw paddy cost per kg | ₹15.00 |
| Samba's costs after the transfer | 60,000 − 30,000 = **₹30,000** for the 2,000 kg still in stock |
| The farm's total profit | **Unchanged** by the transfer |

**Trace.**
- Backward from lot RM-2611-01-1: batch RM-2611-01 → paddy lot SAMBA-2026-H1 → crop cycle *Samba 2026*, North field.
- Forward from SAMBA-2026-H1: batch RM-2611-01 → lots RM-2611-01-1, -2, -3 → sales to Lakshmi Stores and the other buyers.

**Trading.** In November the farm bought 2,000 kg of turmeric for resale at ₹80 (₹1,60,000) and sold 1,500 kg at ₹100
(₹1,50,000). The cost of goods sold is 1,500 × ₹80 = ₹1,20,000, so the gross margin is **₹30,000 (20.0%)**, with 500 kg
(₹40,000) in stock.

## 11. Screens

**Web.** A *Processing* section (shown when its module is on):
- **Batches**: list with code, kind, status and main-product yield.
- **Batch page**: inputs (own produce moved in, stock used, bills), the step log, output with roles and lots, the
  weight balance, figures and sales.
- **Lot trace**: enter or pick a lot; see backward and forward.
- **Reports**: processing batches, trading margin.

**Phone (M13).**
- A step entry (kg in and out, moisture).
- Output of a batch (item, quantity, role).
- Own produce moved into a batch.
- Online now; offline in Phase 13.

## 12. Modules, permissions and data (M12, M15)

**Module and permissions (M12).**
- *Processing and trading* becomes available. It is **off by default** for new and existing farm businesses.
- New permissions `PROCESSING_READ` and `PROCESSING_WRITE`, granted like the organic ones: read and write for owners,
  farm admins, farm managers, field supervisors and workers; read for accountants and viewers.
- The trading margin report needs `FINANCE_READ` and works without the module, since trading uses only existing
  records.

**Overhead and machines (M15).** Farm overhead is not shared out to batches in this phase. An owned mill or dryer is a
farm asset whose depreciation stays in overhead; hired machine work is a bill to the batch.

**Data (one forward-only migration):**
- Cost object kind *Processing batch*.
- `sf_processing_batches`: cost object, code, kind, farm, main item, started on, expected end, finished on, notes.
- `sf_processing_steps`: batch, date, kind, kg in, kg out, moisture before and after, grade, notes.
- `sf_processing_outputs`: batch, date, item, quantity, role (main, byproduct, waste), grade, lot, linked produce
  record (none for waste).
- The default cost rule of internal transfers extended: produce moved **into** a processing batch moves at average cost.

All quantities and amounts are `NUMERIC`. All new tables get tenant keys and composite foreign keys. Every new endpoint
gets cross-tenant and permission tests.

## 13. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **M1** | Record each run of a process (cleaning and grading, drying, milling, oil pressing, packing) as a processing batch, a cost object with a code and a main product? | **Yes** |
| **M2** | Inputs through internal transfers (own produce), stock uses (bought produce, packing) and bills (hired work, labour)? | **Yes** |
| **M3** | Own produce moved into a processing batch carries its average cost by default, so the crop's cost follows its harvest? | **Yes** |
| **M4** | A step log with kg in and out, moisture before and after, and grade, as a record that does not move stock? | **Yes** |
| **M5** | Output in parts as main product, byproduct or waste; products and byproducts into stock at nil value with lots made from the batch code; waste recorded in kg only? | **Yes** |
| **M6** | Cost per kg of the main product by the byproduct method, shown next to the raw material's cost per kg; no market prices fetched? | **Yes** |
| **M7** | Yields per output and process loss from the weight balance, never typed? | **Yes** |
| **M8** | A lot trace report, backward to the crop cycle or supplier and forward to customers? | **Yes** |
| **M9** | Packing with the existing pack units and packing material from stock; no label or barcode printing in this phase? | **Yes** |
| **M10** | No new trading records; a trading margin report for items bought for resale? | **Yes** |
| **M11** | Grades as labels on outputs; no food-safety (FSSAI) or certification records in this phase? | **Yes**; later if asked |
| **M12** | *Processing and trading* available, off by default, with new permissions `PROCESSING_READ` and `PROCESSING_WRITE`; the trading report without the module? | **Yes** |
| **M13** | Phone: steps, output and own produce into a batch (online now, offline in Phase 13)? | **Yes** |
| **M14** | Reports: processing batches, lot trace, trading margin; dashboard item for batches past their expected end? | **Yes** |
| **M15** | No overhead allocation to batches; owned mills stay farm assets in overhead, hired work is a bill to the batch? | **Yes**, as for other activities |

## 14. What Phase 12 delivers once this is approved

**Features:**
- processing batches with inputs, steps, output by role and lots, weight balance and figures;
- own produce into processing at average cost;
- the lot trace, backward and forward;
- the trading margin report;
- the processing batches report and exports, and the dashboard item;
- web screens and phone screens, in Tamil and English.

**Tests:**
- section 10 as a golden scenario (milling, trace and trading), every figure asserted;
- the weight balance and yields, including waste and loss;
- cost per kg by the byproduct method, before and after byproduct sales;
- output lots from the batch code, and the trace through a bought lot;
- the cost rule for transfers into a batch, and the farm total unchanged;
- a finished batch refuses inputs and output until reopened;
- permissions and the module switch, and the trading report without the module;
- cross-tenant tests for every new endpoint;
- the migration on a copy of the current schema.
