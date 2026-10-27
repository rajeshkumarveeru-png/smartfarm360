# ADR 0013 — Exporting and deleting a farm business, and deleting an account

- Status: Accepted (2026-10-22), decisions O9–O11 of the [SaaS operations design](../platform/saas-operations.md).

## Decision

- **Export (O9).** An owner can export the whole farm business as one ZIP. It is made in the background, two at a time,
  at most three a day, and kept seven days. It contains:
  - for every table with a `tenant_id`: a CSV file with a BOM, for spreadsheets, and a JSON file;
  - the business itself and its members;
  - a README listing every file and its columns.

  The sync replay store and secret columns (token and password hashes, two-step secrets) are left out. Amounts stay
  exact decimal strings.
- **Deletion (O10).** An owner asks, typing the business's name. For 30 days:
  - the business is read-only, except for cancelling and exporting;
  - any owner can cancel.

  Then a daily job deletes every row of the business in one transaction, and removes its export files.
  - Deletion works from the catalog: the link tables without a `tenant_id` (role links) first, then every tenant table.
    Rows that another table still references wait for a later pass, using savepoints.
  - The journal's and stock movements' append-only guards allow a DELETE only when the job sets
    `app.tenant_deletion = 'on'` in its own transaction (changeset 057). Every other change to them is still refused.
  - Only a deletion record stays, in the platform audit log (business id, name, date, who asked).
  - Backups expire on their normal schedule, as the privacy notice says.
- **Account deletion (O11).** Anyone can delete their own account, except the only owner of an active farm business and
  a platform administrator.
  - The name, email and password are replaced: the label becomes *Former member #id*, with a unique `.invalid`
    address.
  - Sessions, two-step secrets and open links are removed, and memberships end.
  - The records the person made stay in the farm businesses' books, and the audit log shows the label.

## Consequences

- A new tenant table is exported and deleted with no change here, as long as it has a `tenant_id`.
- Keeping books for tax purposes is the owner's responsibility. The deletion request says so and offers the export.
- The append-only guarantee of ADR 0004 and ADR 0005 has exactly one exception: the deletion of a whole farm business.
