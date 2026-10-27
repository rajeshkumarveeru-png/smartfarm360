# ADR 0003 — Authentication

- Status: Accepted (2026-10-19) — login identity uses the default (email + password); revisit for phone/OTP

## Context

Today the web app uses HTTP Basic and keeps the base64-encoded password in `sessionStorage`, sending it on every
request. The mobile client expects a bearer token. A bootstrap admin `admin`/`admin123` is created by default.

## Decision

- **Identity:** email + password for the first release. Phone number + OTP is a likely future requirement for
  farmers and needs an SMS provider decision; the `sf_users` model will allow adding a verified phone later.
- **Tokens:** short-lived signed JWT access tokens (about 15 minutes) issued by the backend via Spring Security's
  OAuth2 resource-server support, plus opaque refresh tokens (about 30 days) stored **hashed** in the database,
  rotated on every use, with reuse detection revoking the whole token family.
- **Clients:** web keeps the access token in memory and the refresh token in an `HttpOnly; Secure; SameSite=Strict`
  cookie scoped to the refresh path; mobile stores tokens in `expo-secure-store`.
- **Passwords:** BCrypt (existing). Rate-limit login, registration and password-reset endpoints. Registration responses
  must not reveal whether an email exists.
- **Password reset:** single-use, hashed, 30-minute tokens delivered by email. Phase 2 implements an `EmailSender`
  interface with a logging stub; a real provider is plugged in once chosen.
- **Bootstrap admin:** disabled by default; enabling it requires an explicit password via environment variable and it
  creates a *platform* administrator, not a tenant role.
- **Signing keys** come from environment/secret store, never the repository.

## Consequences

- HTTP Basic and the `sessionStorage` credential are removed in Phase 2.
- Logout revokes the refresh token family server-side.

## Amendments (Phase 14, 2026-10-22)

- **Rotation grace.** A rotated refresh token presented again within 30 seconds of its rotation, from a family that
  was not ended, gets another successor in the same family. This is another tab of the same browser refreshing at the
  same moment. Only the token's hash is stored, so the first successor cannot be handed back. Reuse after 30 seconds
  still revokes the family. (SaaS operations design, O13.)
- **Two-step sign-in.** It uses an authenticator app (TOTP) and ten single-use recovery codes. It is required for
  platform administrators, who reach the console only with a session that passed it, and optional for everyone else.
  - The password step returns a 5-minute challenge, never an access token.
  - Secrets are encrypted with `APP_MFA_ENCRYPTION_KEY`.
  - A refreshed session keeps its second step. (O6.)
- **Email verification.** New accounts verify their address before creating a farm business. Accepting an invitation
  sent to the address also verifies it. (O5.)
