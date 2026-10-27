# Billing and payments design (Phase 15)

- Status: **Approved** (2026-10-27, P1–P15 as recommended) and **built** in Phase 15. Implementation notes are in section 17.
- Builds on:
  - Phase 14 (SaaS operations): every farm business is on the `FREE` plan, plans are lists of limits
    (`sf_plans`, `sf_plan_limits`), a platform administrator can override a limit with a reason, limits only refuse new
    additions, SMTP email with English and Tamil templates, the platform console and `PlatformAudit`;
  - ADR 0002 (tenancy), ADR 0004 (accounting model), ADR 0011 (Row-Level Security) and ADR 0012 (hosting).
- Decisions **P1–P15** were approved as recommended. ADRs: [0014](../adr/0014-payment-gateway.md) (P4),
  [0015](../adr/0015-gst-invoices.md) (P6, P7), [0016](../adr/0016-plans-trial-and-grace.md) (P1–P3, P8–P11); amendments to ADR 0011 and ADR 0013.
- Prices and the legal entity's GST details are not decided here. The product owner sets them; this phase builds
  where they live and how they are charged. Every amount below is an example.
- SmartBill360 is a separate product and repository. Nothing here uses or changes it.

## 1. What this phase fixes

SmartFarm360 can run as a service (Phase 14), but it cannot earn anything:

- **Every farm business is free forever.** There are no paid plans, no trial and no way to pay.
- **Limits can only be raised by hand.** A growing farm business that needs more farms or members must ask a platform
  administrator for an override.
- **No invoices.** Indian farm businesses that pay for software need a GST tax invoice to claim input tax credit, and
  the platform must keep a numbered invoice series for its own returns.
- **Nobody knows who has paid.** There is no record of payments, failed renewals or refunds, and no way to reconcile
  with the payment gateway.

## 2. Principles

- **Never hold a farm's books hostage.** A farm business that stops paying is moved to the `FREE` limits. Nothing
  recorded is hidden, blocked, read-only or deleted because of billing; only new additions above the free limits are
  refused (`PLAN_LIMIT_REACHED`, as today). Export and deletion (Phase 14) always work.
- **Card and bank details never reach SmartFarm360.** Payment happens on the gateway's hosted checkout. We store the
  gateway's ids, never card numbers, UPI ids or bank accounts.
- **The gateway is the source of truth for money moved; SmartFarm360 is the source of truth for entitlements.**
  Webhooks change the subscription's state; a nightly reconciliation compares both and raises an alert on any
  difference.
- **Invoices are immutable.** An issued invoice is never edited or deleted. A refund or correction is a credit note.
- **Money is `NUMERIC(14,2)` in rupees** (`BigDecimal` in code), with GST worked out per line and rounded to the paisa.
- **Billing belongs to the platform, not to the farm's books.** Platform billing tables live in `platform.billing`.
  The farm's own finance module is not touched, except through the optional booking in section 10.

## 3. Plans and prices (P1, P2)

- **Three plans**, each a list of limits as in Phase 14:

  | Limit | FREE | GROWER (example ₹499/month) | ESTATE (example ₹1,999/month) |
  |---|---|---|---|
  | Active members per farm business | 25 → **5** for new businesses (P2) | 25 | 100 |
  | Farms per farm business | 20 → **3** for new businesses (P2) | 20 | unlimited |
  | Exports of the whole business | 3 a day | 3 a day | 10 a day |
  | Attachment storage (when ADR 0009's storage arrives) | 1 GB | 10 GB | 100 GB |
  | Optional modules (livestock, aquaculture, organic, processing) | all | all | all |

- **Prices are data, not code.** Each plan has a monthly and a yearly price (yearly example: 10 × monthly). Prices
  include no GST; GST is added on the invoice. A price change applies from the next renewal, with 30 days' notice by
  email.
- **Modules are not sold separately** at launch. Charging by module would make the farmer choose before they know
  what they need; limits by size are easier to explain.
- **P2 — what happens to today's FREE businesses.** The current `FREE` limits (25 members, 20 farms) were generous
  because there was nothing to pay for. Recommendation: lower `FREE` to 5 members and 3 farms **for farm businesses
  created after the release**, and keep every existing business on a `FREE_LEGACY` plan with today's limits, so no
  current user loses anything.

## 4. Trial (P3)

- A new farm business starts a **30-day GROWER trial**, with no card needed.
- Seven days and one day before the end, the owner gets an email and a banner.
- At the end, without a subscription, the business moves to `FREE`. Records above the free limits stay; only new
  additions are refused.
- One trial per user (the owner who created the business), so deleting and recreating a business does not restart it.

## 5. Checkout and the payment gateway (P4, P5)

- **P4 — gateway.** Recommendation: **Razorpay Subscriptions**, behind a `PaymentGateway` interface with a fake for
  tests (like `EmailSender`). Razorpay supports UPI AutoPay, cards, net banking and e-mandates in INR, which match how
  Indian farm businesses pay; a second gateway (for example Cashfree) can be added behind the same interface.
- **How an owner subscribes (web only):**
  1. The owner opens *Settings → Plan and billing*, picks a plan and monthly or yearly, and enters billing details
     (legal name, address, state, optional GSTIN).
  2. SmartFarm360 creates the gateway subscription and opens the hosted checkout.
  3. The owner authorises the mandate on the gateway's page.
  4. The gateway's webhook (`subscription.activated`, `payment.captured`) activates the plan and issues the invoice.
     The browser's return page only shows "waiting for confirmation"; it never activates anything itself.
- **Webhooks** (`POST /api/v1/billing/webhooks/razorpay`):
  - the signature is checked with the webhook secret (environment variable) before anything is read;
  - each event id is stored once, so a repeated delivery changes nothing (the same idempotency idea as ADR 0010);
  - events can arrive out of order, so each one moves the subscription only forward in its state machine;
  - the endpoint answers quickly and does the work in the same transaction as the event record.
- **Who can pay:** only a `TENANT_OWNER`, with a new permission `BILLING_MANAGE`. Accountants get `BILLING_VIEW`
  (invoices and payments, no changes).
- **P5 — billing currency and customers.** INR only, and Indian billing addresses only at launch. A foreign address
  would need export-of-services invoices (zero-rated under LUT); that is left for later.

## 6. Invoices and GST (P6, P7)

- **P6 — GST tax invoices from SmartFarm360.**
  - The seller is the platform's legal entity (GSTIN, legal name, address and state from configuration, checked by
    `ProductionSettings` in `prod`).
  - **Place of supply** is the customer's billing state. Same state as the seller: CGST 9% + SGST 9%. Another state:
    IGST 18%. The rate is data (`sf_tax_rates`), not code.
  - The SAC code is 998314 (software as a service), from configuration.
  - **Numbering:** one gapless series per financial year, for example `SF/2026-27/000123`. Numbers are taken in the
    same transaction as the invoice, from a counter row locked for update, so a failed transaction leaves no gap.
  - A B2B invoice shows the customer's GSTIN; without one it is a B2C invoice.
  - The invoice is issued when the payment is captured, never before.
  - **Credit notes** (refunds, corrections) have their own gapless series and refer to the invoice.
  - Invoices and credit notes are PDFs in English (the legal copy), with a Tamil summary page. They are kept 8 years,
    as GST law requires, even after the farm business is deleted (Phase 14 keeps only a deletion record; billing
    documents join it, ADR 0013 is amended).
- **P7 — e-invoicing (IRN).** Not needed at launch: it applies only above the e-invoicing turnover threshold. The
  invoice model keeps space for an IRN and QR code so it can be added without changing issued invoices.

## 7. Renewals, failed payments and grace (P8, P9)

- The gateway charges each renewal through the mandate. A captured payment issues the next invoice and extends the
  paid-until date.
- **A failed renewal:**
  1. The subscription becomes `PAST_DUE`. The gateway retries on its own schedule.
  2. The owner gets an email and a banner with a link to update the payment method.
  3. **P9 — grace: 14 days.** During grace the plan's limits stay.
  4. After grace without payment, the subscription becomes `LAPSED` and the business moves to `FREE` limits.
     Nothing is hidden or locked (section 2). Paying later reactivates the plan from that day.
- **P8 — no suspension for non-payment.** Suspension (Phase 14) stays for abuse and legal reasons only, with a reason.
- **States:** `TRIAL → ACTIVE ⇄ PAST_DUE → LAPSED`, plus `CANCELLED` (ends at the paid-until date) and `ENDED`. Each
  change is recorded in `sf_subscription_events` and in the business's audit log.

## 8. Changing plans, cancelling and refunds (P10, P11)

- **Upgrade** (for example GROWER to ESTATE, or monthly to yearly): takes effect at once. The unused part of the
  current period is credited against the new price (pro-rata by days), and the difference is charged now with its
  own invoice.
- **Downgrade:** takes effect at the next renewal, so nothing paid for is lost. If the business is above the lower
  plan's limits, the owner is shown which limits will refuse new additions; nothing is removed.
- **Cancel:** stops the mandate; the plan stays until the paid-until date, then the business moves to `FREE`.
- **P10 — refunds.** Recommendation: no automatic refunds for a part-used period. A platform administrator can refund
  a payment (fully or partly) from the console with a reason. That issues a credit note and calls the gateway's refund.
- **P11 — deletion with a paid plan.** Requesting deletion of a farm business cancels its subscription at once (no
  refund unless an administrator gives one). Cancelling the deletion does not restore the subscription.

## 9. The platform console (P12)

- New *Billing* tab: subscriptions by state, payments, failed renewals, invoices and credit notes, and the
  reconciliation report.
- Actions, each with a reason and recorded in `PlatformAudit`:
  - extend a trial or grace;
  - give a plan free for a period (a **comp**, for example for a pilot farm), shown on invoices as a 100% discount;
  - refund (section 8);
  - re-send an invoice email.
- **Limit overrides stay** (Phase 14) and take precedence over the plan.
- **P12 — reconciliation.** A nightly job lists the gateway's payments and subscriptions for the last 3 days and
  compares them with ours. A payment we have not recorded, or a state that differs, is listed in the console and sent
  as an alert email. The job never changes anything on its own; an administrator replays the missing event.
- Platform administrators still never see farm books (Phase 14).

## 10. The farm's own books (P13)

- **P13 — book the subscription in the farm's finance?** Recommendation: **offer, do not force.** On each paid
  invoice, the owner can press *Record in my books*. That creates a supplier bill (supplier "SmartFarm360", category
  *Software and subscriptions*, GST input from the invoice) through the existing finance services, with the invoice
  PDF attached when attachment storage exists. Nothing is booked automatically, because many farm businesses keep
  their books elsewhere.

## 11. The phone app (P14)

- **P14 — no buying in the phone app.** Google Play requires its own billing for digital purchases inside the app,
  with its own fees. The phone shows the plan, the trial or grace banner, and a sentence that the plan is managed on
  the web (no link to a payment page, which Play also restricts).
- The phone handles `PLAN_LIMIT_REACHED` as today.

## 12. Security, privacy and tests (P15)

- **Secrets:** the gateway key, secret and webhook secret come from environment variables; `ProductionSettings`
  refuses to start `prod` without them or with test-mode keys.
- **Tenancy:** subscriptions, invoices, payments and billing details carry `tenant_id`, get the forced RLS policy
  (ADR 0011) and cross-tenant negative tests. The webhook runs in the `all` scope and finds the business by the
  gateway's subscription id, never by an id in the payload's notes.
- **Billing details** (name, address, GSTIN) are personal data under the DPDP Act: included in the owner's data
  export, and anonymised with the account where the law allows. Issued invoices keep them for 8 years (legal
  obligation), which the privacy notice must say.
- **P15 — tests.** The fake gateway produces signed webhooks for every path: activation, renewal, failure and grace,
  lapse, upgrade with pro-rata, downgrade, cancel, refund, repeated and out-of-order events, a bad signature, and the
  invoice numbering under concurrent payments (no gaps, no duplicates). Before release, one end-to-end run on the
  gateway's test mode on staging, recorded in PROGRESS.

## 13. Worked example (November 2026)

Murugan's farm business in Erode (Tamil Nadu) was created on 1 November 2026. Prices are examples.

1. 1 November: a 30-day GROWER trial starts. Murugan adds 6 members and 4 farms.
2. 24 November: a trial reminder email and banner.
3. 28 November: Murugan subscribes to GROWER yearly (₹4,990) with GSTIN 33ABCDE1234F1Z7. UPI AutoPay is authorised;
   the webhook activates the plan.
4. Invoice `SF/2026-27/000041`: ₹4,990.00 + CGST 9% ₹449.10 + SGST 9% ₹449.10 = **₹5,888.20** (the seller is also in
   Tamil Nadu). He presses *Record in my books*; a supplier bill appears in his finance module.
5. 28 November 2027: the renewal fails (insufficient balance). The subscription is `PAST_DUE`; Murugan is emailed.
6. 12 December 2027: grace ends without payment. The business moves to `FREE` (5 members, 3 farms). His 6 members
   and 4 farms stay; he cannot add a seventh member or a fifth farm.
7. 15 December 2027: he pays. GROWER is active again from 15 December 2027 to 14 December 2028, with a new invoice.

## 14. Data and server changes

- **Migration `017-billing.yaml`** (new changesets from 058; nothing applied is edited):
  - `sf_plans`: add `FREE_LEGACY`, `GROWER`, `ESTATE`; existing businesses move to `FREE_LEGACY` (P2). Plan prices in
    `sf_plan_prices` (plan, period, amount, valid from).
  - `sf_subscriptions` (tenant, plan, period, state, trial end, paid until, gateway subscription id, cancel at period
    end), `sf_subscription_events`.
  - `sf_billing_profiles` (tenant, legal name, address, state code, GSTIN).
  - `sf_payments` (tenant, gateway payment id, amount, state, captured at), `sf_gateway_events` (event id unique,
    type, received at, processed).
  - `sf_invoices`, `sf_invoice_lines`, `sf_credit_notes`, `sf_document_counters` (series, financial year, next
    number), `sf_tax_rates`.
  - `sf_trials_used` (user id), so one trial per user.
  - RLS on every new table with `tenant_id`; billing documents are kept by the tenant deletion (ADR 0013 amendment).
- **Backend:** `platform.billing` (plans and prices, subscriptions and the state machine, checkout, webhooks,
  invoices and credit notes with PDFs, reconciliation, the trial and grace jobs); `PlanLimits` reads the
  subscription's effective plan; new permissions `BILLING_MANAGE` and `BILLING_VIEW`; error codes such as
  `BILLING_PROFILE_INCOMPLETE`, `SUBSCRIPTION_EXISTS`, `GATEWAY_UNAVAILABLE`, `WEBHOOK_SIGNATURE_INVALID`.
- **Web:** *Plan and billing* in Settings (plan cards, checkout, billing details, invoices, cancel), trial and grace
  banners, the console's *Billing* tab.
- **Phone:** plan and banners only (P14).
- **Email:** trial reminders, payment received with the invoice, renewal failed, grace ending, plan lapsed, price
  change notice; in English and Tamil.

## 15. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **P1** | Three plans (FREE, GROWER, ESTATE) as lists of limits, monthly and yearly prices as data, no per-module pricing at launch? | **Yes**; prices from the product owner |
| **P2** | Lower FREE to 5 members and 3 farms for new businesses, and keep existing businesses on `FREE_LEGACY` with today's limits? | **Yes** |
| **P3** | A 30-day GROWER trial without a card, one per user, reminders at 7 and 1 days, then FREE? | **Yes** |
| **P4** | Razorpay Subscriptions behind a `PaymentGateway` interface with a test fake; hosted checkout only; activation only by signed, idempotent webhooks? | **Yes** (ADR) |
| **P5** | INR and Indian billing addresses only at launch? | **Yes** |
| **P6** | GST tax invoices from the platform entity: CGST+SGST or IGST by place of supply, SAC 998314, gapless numbering per financial year, credit notes, PDFs, kept 8 years even after deletion? | **Yes** (ADR); GST details from the product owner |
| **P7** | No e-invoicing (IRN) at launch, with room for it in the invoice model? | **Yes** |
| **P8** | No suspension for non-payment: a lapsed business moves to FREE limits with nothing hidden or locked? | **Yes** |
| **P9** | 14 days' grace after a failed renewal, with emails and banners? | **Yes** (ADR) |
| **P10** | Upgrades at once with pro-rata credit; downgrades at renewal; cancel at period end; refunds only by a platform administrator with a reason and a credit note? | **Yes** |
| **P11** | Requesting deletion cancels the subscription at once; cancelling the deletion does not restore it? | **Yes** |
| **P12** | A console *Billing* tab with trial and grace extensions, comps and refunds (all in `PlatformAudit`), and a nightly reconciliation that alerts but never changes anything on its own? | **Yes** |
| **P13** | *Record in my books* on each paid invoice, creating a supplier bill on request, never automatically? | **Yes** |
| **P14** | No purchase in the phone app (Play billing rules); plan status and banners only? | **Yes** |
| **P15** | The fake-gateway test paths in section 12, plus one end-to-end run in gateway test mode on staging before release? | **Yes** |

**Not in this phase:** foreign customers and export invoices, e-invoicing, per-module or per-seat pricing, coupons
and referral codes, usage-based charges (for example SMS), Play billing in the phone app, and dunning by SMS.

## 16. What Phase 15 delivers once this is approved

- Paid plans, a trial and checkout on the web, with renewals, failed payments and grace handled by webhooks.
- GST tax invoices and credit notes with gapless numbering, as PDFs, emailed and listed for owners and accountants.
- Plan changes, cancellation and administrator refunds; the console *Billing* tab and nightly reconciliation.
- Optional booking of the subscription in the farm's own books.
- Migration `017-billing.yaml`, ADRs for P4, P6 and P9, and an amendment to ADR 0013.
- Tests for every path in section 12; the gates run and recorded in PROGRESS. The end-to-end run in gateway test mode
  needs staging and the gateway account, so it is recorded as not run until those exist.

## 17. Implementation notes (2026-10-27)

Built as designed, with these choices and narrowings:

- **Where things live.** `platform.billing` holds the plans and prices (`PlanCatalog`), the state machine and jobs
  (`SubscriptionService`), the webhook (`WebhookService`), invoices and credit notes (`InvoiceService`, `InvoicePdf`),
  GST rules (`Gst`), refunds and prices (`BillingService`) and the reconciliation. *Record in my books* lives in
  `finance` (`SubscriptionBookingService`), because the platform may not depend on business modules.
- **The farm business's `plan_code` is the effective plan.** `PlanLimits` did not change; the subscription writes the
  effective plan whenever it changes (ADR 0016).
- **Migration `017-billing.yaml`** (changesets 058 and 059). Changeset 058 was corrected before its first run: the
  trigger function needed its own SQL block, because splitting statements broke its body.
- **Billing documents keep `customer_tenant_id` without a foreign key**, with their own RLS policy, so the deletion
  keeps them for 8 years (ADRs 0011, 0013 and 0015 amended or added).
- **The amount charged is the price plus GST for the customer's state**, so a gateway plan is created for each amount
  the first time it is needed. Invoices are built from the captured total, so they always add up to it.
- **Narrowings:**
  - Only an upgrade to a higher plan for the same period is pro-rated (a payment link). Switching between monthly and
    yearly waits for the renewal, like a downgrade.
  - A comp charges nothing, so it issues no invoice (section 9 said it would show as a 100% discount). It is in the
    subscription events and both audit logs.
  - The invoice PDF is in English only. The PDF library cannot shape Tamil text, so there is no Tamil summary page;
    the invoice is shown in Tamil in the app and the emails are in both languages.
  - *Record in my books* creates one bill line for the invoice total, GST included, with the GST in the note. The
    finance module has no input-tax account yet.
  - The reconciliation lists a payment the gateway has and we do not. A platform administrator applies it by having
    the gateway resend the webhook; replay in the console is for events we stored that failed.
  - The fake gateway's checkout opens the return page and nothing more. Locally, a plan becomes paid only through a
    signed webhook (as in the tests).
- **Not run yet:** the end-to-end run on Razorpay test mode (it needs staging and the account), and a browser check.
