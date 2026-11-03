# Organic resources design (Phase 11)

- Status: **Approved** (2026-10-21) with the recommended answers to L1–L15, and implemented in Phase 11. Implementation
  notes are in section 13.
- Builds on:
  - the [finance posting rules](../finance/posting-rules.md): farm produce at nil value (D3), overhead allocation as a
    net-zero move of cost between activities (P16), and the rule fixed for **internal transfers (P17)**: quantity moves
    in stock, cost moves with a P16-style net-zero line, and it is **never** revenue;
  - [ADR 0004](../adr/0004-accounting-model.md): internal transfers move cost between cost objects and never create
    revenue;
  - the [crop](../crops/crop-management.md), [livestock](../livestock/livestock-and-dairy.md),
    [poultry, goats and sheep](../livestock/poultry-goats-sheep.md) and [aquaculture](../aquaculture/aquaculture.md)
    designs: activities as cost objects, produce into stock from an activity.
- Decisions marked **L1–L15** need an answer. Each has a recommendation.

## 1. What this phase fixes

- The *Organic manure* module is a switch marked "coming in a later release".
- A farmer who makes compost or vermicompost cannot record:
  - a compost pit or vermicompost bed and what went into it (dung, crop residues, bought earthworms, labour);
  - turning, watering and checks;
  - the compost taken out, its yield, and its cost per kg;
  - compost sold, and compost spread on their own fields.
- **Own produce cannot move between activities.** Today a farmer can use stock on an activity, but farm produce is at
  nil value (D3), so:
  - compost spread on a paddy field carries none of its making cost to the paddy crop;
  - fodder grown on the farm and fed to the herd leaves its growing cost with the fodder crop, so the fodder plot looks
    like a loss and the herd looks cheaper than it is (an open item from Phase 8);
  - dung from the herd and straw from the fields cannot be recorded as going into compost.

## 2. Principles

1. **An internal transfer is never income.** Moving own produce between activities moves quantity and, where chosen,
   cost. The farm's total profit is unchanged by any transfer (P17, ADR 0004).
2. **Compost making is an activity.** A compost pit, vermicompost bed or liquid manure batch is a cost object, like a
   crop cycle or a pond crop. Its costs are bills, stock used and labour; its output is produce in stock; its revenue
   is compost sold.
3. **Cost moves by rule, not by guess.** When cost moves with a transfer, it is the source's **average cost per unit
   of that produce** (its costs so far ÷ the quantity it has produced so far), never a typed price. It cannot move more
   cost than the source has left.
4. **Byproducts stay free unless the owner says otherwise.** Dung, straw and residues are byproducts; their main
   activity (milk, grain) keeps its costs. They move at nil unless an owner or accountant chooses to move cost.
5. **No agronomy advice.** The app records what was put in, what was done and what came out. It does not suggest
   recipes, ratios or application rates.

## 3. Organic batches (L1, L2)

A **batch** is one compost pit, vermicompost bed or liquid manure preparation, from start to finish:

| Field | Notes |
|---|---|
| Name | For example *VC bed 1 – Aug 2026* |
| Kind | Compost (heap or pit), vermicompost (bed), liquid manure (jeevamrutham, panchagavya and the like), other |
| Farm | Required |
| Started on | Required |
| Expected ready by | Optional, for reminders |
| Finished on | Set when the farmer marks the batch finished |
| Notes | Optional (bed size, location) |

- A batch is a cost object (new kind *Organic batch*). Its states are worked out from its records, as for crops and
  ponds: *Making*, *Ready* (some output taken), *Finished*.
- A finished batch refuses new inputs and log entries. Marking it unfinished again is allowed while nothing else
  depends on it.

## 4. What goes into a batch (L2, L3)

| Input | How it is recorded |
|---|---|
| Own dung, straw, crop residues, leaves | An **internal transfer** from stock to the batch (section 6). At nil by default (principle 4). |
| Bought dung, earthworms, jaggery, pulse flour, bio-cultures | A bill to the batch, or bought into stock and then used on the batch (existing records) |
| Labour | Attendance charged to the batch, or a labour bill (existing records) |
| Materials with no stock item (leaves collected, water) | A log entry with a quantity, for the record only |

**Manure collection (L3).** Dung and poultry litter come into stock as farm produce from the animal group or batch,
with the existing *Farm produce into stock* record (nil value, D3). A *Collect manure* shortcut on the herd, batch and
organic pages fills it in. Weekly entries are fine.

## 5. Batch log and output (L6, L7)

**Log (L6).** Dated entries: turned, watered, material added (with kg), temperature checked (°C), moisture checked
(dry, right, wet), worms added or separated (kg), other. Notes are free text. The log is a record, not advice.

**Output (L7).**
- Compost, vermicompost or liquid manure taken out comes into stock as farm produce from the batch (kg, or litres for
  liquids), at nil value (D3). It can be taken out in parts.
- The farmer marks the batch **finished** with the last output.
- **Yield** = output kg ÷ input kg recorded (transfers in, bought inputs in kg, and log additions in kg).

Compost sold to others is an ordinary sale with the existing category *Manure and compost sales* (4000), assigned to
the batch.

## 6. Internal transfers (L4, L5, L9, L12)

An **internal transfer** moves own produce from stock to another activity. It is a new finance record, *Internal
transfer* (`ITRF/2026-27/0001`): numbered, dated, lock-date checked and reversible.

| Field | Notes |
|---|---|
| Date | Required |
| Item and quantity | Farm produce in stock (compost, dung, fodder, straw, grain) |
| To | The activity that uses it: a crop cycle, animal group, batch, pond crop or organic batch |
| From | The activity that produced it. Required when cost moves |
| Cost | *None* or *Average cost* (L5) |

It creates:
- the **stock movement**: the quantity leaves stock and is used on the target activity, as a stock use does today;
- when cost moves, a **net-zero cost line**: Dr *Own farm inputs* (target) / Cr *Own farm inputs* (source), on a new
  operating account **5650 Own farm inputs**. The farm's total cost is unchanged; the source's cost goes down and the
  target's goes up.

**Average cost (L5).**
- Cost per unit = the source's costs so far ÷ the quantity of that item it has produced so far, both up to the
  transfer date. It is shown before saving.
- The cost moved is capped at the source's costs not already moved out.
- Earlier transfers are not recalculated when the source produces more later. Like moving-average stock cost, the
  figure is the one known on the day.
- **Who chooses:**
  - Output of an organic batch moves at average cost **by default**, since the batch exists to serve the farm's
    fields.
  - Byproducts (dung, straw, residues) and other produce (fodder, grain fed to animals) move at **nil by default**. An
    owner or accountant (`FINANCE_MANAGE`) may choose average cost, as with overhead allocation (P16).
- The quantity check: with a source, the source must have produced at least the quantity being moved, less what was
  already moved from it.

**Transfers are not part of the module.** Internal transfers are a stock feature, available to every farm business
(L12), so fodder to the herd works without the *Organic manure* module.

## 7. Reports and figures (L8, L14)

**Batch figures (L8)**, for the chosen period and the whole batch:

| Figure | How it is worked out |
|---|---|
| Days | Start to finish (or to today) |
| Input kg | Transfers in, bought inputs in kg, log additions in kg |
| Output kg (or litres) | Produce into stock from the batch |
| **Yield %** | Output kg ÷ input kg |
| Costs | From the batch's cost object, before transfers out |
| Moved to other activities | Own farm inputs credited to the batch |
| Revenue | Compost sold |
| **Cost per kg** | Costs ÷ output kg |
| Profit | Revenue − costs + cost moved out |
| In stock | Output not yet sold or moved |

**Reports.**
- **Organic batches**: one row per batch with the figures above; exported as CSV and Excel.
- **Internal transfers**: by date, item, from, to, quantity and cost moved; exported.
- Crop, herd, batch and pond reports show **own farm inputs** as their own cost line, so a reader sees what came from
  the farm.
- The Phase 7 report catalogue gains an *Organic resources* group.

**Dashboard (L14):** organic batches past their expected ready date and not finished.

## 8. Worked example (2026)

**Making vermicompost (bed VC-1).**
- 1 August: batch *VC bed 1 – Aug 2026* started.
- Milking herd dung collected into stock: 2,000 kg (nil value). Samba straw into stock: 500 kg (nil value).
- 2 August: both moved into VC-1 by internal transfer, at nil (byproducts): 2,500 kg of input.
- Earthworms 10 kg at ₹300: a ₹3,000 bill to VC-1. Shade net and bed lining: ₹2,000. Labour: ₹5,000.
- 15 October: 1,000 kg of vermicompost taken out and the batch marked finished.

| Figure (batch) | Value |
|---|---|
| Days | 1 August – 15 October = **75** |
| Input | **2,500 kg** |
| Output | **1,000 kg** |
| Yield | 1,000 ÷ 2,500 = **40.0%** |
| Costs | 3,000 + 2,000 + 5,000 = **₹10,000** |
| Cost per kg | 10,000 ÷ 1,000 = **₹10.00** |

**Using and selling it.**
- 18 October: 300 kg sold to a neighbour at ₹15: **₹4,500** of *Manure and compost sales*, assigned to VC-1.
- 20 October: 600 kg spread on the Groundnut cycle by internal transfer at average cost: 600 × ₹10.00 = **₹6,000**
  moves from VC-1 to Groundnut.

| After the transfer | Value |
|---|---|
| VC-1 costs moved out | **₹6,000** |
| VC-1 profit | 4,500 − 10,000 + 6,000 = **₹500** |
| Groundnut costs | up by **₹6,000** of own farm inputs |
| Vermicompost in stock | 1,000 − 300 − 600 = **100 kg** |
| The farm's total profit | **Unchanged** by the transfer |

**Fodder to the herd.**
- The fodder plot cycle *CO-4 grass 2026* has costs of ₹12,000 and has produced 24,000 kg of green fodder into stock.
- July: 6,000 kg fed to the Milking herd. The owner chooses average cost: ₹12,000 ÷ 24,000 = ₹0.50 per kg, so
  **₹3,000** moves from the fodder plot to the herd.
- Fodder plot costs after the move: **₹9,000**. Milking herd costs: up by **₹3,000**. Farm total: unchanged.

## 9. Screens

**Web.**
- **Organic** section (shown when its module is on): batches list; batch page with inputs, log, output, sales and
  figures; reports.
- **Stock → Use own produce on another activity**: the internal transfer form, with the average cost shown before
  saving. Available to every farm business.
- **Collect manure** shortcuts on herd, batch and organic pages.

**Phone (L13).**
- A batch log entry (turned, watered, added, checked).
- Output taken out of a batch.
- Manure collected from a herd or batch.
- Compost spread on a field (an internal transfer at the default cost rule).
- Online now; offline in Phase 13.

## 10. Modules, permissions and data (L11, L12, L15)

**Module and permissions (L11).**
- *Organic manure* becomes available, renamed **Organic manure and compost**. It is **off by default** for new and
  existing farm businesses.
- New permissions `ORGANIC_READ` and `ORGANIC_WRITE`, granted like the aquaculture ones: read and write for owners,
  farm admins, farm managers, field supervisors and workers; read for accountants and viewers.

**Internal transfers (L12).** Recording one needs `INVENTORY_WRITE`. Choosing average cost for a byproduct needs
`FINANCE_MANAGE`. Reversing one needs `FINANCE_REVERSE`, like other records.

**Overhead (L15).** Farm overhead is not shared out to organic batches in this phase.

**Data (one forward-only migration):**
- Cost object kind *Organic batch*.
- `sf_organic_batches`: cost object, kind, farm, started on, ready by, finished on, notes.
- `sf_organic_logs`: batch, date, kind, kg, temperature, moisture, notes.
- Document type *Internal transfer*; `sf_internal_transfers`: document, item, quantity, source and target cost objects,
  cost method, cost moved.
- Account **5650 Own farm inputs** (operating cost, nets to zero for the farm).
- The *Organic manure and compost* module label.

All quantities and amounts are `NUMERIC`. All new tables get tenant keys and composite foreign keys. Every new endpoint
gets cross-tenant and permission tests.

## 11. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **L1** | Record compost pits, vermicompost beds and liquid manure preparations as organic batches, each a cost object from start to finish? | **Yes** |
| **L2** | Inputs to a batch through existing records (bills, stock uses, attendance) plus internal transfers of own produce, with a log for materials that are not stock? | **Yes** |
| **L3** | Dung and litter into stock as farm produce from the herd or batch, with a *Collect manure* shortcut, at nil value? | **Yes** |
| **L4** | A new reversible finance record *Internal transfer* that moves own produce to another activity as a stock use, and never creates revenue? | **Yes** |
| **L5** | When cost moves, use the source's average cost per unit so far (capped at its unmoved costs), never a typed price? | **Yes** |
| **L6** | A batch log (turned, watered, added, checked, worms) as a record, with no suggested recipes or schedules? | **Yes** |
| **L7** | Output into stock in parts at nil value, with the batch marked finished by the farmer, and yield = output ÷ input kg? | **Yes** |
| **L8** | The batch figures in section 7: yield, cost per kg, cost moved out, profit, stock left? | **Yes** |
| **L9** | Organic batch output moves at average cost by default; byproducts and other produce at nil unless an owner or accountant chooses average cost? | **Yes** |
| **L10** | No certification records (organic inspection, input approvals) in this phase? | **Yes**; later if asked |
| **L11** | *Organic manure and compost* available, off by default, with new permissions `ORGANIC_READ` and `ORGANIC_WRITE`? | **Yes** |
| **L12** | Internal transfers as a stock feature for every farm business, needing `INVENTORY_WRITE` (and `FINANCE_MANAGE` to move cost for byproducts)? | **Yes** |
| **L13** | Phone: batch log, output, manure collection and compost to a field (online now, offline in Phase 13)? | **Yes** |
| **L14** | The organic batches and internal transfers reports, own farm inputs as a cost line in activity reports, and the overdue-batch dashboard item? | **Yes** |
| **L15** | No overhead allocation to organic batches in this phase? | **Yes**, as for herds and ponds |

## 12. What Phase 11 delivers once this is approved

**Features:**
- organic batches with inputs, log, output and figures;
- manure collection from herds and batches;
- internal transfers of own produce between any activities, with cost moved at average cost where chosen;
- own farm inputs shown in activity reports;
- the organic batches and internal transfers reports and exports, and the dashboard item;
- web screens and phone screens, in Tamil and English.

**Tests:**
- section 8 as a golden scenario (vermicompost and fodder), every figure asserted;
- a transfer never changes the farm's total profit, and reversing it restores both activities;
- average cost capped at the source's unmoved costs, and the quantity check against what the source produced;
- defaults by source kind, and the `FINANCE_MANAGE` rule for byproducts;
- a finished batch refuses inputs;
- permissions and the module switch, and transfers without the module;
- cross-tenant tests for every new endpoint;
- the migration on a copy of the current schema.

## 13. Implementation notes (2026-10-21)

- **Modules.** Internal transfers live in the `inventory` package (`InternalTransferService`, under
  `/stock/internal-transfers`), so they work without the *Organic manure* module (L12). Organic batches are a new
  `organic` package; `ModuleBoundaryTest` (rule `onlyReportingUsesOrganic`) allows only `reporting` to depend on it.
- **The record (L4).** *Internal transfer* is a stock record type (`ITRF`), so the existing reversal reverses both the
  stock movement and the cost lines. The quantity is an `ISSUE` movement on the target activity, the same as a stock
  use. A nil-value transfer has a zero total; changeset `049` also widens the document-total check for it. `049` had not
  been applied anywhere when this was found, so the changeset was corrected before its first run.
- **Average cost (L5).** The source's costs so far are its posted expense lines up to the transfer date, with the cost
  it has already moved out added back. Cost per unit = those costs ÷ the quantity of that item it has produced; the
  move is capped at the costs not yet moved. A reversed transfer no longer counts against the source.
- **Who chooses (L9).** The default comes from the source: organic batch output moves at average cost, anything else at
  nil. Choosing the other method, in either direction, needs `FINANCE_MANAGE`. A preview endpoint shows the figures
  before saving.
- **Finished batches.** Transfers into a finished batch are refused. Bills assigned to a finished batch are not
  refused, because finance does not know about batches; they still count as the batch's costs.
- **Figures (L8).** *Ready* means some output has been taken out. Input kg counts stock used on the batch in kg (own
  produce moved in and bought stock) and log additions in kg. Liquid batches show litres and no yield. *In stock* is
  output less what was moved or sold from the batch.
- **Manure (L3).** *Collect manure* is on the organic batches page and the livestock *Groups* page. It records
  *Farm produce into stock* from the chosen herd or batch, so it needs `INVENTORY_WRITE`, and `FINANCE_READ` to list
  the activities. Taking output out of a batch also needs `INVENTORY_WRITE`.
- **Own farm inputs in reports (L14).** Transfer cost lines carry no category, so activity reports show them under the
  account *Own farm inputs* (5650).
- **Lots.** Harvests that track lots are moved lot by lot: the transfer takes a lot number, and the web form offers the
  item's open lots. (Found in the browser check; the phone form does not offer lots yet.)
- **Phone (L13).** Under *More → Compost*. Compost can be spread on crop and pond cycles.

