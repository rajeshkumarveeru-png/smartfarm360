# Poultry, goats and sheep design (Phase 9)

- Status: **Draft for product-owner review** (2026-10-21). No Phase 9 code is written until this is approved.
- Builds on the approved [livestock and dairy design](livestock-and-dairy.md):
  - animal groups as cost objects (I2);
  - dated group events, health events with next-due dates (I9);
  - milk into stock as nil-value produce (I6);
  - exits (I14);
  - herd profitability (I13).

  And on the [finance posting rules](../finance/posting-rules.md): produce at nil value (D3), *Egg sales* and *Livestock
  sales* on 4000.
- Decisions marked **J1–J15** need an answer. Each has a recommendation.

## 1. What this phase fixes

- The *Poultry* and *Goats and sheep* modules are switches marked "coming in a later release".
- A poultry farmer cannot record the essentials:
  - a batch of chicks and how many are alive;
  - daily deaths and eggs;
  - feed per batch;
  - the figures that decide whether a batch made money: **hen-day egg production** and **cost per egg** for layers,
    **feed conversion (FCR)** and **cost per kg** for broilers, **mortality** for both.
- A goat or sheep keeper cannot record a herd's size, kidding and lambing, deaths and sales by head and weight, or see
  what the herd earned.
- Phase 8 records cattle one by one. Flocks of a thousand birds and village goat herds are counted, not tagged.

## 2. Principles

1. **Counted groups.** A poultry batch and a goat or sheep herd are groups with a **head count**. The count changes only
   through recorded movements: placed, bought, born, died, culled, sold, moved in or out, counted. Each movement is
   dated, so the count on any day is known. This is the same rule as stock (ADR 0005), applied to animals.
2. **Same core as dairy.** Groups are the Phase 8 animal groups (cost objects) with a species and a purpose. Health
   events, next-due reminders, exits and reports reuse the livestock module. There is no second animal system.
3. **Produce follows the stock rules.** Eggs are farm produce at nil value (D3), brought into stock once a day. They
   leave as sales, home use or damaged.
4. **Batch-to-date as well as by period.** A broiler batch lives six weeks and a layer batch about 18 months. Reports
   show the period the reader chose **and** the batch since placement, so a batch whose chicks were bought last month
   is not shown as a loss without context.
5. **No husbandry advice.** As in Phase 8, the app records and reminds; it does not suggest vaccines, rations or
   treatments.

## 3. Groups and head counts (J1, J2, J7)

**Species and purposes.**
- Poultry: *Chicken*, with purpose *Layers*, *Broilers* or *Country birds*. Ducks and quail can follow later.
- Goats and sheep: species *Goat* or *Sheep*, with purpose *Breeding herd*, *Fattening* or *Mixed*.

**Poultry batches (J2).**
- A poultry group is a **batch**: one placement, with a placement date, the age at placement (days or weeks) and a
  breed or strain.
- A batch is **closed** when its count reaches zero, or by hand. A closed batch's cost object becomes inactive. Its
  closing summary stays as a report (J14).

**Goat and sheep herds (J1).**
- A goat or sheep group is an ongoing **herd** and is never closed automatically.
- Breeding stock (does, ewes, bucks, rams) can *optionally* be recorded individually with tags, using the Phase 8 animal
  record (species *Goat* or *Sheep*). The herd count includes them.

**Head count movements (J7).**

| Movement | Effect | Notes |
|---|---|---|
| Placed / bought | + | Birds or animals arriving, with a bill (J3) |
| Born | + | Kidding or lambing (J10) |
| Moved in / out | + / − | Between groups (for example weaned kids to a fattening herd) |
| Died | − | With a cause |
| Culled | − | Removed and not sold (for example spent hens given away) |
| Sold | − | With the sale record (J6) |
| Count check | ± | The difference between a physical count and the recorded count, with a reason |

- A movement that would make the count negative is refused.
- The **bird-days** or **animal-days** of a period come from the dated counts. They are the basis of hen-day
  production and of averages.

## 4. Buying birds and animals (J3)

- Chicks, pullets, kids, lambs and grown goats bought are **costs of the group**: a bill with a new expense category,
  *Chicks and young stock* (5000), assigned to the group's cost object. They are **not** farm assets:
  - broiler and layer batches are short-lived and sold or culled in full;
  - goat and sheep values are small and the herd turns over.

  This differs from milch cows (I3), which last years and are registered.
- The trade-off: a batch's period profit is low in the month its birds are bought. Batch-to-date reports (principle 4)
  show the whole picture.
- The same request records the bill and the *placed* count movement, so a batch never has birds without their cost.
  "Received" (gifted or transferred-in) birds and animals have a count movement and no bill.

## 5. Daily batch record (J4, J5, J13)

One record per batch per day, editable until it is used by later records (see I6 in Phase 8):

| Field | Notes |
|---|---|
| Deaths | Count and cause (free text or a short list: disease, heat, predator, unknown) |
| Culls | Count |
| Eggs collected (layers, country birds) | Good, cracked and broken counts |
| Feed given | Stock item and quantity (kg). Becomes one stock use to the batch for the day (J13). |
| Water and notes | Optional |
| Body weight sample (broilers) | Optional: birds weighed and total kg, for average weight |

**Eggs (J5).**
- Good and cracked eggs come into stock once a day, as nil-value produce of the tenant's *Eggs* item, in pieces.
- A new unit, **tray (30 eggs)**, is added to the unit catalogue alongside *dozen*.
- Broken eggs are recorded for production figures but never enter stock.
- Eggs leave stock as sales (*Egg sales*, 4000), home use (to overhead, nil value) or damaged (wastage with a reason).
- Corrections follow the milk rule: the day's record is replaced while its eggs are unused; afterwards the difference is
  recorded as damaged.

**Feed (J13).**
- The daily feed quantity is a stock use from the farm's feed item to the batch, so feed cost is a batch cost at its
  average stock cost.
- Feed can still be recorded weekly through an ordinary stock use instead, if the farmer prefers.

## 6. Selling birds and animals (J6)

One **sale record** covers:
- the number of birds or animals;
- the total live weight (kg), optional for goats and sheep;
- the price, per kg or per head;
- the buyer, or the account the money came into.

It creates:
- a sale with category *Livestock sales* (4000), assigned to the group, with the quantity in kg (or heads) on the line;
- a *sold* count movement for the heads.

Dead birds and culled spent hens given away have no accounting entry; the count movement records them. Spent hens sold
are a normal sale.

## 7. Breeding of goats and sheep (J10)

- **Herd-level kidding or lambing:** a date, the number born alive and the number born dead, optionally the dam (an
  individually recorded doe or ewe).
  - The live births are a *born* movement into the herd.
  - With a dam, a kidding event is added to her record (no lactation is started).
- **Individual breeding events** are available only for individually recorded does and ewes: heat, mating with a
  buck or ram, pregnancy check, kidding or lambing. They use the Phase 8 breeding events.
- Expected kidding or lambing is **150 days** after mating; the farmer can change it. No pregnancy-check or dry-off
  dates are worked out for goats and sheep.
- Poultry has no breeding events in this phase; hatching eggs come later.

## 8. Health (J9)

- Vaccinations, dewormings and treatments for a batch or herd use the Phase 8 group health events, with medicine from
  stock or a vet bill, and next-due reminders.
- Withdrawal days apply to eggs (layers): eggs collected in the withdrawal period should be recorded as damaged, and the
  daily record warns.
- **No schedules are suggested.** A farmer can copy the health plan of an earlier batch of the same purpose to a new
  batch, with dates shifted by placement age. The plan is the farmer's own record, not advice.

## 9. Reports and figures (J8, J14)

All figures are actual. Each report shows the chosen period and batch-to-date.

| Group | Figures |
|---|---|
| Layers | Hen-days; eggs (good, cracked, broken); **hen-day production %** (eggs ÷ hen-days); feed kg and **feed per dozen eggs**; mortality % (deaths ÷ opening count plus placed); costs, revenue, profit; **cost per egg** (costs ÷ eggs collected) |
| Broilers | Placed, died, sold; **mortality %**; live weight sold and **average weight**; feed kg and **FCR** (feed kg ÷ live weight sold); costs, revenue, profit; **cost per kg live weight** |
| Country birds | As layers, with sales of birds as well as eggs |
| Goats and sheep | Opening and closing heads; born, died, sold (heads and kg); costs, revenue, profit; **cost per head per month** |

**Batch closing summary (J14).** When a batch closes, its summary is fixed: the batch-to-date figures above, from
placement to its last movement. It can be printed and exported. Reversing a record in a closed batch reopens it.

The **report catalogue** (Phase 7) gains a *Poultry and small stock* group.

The **dashboard** adds three "needs attention" items:
- today's batch records missing after 10 a.m.;
- mortality above 1% on a day in a batch;
- health due within 7 days (the Phase 8 item covers groups too).

## 10. Worked example (July 2026)

**Layers 2026-A.**
- 1,000 pullets were bought on 1 June 2026 at 18 weeks, for ₹180 each: a ₹1,80,000 bill, a June cost of the batch.
- 10 died in June, so the batch starts July with **990** birds.
- 6 died on 15 July: 990 birds for 14 days and 984 for 17 days.
- Eggs collected in July: 26,000 (25,600 good, 300 cracked, 100 broken).
- 25,200 good eggs (840 trays) were sold at ₹150 a tray: ₹1,26,000.
- The 300 cracked eggs went to home use. 400 good eggs remain in stock.
- 3,400 kg of layer mash was fed at ₹32/kg: ₹1,08,800.
- A vaccination bill of ₹1,500.

| Figure (July) | Value |
|---|---|
| Hen-days | 990 × 14 + 984 × 17 = **30,588** |
| Hen-day production | 26,000 ÷ 30,588 = **85.0%** |
| Feed per dozen eggs | 3,400 ÷ (26,000 ÷ 12) = **1.57 kg** |
| Mortality | 6 ÷ 990 = **0.61%** |
| Costs | 1,08,800 + 1,500 = **₹1,10,300** |
| Revenue | **₹1,26,000** |
| Profit, July | **₹15,700** |
| Cost per egg | 1,10,300 ÷ 26,000 = **₹4.24** |
| Eggs in stock, 31 July | 25,600 + 300 in, 25,200 sold, 300 home use: **400** |
| Batch-to-date (1 June – 31 July) | Includes the ₹1,80,000 pullets and June's own figures, labelled "since placement" |

**Broilers B-07.**
- 2,000 day-old chicks were placed on 20 June 2026 at ₹40: ₹80,000.
- 60 died in the batch.
- 7,000 kg of broiler feed was fed at ₹38: ₹2,66,000.
- Vaccines and medicine ₹6,000; litter ₹4,000.
- 31 July: 1,940 birds sold, 4,268 kg live weight, at ₹110/kg: ₹4,69,480. The count reaches 0 and the batch closes.

| Figure (batch) | Value |
|---|---|
| Mortality | 60 ÷ 2,000 = **3.0%** |
| Average weight | 4,268 ÷ 1,940 = **2.20 kg** |
| FCR | 7,000 ÷ 4,268 = **1.64** |
| Costs | 80,000 + 2,66,000 + 6,000 + 4,000 = **₹3,56,000** |
| Revenue | **₹4,69,480** |
| Profit | **₹1,13,480** |
| Cost per kg live weight | 3,56,000 ÷ 4,268 = **₹83.41** |

**Goat herd.**
- 1 July: 21 goats (20 does and a buck).
- July: 6 kids born alive; 1 kid died.
- 30 July: 4 weaned males sold at ₹6,500 each (88 kg in all): ₹26,000.
- Concentrate 150 kg at ₹30 (₹4,500) and deworming ₹400.

| Figure (July) | Value |
|---|---|
| Heads | 21 + 6 − 1 − 4 = **22** on 31 July |
| Costs | **₹4,900** |
| Revenue | **₹26,000** |
| Profit | **₹21,100** |

## 11. Screens

**Web.** The *Livestock* section gains:
- **Poultry:** batches list; batch page with the daily record grid, counts, health and the batch summary; egg stock and
  where eggs went.
- **Goats and sheep:** herds, counts and movements, kidding and lambing, sales by head and weight.
- Reports.

Each is shown only when its module is on.

**Phone (J12).**
- **Poultry:** the daily batch record (deaths, eggs, feed) in one screen per batch.
- **Herds:** count movements (born, died, sold) and a count check.
- Online now; offline in Phase 13.

## 12. Modules, permissions and data (J11, J15)

**Modules and permissions (J11).**
- *Poultry* and *Goats and sheep* become available as separate switches. They are **off by default** for new and
  existing farm businesses, because most farms keep one or the other. They are switched on in onboarding or Settings.
- They reuse `LIVESTOCK_READ` and `LIVESTOCK_WRITE` and the Phase 8 grants. Money still needs the finance permissions.

**Overhead (J15).** Farm overhead is not shared out to batches or herds in this phase, as for dairy herds (I12).

**Data (one forward-only migration):**
- Columns on `sf_animal_groups`: `species`, placement date, age at placement, strain, closed date.
- New purposes on `sf_animal_groups`: *Layers*, *Broilers*, *Country birds*, *Breeding herd*, *Fattening*, *Mixed*.
- `sf_head_movements`: group, date, kind, heads, kg, cause, linked document.
- `sf_batch_days`: group, date, deaths, culls, eggs good, cracked and broken, feed item and kg, weight sample, linked
  produce and stock-use documents.
- Species *Goat* and *Sheep* on `sf_animals`.
- The *tray* unit (30 pieces).
- The *Chicks and young stock* category (5000).
- An *Eggs* produce item per tenant, created on first use.

All new tables get tenant keys and composite foreign keys. Every new endpoint gets cross-tenant and permission tests.

## 13. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **J1** | Record poultry batches and goat and sheep herds as counted groups, with optional individual records only for breeding goats and sheep? | **Yes** |
| **J2** | Poultry batches by purpose (layers, broilers, country birds), one placement per batch, closed when empty or by hand? | **Yes** |
| **J3** | Bought chicks, pullets, kids, lambs and goats as costs of the group (new category *Chicks and young stock*), not farm assets, with batch-to-date reports? | **Yes** |
| **J4** | One daily batch record (deaths, culls, eggs, feed, optional weight sample), editable until its eggs or feed are used? | **Yes** |
| **J5** | Good and cracked eggs into stock daily at nil value (pieces, with a tray unit of 30); broken eggs recorded but not stocked? | **Yes** |
| **J6** | One sale record for birds and animals by head and live weight, price per kg or per head, with the count movement? | **Yes** |
| **J7** | Head counts change only through dated movements, never negative, with count checks recording differences and a reason? | **Yes** |
| **J8** | The figures in section 9: hen-day %, feed per dozen, cost per egg, mortality, FCR, average weight, cost per kg, cost per head? | **Yes** |
| **J9** | Health through the Phase 8 group events, no suggested schedules, but a farmer may copy an earlier batch's plan? | **Yes** |
| **J10** | Goats and sheep: herd-level kidding and lambing, individual breeding events only for tagged animals, 150-day expected date? | **Yes** |
| **J11** | *Poultry* and *Goats and sheep* available as separate modules, off by default, reusing the livestock permissions? | **Yes** |
| **J12** | Phone: the daily batch record and herd count movements (online now, offline in Phase 13)? | **Yes** |
| **J13** | Daily feed in the batch record becomes one stock use to the batch per day? | **Yes**; weekly stock uses remain possible |
| **J14** | A fixed closing summary for each closed batch, reopened only by reversing one of its records? | **Yes** |
| **J15** | No overhead allocation to batches or herds in this phase? | **Yes**, as for dairy |

## 14. What Phase 9 delivers once this is approved

**Features:**
- poultry batches and goat and sheep herds with dated head counts;
- bought young stock as group costs;
- daily batch records with eggs into stock and feed as stock use;
- egg sales, home use and damage;
- sales by head and weight;
- kidding and lambing;
- group health with copied plans;
- the layer, broiler and herd figures, by period and batch-to-date;
- batch closing summaries;
- reports and exports;
- dashboard items;
- web screens and phone screens, in Tamil and English.

**Tests:**
- section 10 as a golden scenario (layers, broilers and goats);
- head counts never negative and bird-days from dated counts;
- egg stock corrections after use;
- FCR and hen-day arithmetic, including partial days in the period;
- batch closing and reopening on reversal;
- sales posting with count movements;
- kidding with dams;
- copying a health plan;
- permissions and module switches;
- cross-tenant tests for every new endpoint;
- the migration on a copy of the current schema.
