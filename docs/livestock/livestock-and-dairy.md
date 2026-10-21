# Livestock and dairy design (Phase 8)

- Status: **Approved** (2026-10-21, I1–I16 as recommended) and implemented on `feature/phase-8-livestock`; see section 14.
- Builds on:
  - the approved [finance posting rules](../finance/posting-rules.md): produce at nil value (D3), breeding and milch
    animals as farm assets (D8), *Milk sales* and *Livestock sales* on 4000, *Animal feed* and *Veterinary* on 5000;
  - the [stock design](../inventory/stock-movements.md): farm-produce records, stock use against a cost object,
    wastage;
  - the [crop design](../crops/crop-management.md): cost objects and activity reports;
  - the [workforce and assets design](../workforce/workforce-and-assets.md): attendance by activity, the asset register,
    depreciation runs and disposal;
  - the [dashboard and reports design](../reports/dashboard-and-reports.md): tiles, exports and the report catalogue.
- Decisions marked **I1–I16** need an answer. Each has a recommendation.

## 1. What this phase fixes

- **There is no livestock in the app.** The *Dairy* module is a switch marked unavailable. The phone's Livestock tab is a
  placeholder that says "coming soon".
- **Milk is only money.** A farmer can record a *Milk sales* sale and *Animal feed* bills, but the app does not know:
  - how many cows there are, or which are milking, dry, pregnant or due to calve;
  - how many litres were milked or where they went;
  - what a litre of milk costs to produce.
- **Milch animals sit in the asset register without a life.** Phase 6 registered breeding and milch animals as assets
  but left their depreciation to this phase (G10). Nothing records a cow's death or sale against its register entry.
- **Herd events are not recorded.** Health treatments, vaccinations, heat, insemination and calving go unrecorded, so
  there are no reminders for pregnancy checks, dry-off or due calvings.

## 2. Principles

1. **Animals are individuals, costs belong to groups.** Each cow or buffalo has its own record: tag, breeding and health
   history. Costs (feed, fodder, medicine, vet visits, labour, depreciation) go to the animal's **group** (for example
   *Milking herd*, *Dry cows*, *Heifers*, *Calves*), which is a cost object. Small farms do not cost individual animals,
   and group costs give a reliable cost per litre.
2. **Milk follows the stock rules.** Milk is farm produce at nil value (D3). Each day's milk comes into stock once:
   - it goes out as a sale, home use, feeding calves or discarded milk;
   - the stock balance of milk is always explained;
   - revenue is recognised only when it is sold.
3. **Record once.**
   - Feed given to a group is a stock use against that group.
   - A vet bill is a bill tagged to the group.
   - A bought cow is registered on the bill that bought it (G8), and her death or sale is one disposal record.
   - A calving creates the calf.
4. **Dates drive the reminders.** Insemination gives the pregnancy-check and expected-calving dates. Calving gives the
   lactation. A treatment gives the date its milk withdrawal ends. The app works these out from the farmer's dates and
   never asks for them twice.
5. **No veterinary advice.** The app records what the farmer or vet did and reminds of dates the farmer set or that come
   from standard periods (I10). It does not diagnose or recommend treatment.

## 3. Animals and groups (I1–I4)

**Animals (I1).**
- Species in this phase: cow and buffalo. Goats and sheep come in Phase 9 and reuse this core with herd-level records.
- Each animal record has:
  - tag number (unique in the farm business);
  - name (optional);
  - species, breed (free text), sex;
  - birth date (or approximate age);
  - how she came: born on the farm, bought or received;
  - dam and sire (an animal, or a bull or semen name);
  - group, farm, notes.
- Status is *Active*, *Sold*, *Died* or *Culled*. Female cattle also show a breeding state, worked out from events: open,
  inseminated, pregnant, dry, milking.

**Groups (I2).**
- Groups are cost objects of a new kind, *Animal group*, with a farm and a name.
- Moving an animal between groups is dated. The history is kept, so a cost or a litre belongs to the group the animal
  was in that day.
- Suggested starting groups: *Milking herd*, *Dry cows*, *Heifers*, *Calves*, *Bulls*.

**Bought and home-bred animals (I3).**
- A bought cow or buffalo is a farm asset (D8). She is registered in the asset register on the bill that bought her, and
  the animal record links to that register entry.
- An animal born on the farm has **no book value** (D3: farm-produced at nil). She is in the herd register but not the
  asset register.

**Depreciation of milch animals (I4).**
- Bought milch and breeding animals are depreciated straight-line over a productive life, down to an expected cull
  value, using the existing monthly depreciation runs.
- Suggested defaults are 72 months, with a cull value the farmer enters (for example ₹24,000 on a ₹60,000 cow). This
  settles G10.
- Animals bought for resale or fattening are not assets: they are stock (*Resale* items) and are not depreciated.

## 4. Milk (I5–I8)

**Recording (I5).**
- Milk is recorded per **session** (morning or evening) and per **group**, in litres to two decimals.
- Per-animal yields are optional. When they are entered, the group total is their sum. When only a group total is
  entered, animal yields stay blank.
- Most farmers record a group total; a farm that milk-records cows can enter each one.

**Into stock (I6).**
- At the end of each day the day's milk becomes one farm-produce record into the tenant's *Milk* stock item, at nil
  value and tagged to the group.
- Recording or correcting a session changes that day's record:
  - while the day's milk has not yet been used or sold, the record is replaced;
  - once it has, a correction is a new adjusting record, and the earlier one is never edited (principle 4 of the stock
    design).

**Where milk goes (I6).** Every litre leaves stock as one of these:
- **Sale**: a sale with a *Milk* item line (to a dairy society, a vendor or cash customers).
- **Home use**: a stock use to *Farm overhead* (nil value).
- **Fed to calves**: a stock use to the *Calves* group (nil value). This records the litres; it is not a cost, because
  milk carries no value (D3).
- **Discarded**: wastage with a reason, for example milk withheld after a treatment (I9).

**Quality (I7).**
- Fat % and SNF % can be recorded per session or per collection slip. Reports show volume-weighted averages.
- The app does **not** calculate the price from a fat/SNF rate chart. The sale records the litres and the amount on the
  society's bill, and reports show the average price per litre.

**Selling to a dairy society (I8).**
- A society that pays every 10 or 15 days is a customer.
- Each payment period is one sale with the litres and the bill amount, dated at the end of the period. It is linked to
  that period's milk, and the payment is matched to it as usual.
- Daily cash sales to households can be one sale per day or per week.

## 5. Feed, health and breeding (I9–I12)

**Feed.**
- Bought feed (concentrate, oil cake, mineral mixture) is stock. Feeding is a stock use to a group (existing stock
  issue, H4 of the stock design). Weekly entries are fine.
- Green fodder bought for immediate use, and grazing fees, are bills to the group.
- Fodder grown on the farm and fed to the herd is an internal transfer (P17), which arrives in Phase 11. Until then its
  growing costs stay with the crop.
- There is no ration calculator.

**Health (I9).** Events are per animal or for a whole group:

| Field | Notes |
|---|---|
| Kind | Treatment, vaccination, deworming, or other |
| Date | |
| What it was for | Free text, for example "mastitis" or "FMD vaccine" |
| Medicine | Optional: from stock (a stock use to the group) or a vet bill tagged to the group |
| Done by | Vet name, optional |
| Milk withdrawal | Days. The animal shows **"milk withheld until <date>"**. The milk screen warns while any milking animal is withheld, so her milk is recorded as discarded, not sold. |
| Next due | Optional date (booster, next deworming). Appears in the reminders. |

**Breeding (I10).**
- Events: heat seen, insemination (AI with bull or semen name and the technician, or natural service), pregnancy check
  (positive or negative), calving, abortion, dry-off.
- From an insemination the app works out:
  - a pregnancy check due 60–90 days later (shown from day 60);
  - expected calving at 283 days for a cow and 310 days for a buffalo;
  - dry-off due 60 days before expected calving.

  The farmer can change any of these dates.
- A negative pregnancy check, or a new heat after insemination, clears the expected dates.

**Calving and lactation (I11).**
- A calving records the date, the outcome (live or stillborn) and the calves. Each live calf becomes a new animal: born
  on the farm, nil value, the dam linked, placed in *Calves*.
- Calving starts a new **lactation** (number 1, 2, …) and moves the cow to her milking group if she is not already
  there.
- Dry-off ends the lactation and moves her to *Dry cows*.
- Days in milk, lactation yield (when per-animal yields are recorded) and calving interval are worked out from these
  dates.

**Labour and other costs (I12).**
- Attendance can choose an animal group as "worked on", like a crop (G3), so dairy labour is a cost of the herd.
- Electricity, rent and other farm overhead are **not** shared out to herds in this phase. They stay in *Farm overhead*,
  and herd reports show the herd's own costs (I13).

## 6. Leaving the herd (I14)

| What happened | Bought animal (in the asset register) | Home-bred animal (nil value) |
|---|---|---|
| Sold | Asset disposal *Sold* with proceeds: gain to 4900 or loss to 5980 (G11) | Sale with category *Livestock sales* (4000) for the price |
| Died | Asset disposal *Scrapped*: book value is a loss (5980) | No accounting entry; the register records death and cause |
| Culled and sold | As sold | As sold |
| Insurance claim received | Receipt with category *Other income* (4100), linked to the animal | Same |

- The animal's status and exit date are set by the same record.
- Reversing the disposal or sale returns the animal to *Active*.

## 7. Worked example (July 2026)

A farm business with three animals:

| Animal | Details | Group |
|---|---|---|
| Lakshmi | Cow, bought 1 April 2026 for ₹60,000; 72 months; cull value ₹24,000. Depreciation ₹500 a month from May. | Milking herd |
| Ganga | Cow, home-bred, nil value | Milking herd |
| Nandhini | Calf, home-bred | Calves |

The farm has one stock item, *Cattle feed* (kg, bought at ₹28/kg), and a *Milk* item (litre).

**What happened in July:**
- Lakshmi gave 10 litres a day and Ganga 8 litres a day, for 31 days: **558 litres**.
- Home use was 1 litre a day: 31 litres.
- Nandhini was fed 2 litres a day: 62 litres.
- **20 July:** Lakshmi was treated for mastitis with a 4-day milk withdrawal. Her milk from 20 to 23 July, 40 litres, was
  discarded.
- 425 litres were sold to the village dairy society in three bills totalling ₹16,150 (₹38 a litre). The society paid
  ₹12,000 by 31 July.
- 500 kg of cattle feed was bought for ₹14,000. Of it, 400 kg (₹11,200) was fed to the Milking herd and 50 kg (₹1,400) to
  Calves.
- Green fodder bill, ₹3,000, to the Milking herd.
- Bills to the Milking herd: deworming ₹600, the mastitis antibiotic ₹450, and AI for Ganga on 10 July ₹300.
- Depreciation run for July: Lakshmi ₹500.

**Expected results:**

| Figure | Value |
|---|---|
| Milk stock, July | 558 in; out: 425 sold, 31 home use, 62 to calves, 40 discarded; balance **0** |
| Cattle feed stock at 31 July | 50 kg, ₹1,400 |
| Milking herd costs | Feed 11,200 + fodder 3,000 + veterinary 1,350 + depreciation 500 = **₹16,050** |
| Milking herd revenue | **₹16,150** |
| Milking herd own profit | **₹100** |
| Cost per litre produced | 16,050 ÷ 558 = **₹28.76** |
| Average price per litre sold | 16,150 ÷ 425 = **₹38.00** |
| Calves costs | Feed ₹1,400. The 62 litres of milk are recorded but carry no value (D3). |
| Receivable from the society | **₹4,150** |
| Lakshmi | Book value ₹58,500 (₹60,000 less May–July depreciation of ₹1,500). Milk withheld 20–23 July. |
| Ganga | Inseminated 10 July. Pregnancy check due from **8 September 2026**. Expected calving **19 April 2027**. Dry-off due **18 February 2027**. |

Veterinary is 600 + 450 + 300 = ₹1,350.

The dashboard on 31 July shows:
- "pregnancy check due" for Ganga only from 8 September;
- no withheld milk (the withdrawal ended on 23 July);
- the herd's July figures.

## 8. Reports

| Report | Contents | Permission |
|---|---|---|
| Herd register | Animals by group and status; age, lactation, breeding state, book value for registered animals. CSV and Excel. | `LIVESTOCK_READ` (book value needs `FINANCE_READ`) |
| Milk report | By day or month and group: litres produced, sold, home use, to calves, discarded, average fat and SNF. Per-animal yields where recorded. | `LIVESTOCK_READ` |
| Herd profitability (I13) | By group and period: revenue, own costs by category, profit, litres, **cost per litre produced**, average price per litre. Uses the same activity report as crops (principle 1 of the reports design). | `FINANCE_READ` |
| Breeding calendar | Due pregnancy checks, dry-offs and calvings; open cows 90+ days after calving; calving intervals | `LIVESTOCK_READ` |
| Health record | Treatments, vaccinations and dewormings by animal or group; next due; withdrawals in force | `LIVESTOCK_READ` |
| Animal card | One animal's history: groups, milk, health, breeding, lactations, calves; printable | `LIVESTOCK_READ` |

**Dashboard tiles** (attention list):
- milk withheld today;
- pregnancy checks, dry-offs and calvings due within 7 days;
- vaccinations or dewormings due;
- today's milk not recorded by 10 a.m. for the morning session.

All reports export as CSV and Excel and print (H7, H8).

## 9. Screens

**Web.** A new *Livestock* section:
- **Herd:** list, add an animal, move between groups.
- **Animal page:** card, events, milk.
- **Milk:** the day's sessions by group, with per-animal entry, quality, and where the milk went.
- **Breeding and health:** event entry and the calendar.
- **Groups.**
- **Reports.**

**Phone (I15).**
- The Livestock tab replaces the placeholder.
- **Milk entry by session:** the group total, or tap each animal and type litres.
- Where today's milk went.
- **Quick health and heat notes:** heat seen, treatment given with the withdrawal days.
- The animal list with withdrawal and due flags.
- Online now; offline in Phase 13.

## 10. Modules and permissions (I16)

- The **Dairy** module becomes available as *Livestock and dairy*. It is on for new farm businesses and can be switched on
  for existing ones. It is off by default for existing tenants that have no animals, so their menus do not change.
- New permissions:
  - `LIVESTOCK_READ`
  - `LIVESTOCK_WRITE`: animals, milk, health and breeding events.

  Money still needs the finance permissions:
  - buying an animal needs `FINANCE_WRITE`;
  - a disposal needs `FINANCE_MANAGE`, as in Phase 6.
- Grants:

| Role | Permissions |
|---|---|
| Owner, farm admin, farm manager | Both |
| Field supervisor | Both |
| Accountant | `LIVESTOCK_READ` |
| Viewer | `LIVESTOCK_READ` |
| Worker | `LIVESTOCK_WRITE` for milk entry, plus `LIVESTOCK_READ` limited to the herd list (no book values) |

## 11. Data model (for implementation)

One new forward-only migration.

**Tables:**
- `sf_animals`: tenant, tag, name, species, breed, sex, birth date, origin, dam, sire text, farm, status, exit date and
  cause, `asset_id`.
- `sf_animal_group_moves`: animal, group cost object, from date.
- `sf_milk_sessions`: tenant, date, session, group, litres, fat, SNF, document id of the day's produce record.
- `sf_milk_yields`: session, animal, litres.
- `sf_animal_events`: animal or group, kind, date, details, withdrawal end, next due, linked document.
- `sf_lactations`: animal, number, calving date, dry-off date.

**Changes to existing tables:**
- Cost object kind `ANIMAL_GROUP`.
- A *Milk* system produce item per tenant, created when the module is switched on.
- `animal_id` on finance documents, so a sale, disposal or insurance receipt names the animal.

**Rules for every new table:**
- `(tenant_id, id)` unique keys and composite foreign keys.
- Every new endpoint gets cross-tenant and permission tests.

**Module boundaries:**
- A new `livestock` module. It reads finance, inventory, costing and assets through their services.
- Nothing depends on `livestock` except `reporting`.

## 12. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **I1** | Keep an individual record for each cow and buffalo (tag, breed, dam and sire, status), with goats and sheep left to Phase 9 as herds? | **Yes** |
| **I2** | Cost by animal group (a new cost object kind), with dated group moves, rather than by individual animal? | **Yes** |
| **I3** | Bought milch and breeding animals in the asset register (D8); home-bred animals at nil value (D3)? | **Yes** |
| **I4** | Depreciate bought milch and breeding animals straight-line over a productive life to a cull value (default 72 months), settling G10? | **Yes** |
| **I5** | Record milk per session and per group, with per-animal yields optional? | **Yes** |
| **I6** | Bring each day's milk into stock as one nil-value produce record, and record every litre's exit (sale, home use, calves, discarded)? | **Yes** |
| **I7** | Record fat and SNF, but not calculate prices from a society's rate chart? | **Yes**; the bill amount is what counts |
| **I8** | A dairy society is a customer, with one sale per payment period? | **Yes** |
| **I9** | Health events with milk withdrawal (and a warning while milk is withheld) and next-due reminders, without diagnosis or advice? | **Yes** |
| **I10** | Work out pregnancy check (60–90 days), expected calving (283 days cow, 310 buffalo) and dry-off (60 days before) from insemination, editable? | **Yes** |
| **I11** | Calving creates the calves (nil value, dam linked) and starts a lactation; dry-off ends it? | **Yes** |
| **I12** | Attendance can charge labour to an animal group; farm overhead is not shared out to herds in this phase? | **Yes**; allocation by animal-days can follow later |
| **I13** | Herd profitability on the group's own costs, with cost per litre produced as the headline figure? | **Yes** |
| **I14** | Exits as in section 6 (asset disposal for registered animals, *Livestock sales* for home-bred, insurance as other income)? | **Yes** |
| **I15** | Phone: milk entry by session, quick health and heat notes, and the animal list (online now, offline in Phase 13)? | **Yes** |
| **I16** | Make *Livestock and dairy* available, on for new farm businesses and off for existing ones until switched on, with the permissions and grants in section 10? | **Yes** |

## 13. What Phase 8 delivers once this is approved

**Features:**
- the herd register with groups and dated moves;
- bought animals linked to the asset register, with depreciation;
- milk by session, group and animal, with quality;
- daily milk into stock, with every exit recorded;
- dairy society sales;
- feed to groups;
- health events with withdrawals and reminders;
- breeding events with worked-out dates, calvings creating calves, and lactations;
- labour to groups;
- exits with the right accounting;
- herd profitability and cost per litre;
- the milk report, breeding calendar, health record, animal card and herd register, all with exports;
- dashboard tiles;
- web screens and the phone Livestock tab, in Tamil and English.

**Tests:**
- section 7 as a golden scenario;
- milk stock always explained, and corrections after milk has been used;
- withdrawal warnings;
- breeding date arithmetic for cow and buffalo, including a negative check and a re-insemination;
- calving creating calves and lactations;
- group moves splitting costs and litres by date;
- every exit's postings and its reversal;
- depreciation of a bought cow to her cull value;
- permissions (including the worker's limited view);
- cross-tenant tests for every new endpoint;
- the migration on a copy of the current schema.

## 14. Implementation notes (2026-10-21)

- **Module.**
  - The new `livestock` package reads finance, inventory, costing and assets through their services.
  - `ModuleBoundaryTest` (rule `onlyReportingUsesLivestock`) allows only `reporting` to depend on it.
  - The rule that kept assets private was widened so livestock can register bought animals.
- **Links to finance records.** Finance documents have no `animal_id` column, unlike section 11 planned. The links live on
  the livestock side instead:
  - `exit_document_id` on the animal (its sale or asset disposal);
  - `document_id` and `stock_document_id` on events (vet bills, medicine use, insurance receipts).

  An animal whose exit record is reversed shows as *In the herd* again: its status is worked out from that record.
- **Milk into stock (I6).**
  - Saving a session keeps the day's produce records equal to the sessions for that group (table `sf_milk_postings`).
  - More milk adds a produce record for the difference.
  - Less milk reverses the day's records and posts the new total.
  - The reversal is refused once the milk has been sold or used; the error is `MILK_ALREADY_USED`, and the user records
    the difference as discarded instead.
  - The *Milk* item (litre, farm produce, *Milk sales*) is created on first use, or an existing produce item named Milk
    in litres is adopted.
- **Selling milk (I8).**
  - `POST /livestock/milk-sales` records one sale per bill period, with a Milk item line assigned to the group, so
    revenue and the price per litre belong to the herd.
  - A milk sale entered on the finance screens without an activity goes to farm overhead.
- **Depreciation (I4).**
  - Breeding and milch animals with a productive life are now depreciated to their cull value by the existing monthly
    runs. Without a life they are still not depreciated.
  - A move updates the asset's cost object. Depreciation therefore goes to the group the animal is in **when the run is
    posted**, not the group it was in during the month. Posting runs each month keeps them in step. This limitation is
    covered by a test.
- **Worker role (section 10).**
  - Workers get `LIVESTOCK_READ` and `LIVESTOCK_WRITE`.
  - Book values need `FINANCE_READ`, so workers see the herd list without them; no separate limited permission was
    needed.
  - Selling or writing off a registered animal needs `FINANCE_MANAGE`.
  - A milk sale, a vet bill or buying an animal needs `FINANCE_WRITE`.
- **Herd profitability (I13).** Each group's revenue and costs come from the income statement for its cost object.
  Litres produced come from the sessions. The average price per litre comes from the Milk sale lines assigned to the
  group.
- **Calving.** A second calving within 200 days of the last is refused (`CALVING_TOO_SOON`). A calving that added calves
  cannot be deleted.
- **Dashboard.** Livestock adds four "needs attention" items:
  - milk withheld;
  - breeding events due within 7 days;
  - health events due within 7 days;
  - the morning's milk not recorded, after 10 a.m. India time on the current day.
- **Phone.** The Livestock tab is online only, as decided; offline arrives in Phase 13.
