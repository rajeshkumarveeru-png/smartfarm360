# ADR 0014 — Payment gateway, hosted checkout and signed webhooks

- Status: Accepted (2026-10-27), decision P4 of the [billing design](../platform/billing.md).

## Decision

- **Razorpay Subscriptions** is the gateway, behind the `PaymentGateway` interface (`platform.billing`).
  - `RazorpayGateway` talks to Razorpay's REST API.
  - `FakePaymentGateway` moves no money and is used in development and tests. `APP_BILLING_GATEWAY` chooses between
    them; the `prod` profile refuses `fake` and Razorpay test keys (`ProductionSettings`).
  - A second gateway can be added behind the same interface.
- **Hosted pages only.** The owner pays on the gateway's checkout page (a subscription), or on a payment link (the
  pro-rata part of an upgrade). SmartFarm360 stores the gateway's ids and the amounts, never card numbers, UPI ids or
  bank accounts.
- **Only signed webhooks change a plan.** `POST /api/v1/billing/webhooks/{gateway}` is open to the internet and is
  exempt from read-only maintenance.
  1. It checks the HMAC-SHA256 signature over the exact body with `APP_BILLING_WEBHOOK_SECRET` before reading anything.
  2. Each event id is stored once in `sf_gateway_events`, so a repeated delivery changes nothing.
  3. The event is applied in the same transaction. It finds the farm business by the gateway's subscription id, or by
     our reference for a payment link, never by ids in the body's notes.
  4. A state change older than the last one applied is ignored. A captured payment is always recorded, once, by its
     gateway payment id.
  5. If applying fails, the event is kept as `FAILED` and the gateway still gets a success; a platform administrator
     replays it from the console.
- **The gateway's plan per amount.** A Razorpay plan has a fixed amount. The amount charged is the price plus GST for
  the customer's state, so a gateway plan is created the first time each (plan, period, amount) is needed and
  remembered in `sf_gateway_plans`. Price changes and plan changes tell the gateway to charge the new plan from the
  next cycle.
- **Nightly reconciliation.** It compares the gateway's captured payments of the last 3 days, and the state of every
  running subscription, with ours. It records the result, lists differences in the console and sends an alert. It never
  changes anything on its own.

## Consequences

- The browser's return page never activates a plan, so a closed tab or a forged return URL cannot give a plan away.
- Tests drive the real webhook path: the fake gateway signs events in Razorpay's format with the configured secret.
- A payment the gateway has but never delivered is found by the reconciliation; it is applied once the gateway sends
  the webhook again (from its dashboard).
- Checkout, plan changes and cancellation call the gateway inside the database transaction: if the gateway fails, the
  request fails with `GATEWAY_UNAVAILABLE` and nothing is saved. Stopping the gateway subscription when a business is
  deleted happens after the commit; a failure there shows up in the reconciliation.
- The end-to-end run on Razorpay's test mode needs staging and the account, and is part of the production-readiness
  review.
