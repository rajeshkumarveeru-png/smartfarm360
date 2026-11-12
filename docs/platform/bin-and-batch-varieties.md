# Recycle bin and batch varieties (design draft)

- Status: **Approved and partly built** (2026-11-03). See section 5 for what is built and what changed.
- Asked for by the owner:
  - a batch of birds can hold several varieties, as line items, and its count can be increased;
  - a batch can be deleted at any time;
  - every delete in the application has two levels: first a soft delete, then a permanent delete.

## 1. Today

- **Batches:** a poultry batch is one species (chicken only) with one optional *strain*. Birds are added with *Buy more
  birds* (with a bill) or *Received* (without one), on the batch's *Birds* tab. The batch has no line items, and ducks
  cannot be recorded.
- **Deleting:** deleting is uneven.
  - Farms and fields are archived: hidden, but kept for the accounts.
  - Work notes, observations and hand-entered head changes are erased.
  - Posted money records are reversed (ADR 0004).
  - Batches, herds and many other records cannot be deleted at all.

## 2. Recycle bin (two-level delete)

| # | Question | Recommended answer |
|---|---|---|
| B1 | What is level 1? | **Delete moves the item to the Bin.** It disappears from every list, report and choice, but nothing is erased. *Restore* brings it back as it was. |
| B2 | What is level 2? | **Delete forever, from the Bin.** It erases the item and everything that belongs only to it (a batch's daily records, a farm's fields), after a second confirmation that names what goes with it. |
| B3 | What can be deleted forever? | **Anything that has no money or stock records.** If an item has them (a batch with a bill, a farm with expenses), *Delete forever* is refused with the reason, and the item stays in the Bin, out of sight. Indian law requires accounts and GST invoices to be kept for 8 years, and the profit already reported must not change behind the owner's back. |
| B4 | Posted money records (bills, sales, payments)? | **Unchanged:** they are reversed, never erased (ADR 0004). A reversed record can be hidden from lists with *Hide reversed records*. Drafts that were never posted go to the Bin like anything else. |
| B5 | Who can use the Bin? | **Delete (to the Bin) and Restore:** whoever may change that kind of record today. **Delete forever:** the business owner only, with a new permission `DATA_PURGE`, given to the owner role. |
| B6 | Does the Bin empty itself? | **No.** Items stay until the owner restores or deletes them. A note shows items older than 90 days. |
| B7 | Where is the Bin? | **Settings → Bin.** It lists what was deleted, by whom and when, filtered by kind (farms, crops, batches, workers…), with *Restore* and *Delete forever*. |
| B8 | Records? | **Every delete, restore and delete-forever goes into the activity history** (`AuditService`), including what was erased. |
| B9 | Which items? | **Everything the app lets you remove,** first: farms, fields, crop cycles, crops, varieties and seasons, poultry batches, goat and sheep herds, animals, groups, workers, assets, parties, stock items and locations, work notes, observations, weather entries, health-plan items, hand-entered head changes, and draft money records. |

How it would be built:

- Deletable tables get `deleted_at` and `deleted_by`, in a new migration, with no change to existing data.
- Every list and choice leaves deleted rows out.
- A `BinService` knows, for each kind of item, its owner checks, what it owns, and how to find the money or stock records
  that block a delete forever.
- Each endpoint keeps the tenant checks and gets a cross-tenant test.

## 3. Batch varieties (line items)

| # | Question | Recommended answer |
|---|---|---|
| V1 | Which birds? | **Chicken, duck, quail and turkey** (new species). A batch can mix them. |
| V2 | What is a line? | **One variety in the batch:** species, variety name (*Country chicken*, *Kadaknath*, *Broiler*, *Muscovy duck*), heads now, and what they cost. A batch has one or more lines. |
| V3 | Adding birds | **Start batch** and **Buy more birds** take several lines at once (variety, heads, price each or total). One bill is made for the purchase, with a line per variety. *Received* (no bill) also asks for the line. |
| V4 | Head changes and sales | **Deaths, culls, counts, moves and sales choose the line.** Heads are kept per variety, and the batch total is their sum. |
| V5 | Daily record | **One daily record for the batch:** eggs and feed for the whole batch, with deaths by variety. |
| V6 | Figures and profit | **The batch's money stays one cost object,** so profit is per batch, as today. The *Birds* tab shows heads and cost per variety. |
| V7 | Existing batches | **Each becomes one line:** its species, its strain as the variety, and its heads, done by the migration. |
| V8 | Deleting a batch | **The Bin, as in section 2.** A batch with no bills or stock can be deleted forever; otherwise it stays in the Bin, and its money stays in the accounts. |

## 4. Order of work

1. **The Bin**, with batches, farms, fields, crop cycles and work notes first, then the remaining kinds.
2. **Batch varieties.**

Each step gets backend, web and tests, and is recorded in PROGRESS.

## 5. As built (2026-11-03)

- **The Bin (B1-B8)** is built for farms, fields, crop cycles, poultry batches, goat and sheep herds, and work notes.
  Delete moves an item to *Settings → Bin*; the owner can restore it or delete it forever. Delete forever is refused
  while money or stock records refer to the item, and the item stays in the Bin.
- **B9, the other kinds** (workers, assets, parties, stock items and locations, observations, weather entries,
  health-plan items, crops, varieties and seasons, and draft money records) are **not built yet**: they keep their
  current remove or deactivate.
- **Hand-entered head changes** are removed directly, as before. A bought or sold entry is undone by reversing its bill,
  because posted money is never erased (B4).
- **Batch varieties (V1-V4, V6-V8)** are built as designed. Variety names are free text, with common names offered
  (Sonali, Kadaknath, Peruvidai, Aseel, Muscovy, Khaki Campbell and others).
- **V5 changed at the owner's request:** the daily record takes deaths, culls, **eggs laid and a rate per egg for each
  variety**. Cracked and broken eggs stay for the whole batch, and good eggs are those laid less the cracked and broken.
  The batch's figures add *Eggs laid, at your rates* and *Profit counting eggs at your rates*. That profit counts the
  eggs at the owner's rates in place of egg sales, so eggs are not counted twice; it is shown only with finance access.
  The table is `sf_batch_day_lines` (changeset 062).
