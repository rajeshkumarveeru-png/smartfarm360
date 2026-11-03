# ADR 0016 — Plans, trial and grace: never hold the books hostage

- Status: Accepted (2026-10-27), decisions P1–P3 and P8–P11 of the [billing design](../platform/billing.md).

## Decision

- **A plan is a list of limits** (Phase 14's `PlanLimits`). There are `FREE`, `GROWER` and `ESTATE`, plus
  `FREE_LEGACY`, which every farm business created before Phase 15 moved to, with the limits `FREE` had then. `FREE`
  is smaller for new businesses: 5 members and 3 farms. A limit a plan does not list is unlimited.
- **The farm business's `plan_code` is the subscription's effective plan**, and `PlanLimits` reads only that. The
  effective plan is:
  1. a comp, until its date;
  2. otherwise the subscribed plan while it is in trial, paid, cancelled but still paid for, or past due within grace;
  3. otherwise the plan the business had before subscribing (`FREE`, or `FREE_LEGACY`).
- **States:** `TRIAL → ACTIVE ⇄ PAST_DUE → LAPSED`, plus `CANCELLED` (until the paid period ends) and `ENDED`. Every
  change is in `sf_subscription_events` and the business's audit log. A daily job ends trials, sends the reminders,
  lapses after grace, ends cancelled plans and comps, and tells the gateway about announced prices.
- **Trial:** 30 days of `GROWER` for a new farm business, once per user (`sf_trials_used`, kept after deletion).
- **Grace:** 14 days after the first failed renewal, with an email at once and one 3 days before the end.
- **Never hold the books hostage.** Falling back to a free plan only lowers the limits for new additions
  (`PLAN_LIMIT_REACHED`). Nothing recorded is hidden, locked or read-only because of billing, and export and deletion
  always work. There is no suspension for non-payment: suspension stays for abuse and legal reasons.
- **Changes:** a higher plan for the same period starts once its pro-rata part is paid. A lower plan, or another
  period, starts at the next renewal. Cancelling stops renewals at the end of the paid period. Asking for a farm
  business's deletion ends its subscription at once; cancelling the deletion does not restore it.
- **Refunds** are made only by a platform administrator, with a reason, through the gateway, and always with a credit
  note.

## Consequences

- No current user loses anything: early businesses keep their limits unless they subscribe and later lapse, in which
  case they fall back to `FREE_LEGACY` again.
- A business that lapses with more farms or members than `FREE` allows keeps them all and can use them; it only cannot
  add more.
- Switching between monthly and yearly is not pro-rated: it waits for the renewal.
