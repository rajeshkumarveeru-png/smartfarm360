# ADR 0008 — Localization (Tamil and English)

- Status: Accepted (2026-10-19). Tamil terminology reviewer: **not yet assigned**.

## Decision

- One translation catalog per language (`en`, `ta`) shared by web and mobile, keyed by stable identifiers.
  Web uses `i18next`/`react-i18next`; mobile uses the same JSON catalogs.
- The backend returns machine-readable error codes and field names (RFC 7807 problem details); clients translate them.
  Stored data uses canonical codes (`ACRE`, `EXPENSE`), never translated labels.
- Numbers, dates and currency use `Intl` formatting with the user's locale (`en-IN`, `ta-IN`); rupee amounts are
  formatted from decimal strings, not floats.
- The user's language preference is stored on the account and switchable at runtime.
- Financial and agricultural terms in Tamil must be reviewed by a fluent reviewer before a release. Until then,
  Tamil strings are marked unreviewed in the catalog.

## Consequences

- The inline `copy`/`words` dictionaries in `frontend/src/*.tsx` and `mobile/src/i18n/translations.ts` are migrated
  into the shared catalogs in Phase 2; hard-coded English strings (login screen, "Cancel", status labels) are removed.
