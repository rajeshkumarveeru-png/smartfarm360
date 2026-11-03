# ADR 0015 — GST tax invoices and credit notes for subscriptions

- Status: Accepted (2026-10-27), decisions P6 and P7 of the [billing design](../platform/billing.md).

## Decision

- **The seller is the platform's legal entity**: name, GSTIN, address and state from `APP_BILLING_SELLER_*`, checked
  by `ProductionSettings` (a valid GSTIN, with its check digit). The SAC is 998314.
- **Place of supply is the customer's billing state.** The seller's own state pays CGST and SGST; any other state
  pays IGST. The rates are data (`sf_tax_rates`, 9 + 9 or 18 percent). Each tax is worked out on the taxable amount
  and rounded half up to the paisa.
- **An invoice adds up to exactly what was paid.** It is issued only when a payment is captured. The taxable amount is
  worked back from the captured total, each tax is worked out on it, and the taxable amount takes any rounding.
- **Gapless numbers per series and financial year** (April to March, India time): `SF/2026-27/000041` for invoices
  and `SFCN/2026-27/000001` for credit notes. The number is taken from `sf_document_counters` in the same transaction
  as the document, so a rolled-back transaction leaves no gap and two payments at once never share a number.
- **Issued documents are immutable.** A trigger refuses any change or deletion, except recording that the owner booked
  the invoice in the farm's own books. A refund is a credit note whose taxes are the refunded share of each tax, so its
  total is exactly the refund.
- **What is printed is stored.** The invoice keeps the seller's and buyer's details as they were. The PDF is drawn
  from the stored invoice each time, in English (the legal copy), with what GST rule 46 asks for.
- **Kept 8 years.** Payments, invoices, invoice lines and credit notes carry `customer_tenant_id` without a foreign key
  and without a `tenant_id`, so deleting a farm business keeps them (ADR 0013, amended). They have their own
  Row-Level Security policy, `sf_customer_isolation` (ADR 0011, amended).
- **No e-invoicing (IRN) at launch (P7).** The invoice has room for the IRN and its QR code; adding them later does not
  change issued invoices.

## Consequences

- A B2B customer with a GSTIN can claim input tax credit; without one the invoice is B2C.
- A comp (a plan given free by a platform administrator) charges nothing, so it issues no invoice.
- Foreign customers (export of services under LUT) are not supported yet: billing addresses are Indian only (P5).
- The PDF has no Tamil page: the PDF library has no Tamil text shaping. Tamil owners read the invoice in the app and in
  the email.
