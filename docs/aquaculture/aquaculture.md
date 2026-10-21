# Aquaculture design (Phase 10)

- Status: **Approved** (2026-10-21) with the recommended answers to K1–K15, and implemented in Phase 10. Implementation
  notes are in section 15.
- Builds on:
  - the [crop management design](../crops/crop-management.md): a season on a piece of land is a cost object with a
    start, a harvest and a profit;
  - the [poultry, goats and sheep design](../livestock/poultry-goats-sheep.md): counted animals, a daily record with
    feed as one stock use (J13), figures by period and since stocking;
  - the [finance posting rules](../finance/posting-rules.md): *Fish sales* on 4000, bills and stock uses assigned to
    a cost object.
- Decisions marked **K1–K15** need an answer. Each has a recommendation.

## 1. What this phase fixes

- The *Fish farming* module is a switch marked "coming in a later release".
- A fish or shrimp farmer cannot record the essentials:
  - their ponds and their water area;
  - a crop of fish from stocking to harvest, with its seed, feed, inputs, power and labour;
  - daily feed and deaths seen;
  - weight sampling, so they know the size and the estimated weight of fish in the pond;
  - water quality readings (dissolved oxygen, pH, temperature and others) against the limits they set;
  - the harvest, by species and size grade, sold at the pond bank;
  - the figures that decide whether a crop made money: **survival**, **FCR**, **yield per acre**, **cost per kg** and
    **profit per crop**.

## 2. Principles

1. **A pond crop is like a crop season.** Each stocking-to-harvest run in a pond is a **culture cycle**: a cost object
   with preparation, stocking, growing and harvest, the same way a crop cycle is a season on a field. Seed, feed,
   inputs, power and labour are costs of the cycle; the harvest is its revenue.
2. **Fish are estimated, not counted.** Unlike a poultry shed, nobody can count fish in a pond. The app keeps an
   **estimated count** (stocked, minus deaths seen, minus fish harvested) and an **estimated biomass** from the latest
   weight sample. Both are labelled "estimated". The true survival is known only at the final harvest.
3. **Same finance and stock rules.** Feed and inputs leave stock as stock uses to the cycle (ADR 0005). Seed and power
   are bills to the cycle. Harvest sales are *Fish sales*.
4. **Crop-to-date as well as by period.** A carp crop takes eight to ten months and a shrimp crop about four. Reports
   show the chosen period **and** the cycle since stocking.
5. **No husbandry advice.** The app records readings, compares them with the limits the farmer sets, and reminds. It
   does not suggest feed rates, stocking densities, aeration or treatments.

## 3. Ponds (K1)

A **pond** is a record under a farm:

| Field | Notes |
|---|---|
| Name | For example *Pond 1* or *Nursery tank* |
| Farm | Required |
| Kind | Earthen pond, lined pond, tank, cage |
| Water spread area | In the farm business's area unit (acre by default); the basis of yield per acre |
| Average depth | Metres, optional |
| Land parcel | Optional link to a Phase 1 land parcel, so a field turned into a pond keeps its history |
| Water limits | Optional per-pond limits (section 7) |
| Active | A pond that is filled in or no longer used is made inactive, never deleted |

- A pond is **not** a cost object. Its digging, lining and aerators are farm assets in the existing *Assets* register,
  depreciated to farm overhead (K15).
- A pond has at most **one open culture cycle** at a time.

## 4. Culture cycles and stocking (K2, K3, K4, K11)

**Culture cycle (K2).**
- A cycle belongs to one pond and is a cost object (new kind *Pond cycle*), named for example *Pond 1 – 2026*.
- Its states are worked out from its records, as for crop cycles:
  - **Preparing**: created, nothing stocked yet. Pond preparation costs (draining, liming, manuring) are costs of the
    cycle.
  - **Growing**: at least one stocking.
  - **Harvested**: a final harvest has been recorded. A harvested cycle refuses new feed and stocking. Reversing the
    final harvest's sale reopens it.
- Optional planned harvest date, for reminders.
- **Polyculture** is normal: one cycle can hold several species (for example rohu, catla and mrigal).

**Species (K11).**
- Fish: rohu, catla, mrigal, common carp, grass carp, silver carp, tilapia (GIFT), pangasius, murrel, other.
- Shrimp and prawn: white-leg shrimp (vannamei), tiger shrimp, freshwater prawn (scampi).
- *Other* takes a name. The list can grow without a migration of data.

**Stocking (K3, K4).** Each stocking records:
- date, species, number stocked and their total weight in kg (for the starting average size);
- optionally the hatchery and its bill: the seed is a **cost of the cycle**, under a new expense category *Fish and
  shrimp seed* (K4). Shrimp post-larvae are entered in thousands for convenience but kept as a number.

The same request records the bill and the stocking, so a cycle never has seed without its cost. Seed received free
(from a government scheme, for example) has no bill.

**Estimated count (K3).** For each species in the cycle:
- estimated count = stocked − deaths seen − fish harvested (when the harvest counts them);
- it never goes below zero; deaths that would make it negative are refused;
- at the **final harvest** the fish actually harvested give the true survival, and the difference from the estimate
  is shown as **unseen losses** (birds, disease, theft). The estimate is not corrected backwards.

## 5. Daily pond record (K5)

One record per cycle per day, editable until replaced:

| Field | Notes |
|---|---|
| Feed | Stock item and kg. Becomes one stock use to the cycle for the day, as in poultry (J13). Two feeds in a day (starter and grower) are two lines. |
| Deaths seen | By species, count; optional cause |
| Notes | Optional (for example "fish not feeding", "aerator off 2 hours") |

- Changing the day reverses the earlier stock use and posts the new one.
- Feed can still be recorded weekly through an ordinary stock use, if the farmer prefers.
- Other inputs (lime, fertiliser, probiotics, minerals, medicines) are ordinary stock uses or bills assigned to the
  cycle, through the existing screens (K10).

## 6. Weight sampling (K6)

A **sample** records: date, species, number of fish weighed and their total weight (kg).

From it:
- **average body weight (ABW)** = total kg ÷ fish weighed, in grams;
- **estimated biomass** of the species = estimated count × ABW;
- **estimated FCR to date** = feed kg so far ÷ (estimated biomass − weight stocked).

All three are labelled "estimated" and shown on the cycle page and in reports. Samples are optional; without one the
cycle shows only actual figures.

## 7. Water quality (K7)

A **reading** belongs to a pond (not a cycle, so readings continue between crops). It records the date and time, and
any of:

| Parameter | Unit |
|---|---|
| Dissolved oxygen (DO) | mg/L |
| pH | — |
| Water temperature | °C |
| Total ammonia | mg/L |
| Transparency (Secchi disc) | cm |
| Salinity | ppt (shrimp) |
| Notes | Free text, for example water colour |

**Limits.** The farmer may set a lowest and highest acceptable value per parameter, as a farm-business default or per
pond. A reading outside its limits is flagged on the pond page and on the dashboard. The app ships with **no limits
filled in**: they are the farmer's own numbers (principle 5).

Readings are measurements, not money, and have no effect on finance or stock.

## 8. Harvest and sales (K8)

A harvest is **partial** (some fish taken out, for example the larger ones) or **final** (the pond is emptied).
Each harvest has lines by species and **size grade**:

| Line field | Notes |
|---|---|
| Species | From the cycle |
| Grade | Farmer's own label, for example *Above 1 kg*, *500 g – 1 kg*, *Small* |
| Kg | Required |
| Fish count | Optional; needed for survival at the final harvest |
| Where it went | Sold, home use, given away |
| Rate per kg | For sold lines |

It creates:
- for sold lines, **one sale** with category *Fish sales* (4000), assigned to the cycle, with a line per species and
  grade, to the buyer or into the account the money came into;
- the harvested fish count (when given), lowering the estimated count;
- for a final harvest, the cycle becomes *Harvested*.

**Fish are not kept in stock.** Fish are sold at the pond bank on the day, and they spoil within hours. Home use and
fish given away are recorded as kg with no value. (If processing and cold storage arrive in Phase 12, harvested fish
can go into stock then.)

Common deductions from the trader (commission, ice, transport) are lines of the same record with the existing expense
categories, so the sale shows the net amount received.

## 9. Reports and figures (K9, K14)

All figures are actual unless labelled estimated. Each report shows the chosen period and the cycle since stocking.

| Figure | How it is worked out |
|---|---|
| Days of culture | First stocking to final harvest (or to today) |
| Stocked | Number and kg, by species |
| Harvested | Kg and count, by species and grade |
| **Survival %** | Fish harvested (counted) ÷ fish stocked, at the final harvest |
| Unseen losses | Estimated count before the final harvest − fish harvested |
| **FCR** | Feed kg ÷ (kg harvested − kg stocked) |
| **Yield per acre** | Kg harvested ÷ water spread area |
| Costs, revenue, profit | From the cycle's cost object |
| **Cost per kg** | Costs ÷ kg harvested |
| **Profit per acre** | Profit ÷ water spread area |
| Average price per kg | Fish sales ÷ kg sold |
| Estimated biomass and FCR to date | From the latest samples (section 6), for growing cycles |

**Reports.**
- **Pond cycles**: one row per cycle with the figures above; exported as CSV and Excel.
- **Water quality log**: readings by pond and date, with out-of-limit readings marked; exported.
- **Cycle summary**: the cycle's whole story from preparation to harvest, printable (K14).
- The Phase 7 report catalogue gains a *Fish farming* group.

**Dashboard** "needs attention" items (K14):
- a water reading outside its limits today;
- today's pond record missing after 10 a.m. for a growing cycle;
- a cycle past its planned harvest date.

## 10. Worked example (2026)

**Pond 1**: earthen pond, 1 acre of water spread. Carp polyculture.

**Preparation (January 2026).** Lime 200 kg at ₹10 from stock: ₹2,000. Manure bill: ₹3,000. Both are costs of the cycle
*Pond 1 – 2026* while it is *Preparing*.

**Stocking (1 February 2026).** 4,000 fingerlings from a hatchery at ₹2 each, a ₹8,000 bill:

| Species | Number | Kg |
|---|---|---|
| Rohu | 1,600 | 16 |
| Catla | 1,200 | 12 |
| Mrigal | 1,200 | 12 |
| **Total** | **4,000** | **40** |

**Growing (February – September).**
- Feed: 6,000 kg of floating feed at ₹30/kg, recorded daily: ₹1,80,000.
- Aerator electricity bills: ₹12,000. Labour charged to the cycle: ₹24,000.
- Deaths seen: 200 fish in all.
- **Sample, 1 August:** 50 fish weighed 30 kg, so ABW **600 g**. Deaths seen by then: 150, so the estimated count is
  3,850 and the estimated biomass **2,310 kg**. Feed by then: 4,200 kg, so the estimated FCR to date is
  4,200 ÷ (2,310 − 40) = **1.85**.
- **Water reading, 12 June, 5 a.m.:** DO 3.2 mg/L. The farmer's lowest DO limit is 4.0, so the reading is flagged
  and the dashboard shows it that day.

**Final harvest (30 September 2026)**, sold to a trader at the pond bank:

| Grade | Kg | Rate | Amount |
|---|---|---|---|
| Rohu, above 1 kg | 1,100 | ₹140 | ₹1,54,000 |
| Catla, above 1 kg | 1,000 | ₹150 | ₹1,50,000 |
| Mrigal, above 750 g | 700 | ₹120 | ₹84,000 |
| Mixed small | 200 | ₹80 | ₹16,000 |
| **Total** | **3,000** | | **₹4,04,000** |

3,200 fish were counted at harvest.

| Figure (cycle) | Value |
|---|---|
| Days of culture | 1 February – 30 September = **241 days** |
| Survival | 3,200 ÷ 4,000 = **80.0%** |
| Unseen losses | 4,000 − 200 − 3,200 = **600 fish** |
| FCR | 6,000 ÷ (3,000 − 40) = **2.03** |
| Yield per acre | **3,000 kg** |
| Costs | 2,000 + 3,000 + 8,000 + 1,80,000 + 12,000 + 24,000 = **₹2,29,000** |
| Revenue | **₹4,04,000** |
| Profit | **₹1,75,000**, which is also the profit per acre |
| Cost per kg | 2,29,000 ÷ 3,000 = **₹76.33** |
| Average price per kg | 4,04,000 ÷ 3,000 = **₹134.67** |

A **period report for July 2026 alone** shows only July's feed, power and labour as costs and no revenue: a loss. The
"since stocking" column beside it shows the whole crop, so the reader is not misled (principle 4).

## 11. Screens

**Web.** A new *Fish farming* section (shown when its module is on):
- **Ponds**: list with area, the open cycle, its days of culture, estimated count and biomass, and the latest water
  reading with any flag.
- **Pond page**: details, limits, water readings, and the history of cycles.
- **Cycle page**: stockings, the daily record grid, samples, harvests, figures for the period and since stocking.
- **Water quality**: readings across ponds for a date range.
- **Reports**: pond cycles and the water quality log.

**Phone (K13).**
- The daily pond record: feed and deaths seen, per open cycle.
- A water reading for a pond, with the out-of-limit flag shown at once.
- A weight sample.
- Online now; offline in Phase 13.

## 12. Modules, permissions and data (K12, K15)

**Module and permissions (K12).**
- *Fish farming* becomes available. It is **off by default** for new and existing farm businesses, and switched on in
  onboarding or Settings.
- New permissions `AQUACULTURE_READ` and `AQUACULTURE_WRITE`, granted to the same roles as the livestock permissions:
  read and write for owners, farm admins, farm managers, field supervisors and workers; read for accountants and viewers. Money still needs the finance permissions.
- The code is a new module package `aquaculture`. As with livestock, only `reporting` depends on it;
  `ModuleBoundaryTest` gains the rule.

**Overhead and pond assets (K15).** Farm overhead is not shared out to cycles in this phase, as for herds and batches.
Pond construction and aerators are assets whose depreciation stays in farm overhead.

**Data (one forward-only migration):**
- `sf_ponds`: farm, optional land parcel, name, kind, water area and unit, depth, active.
- Cost object kind *Pond cycle*; `sf_pond_cycles`: pond, cost object, name, planned harvest date, harvested on.
- `sf_pond_stockings`: cycle, date, species, count, kg, linked bill.
- `sf_pond_days` and `sf_pond_day_feeds`: cycle, date, notes; feed item and kg; linked stock use.
- `sf_pond_deaths`: cycle, date, species, count, cause (from the daily record).
- `sf_pond_samples`: cycle, date, species, fish weighed, kg.
- `sf_water_readings`: pond, taken at (`TIMESTAMP WITH TIME ZONE`), DO, pH, temperature, ammonia, transparency,
  salinity, notes.
- `sf_water_limits`: tenant default or pond, parameter, lowest, highest.
- `sf_pond_harvests` and `sf_pond_harvest_lines`: cycle, date, partial or final, linked sale; species, grade, kg,
  count, use, rate.
- The *Fish and shrimp seed* expense category.

All quantities and amounts are `NUMERIC`. All new tables get tenant keys and composite foreign keys. Every new endpoint
gets cross-tenant and permission tests.

## 13. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **K1** | Record ponds as their own records under a farm (kind, water spread area, depth, optional land parcel), not as cost objects? | **Yes** |
| **K2** | One culture cycle per pond at a time, as a cost object from preparation to final harvest, holding several species (polyculture)? | **Yes** |
| **K3** | Keep an **estimated** fish count (stocked − deaths seen − harvested), never negative, with true survival and unseen losses shown at the final harvest? | **Yes** |
| **K4** | Fingerlings and post-larvae as costs of the cycle under a new category *Fish and shrimp seed*, recorded with the stocking? | **Yes** |
| **K5** | One daily pond record (feed lines and deaths seen), with feed as one stock use to the cycle per day? | **Yes**; weekly stock uses remain possible |
| **K6** | Weight samples giving average weight, estimated biomass and estimated FCR to date, always labelled estimated? | **Yes** |
| **K7** | Water quality readings per pond (DO, pH, temperature, ammonia, transparency, salinity) with limits set only by the farmer, none pre-filled? | **Yes** |
| **K8** | Harvests (partial or final) by species and grade, sold lines as one *Fish sales* record at the pond bank, and **no fish in stock**? | **Yes** |
| **K9** | The figures in section 9: survival, unseen losses, FCR, yield and profit per acre, cost per kg, average price? | **Yes** |
| **K10** | Other inputs (lime, fertiliser, probiotics, medicine) and power through the existing stock-use and bill screens, assigned to the cycle? | **Yes** |
| **K11** | The species list in section 4, with *Other* for anything else? | **Yes** |
| **K12** | *Fish farming* available, off by default, with new permissions `AQUACULTURE_READ` and `AQUACULTURE_WRITE` granted like livestock? | **Yes** |
| **K13** | Phone: the daily pond record, water readings and weight samples (online now, offline in Phase 13)? | **Yes** |
| **K14** | The pond-cycle and water-quality reports, a printable cycle summary, and the three dashboard items in section 9? | **Yes** |
| **K15** | No overhead allocation to cycles in this phase; pond construction and aerators stay farm assets in overhead? | **Yes**, as for herds |

## 14. What Phase 10 delivers once this is approved

**Features:**
- ponds with water area and limits;
- culture cycles from preparation to harvest, with polyculture stockings and seed bills;
- the daily pond record with feed as stock use and deaths seen;
- weight samples with estimated biomass and FCR;
- water quality readings with out-of-limit flags;
- partial and final harvests by grade, sold at the pond bank;
- the figures by period and since stocking;
- reports and exports, a printable cycle summary, dashboard items;
- web screens and phone screens, in Tamil and English.

**Tests:**
- section 10 as a golden scenario, every figure asserted;
- estimated counts never negative, and unseen losses at the final harvest;
- feed corrections reverse the earlier stock use;
- a harvested cycle refuses new records and reopens when its sale is reversed;
- out-of-limit readings with farm-business and pond limits;
- FCR, survival and per-acre arithmetic, including a cycle without counts;
- permissions and the module switch;
- cross-tenant tests for every new endpoint;
- the migration on a copy of the current schema.

## 15. Implementation notes (2026-10-21)

- **Module.** The new `aquaculture` package reads finance, inventory and costing through their services.
  `ModuleBoundaryTest` (rule `onlyReportingUsesAquaculture`) allows only `reporting` to depend on it.
- **Ponds (K1).** Pond names are unique within a farm business, ignoring case. The water area is converted to acres
  when saved, for yield and profit per acre. A pond with a crop in it cannot be made inactive.
- **Cycles (K2).**
  - A cycle's cost object has the kind `POND_CYCLE`, the pond's area in acres and the start date. Overhead allocation
    still covers only crop cycles (K15).
  - The state is worked out from the records: *Preparing* without a stocking that counts, *Growing* after one,
    *Harvested* after a final harvest whose sale is posted (or that has no sale).
  - Reversing the final harvest's sale reopens the cycle. Starting a new crop in the pond in between is not prevented.
  - A harvest without a sale (home use or given away), or whose sale was reversed, can be removed.
- **Estimated counts (K3).**
  - Deaths seen are checked species by species: on every day with deaths, stocked − deaths − harvested must not go
    below zero (`DEATHS_EXCEED_STOCK`). Harvest counts above the estimate are accepted, because the stocking count
    may be wrong. The difference shows as negative unseen losses.
  - Survival and unseen losses are shown only when every harvest line of the crop has a fish count.
- **Samples (K6).** A sample without a species is a mixed sample. It applies to every species, unless a later sample
  of that species exists. The estimated FCR uses the feed up to the latest sample date ÷ (estimated kg then + kg
  harvested by then − kg stocked).
- **Feed in figures (K5, K9).** Feed is the stock used on the cycle from items in the *Animal feed* category, so weekly
  stock uses count too, and lime or fertiliser does not.
- **Harvest charges (K8).** The buyer's deductions (commission, transport or ice) are a separate bill to the same buyer,
  or paid from the same account, assigned to the cycle. Finance sale lines are positive amounts, so they cannot be lines
  of the sale itself. The net on the buyer's balance is what was received. Reversing the sale does not reverse the
  charges bill; it is reversed by hand.
- **Water (K7).** A pond's own limit replaces the farm business's limit for that parameter only. The dashboard counts
  ponds with a reading outside its limits on the farm date (Asia/Kolkata).
- **Phone (K13).** Under *More → Ponds*. A feed entry from the phone replaces that feed's kg for the day, and deaths seen
  are added to those already recorded. The day's other feed lines and notes are kept.

