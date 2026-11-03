# ADR 0005 — Inventory movements and valuation

- Status: Accepted (2026-10-19)
- Detailed design: [stock movement design](../inventory/stock-movements.md) (approved 2026-10-20, implemented in Phase 4)

## Context

Today `sf_inventory_items.quantity_on_hand` is set directly on item creation and updated in place by purchases;
crop activities never reduce stock; average cost is rounded to 2 decimal places.

## Decision

- **Append-only `inventory_movement`** records are the source of truth: item, lot, location, signed quantity in the
  item's base unit, unit cost, movement type (receipt, issue/consumption, transfer, adjustment, wastage, return,
  opening balance, harvest receipt, production output), source document reference, cost object, idempotency key.
- An on-hand table (per item/lot/location) is updated **in the same transaction** under a row lock; a test asserts it
  always equals the sum of movements.
- **Valuation: moving weighted average**, unit cost stored at 4 decimal places (`NUMERIC(18,4)`), money at 2.
- Quantities use `NUMERIC(18,4)` with an explicit unit; a unit catalog defines dimensions (mass, volume, area, count)
  and conversion factors. Free-text units are not accepted in new tables.
- Negative stock is rejected unless a tenant setting explicitly allows it.
- Lots carry optional expiry dates for seeds, chemicals, medicines and feed.

## Consequences

- Existing item quantities become opening-balance movements during the Phase 4 migration.
- FIFO can be added later as a tenant setting if a customer needs it; it is not built now.
