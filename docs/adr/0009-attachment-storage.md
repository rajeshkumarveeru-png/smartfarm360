# ADR 0009 — Attachment storage

- Status: Accepted (2026-10-19)

## Decision

- Attachments (bill photos, receipts, documents) are stored behind a `BlobStorage` interface with two
  implementations: local filesystem (default, development and single-server) and S3-compatible object storage
  (selected by configuration when hosting is decided).
- Metadata (`sf_attachment`: tenant, owner document, content type, size, SHA-256, storage key, uploader, timestamps)
  is in PostgreSQL. Storage keys are random and never contain user-supplied names.
- Uploads are validated by size limit and detected content type (allow-list: JPEG, PNG, WebP, PDF).
- Downloads go through an authorized API endpoint that checks tenant membership and permission; no public URLs.

## Consequences

- Backups must cover both the database and the blob store (documented with the hosting decision).
