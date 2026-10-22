# Mobile offline design (Phase 13)

- Status: **Draft for product-owner review** (2026-10-22). No Phase 13 code is written until this is approved.
- Builds on:
  - the phone app as it stands after Phase 12: money quick entries already wait on the phone when offline and are retried
    with their entry id as the idempotency key; every other phone form is online only;
  - server idempotency for finance records, crop activities and harvests (`Idempotency-Key`);
  - ADR 0002 (tenant isolation) and ADR 0005 (stock moves only through movements): the server stays the only place where
    records are checked and posted.
- Decisions marked **N1–N15** need an answer. Each has a recommendation. **N4** and **N7** change the server and will get
  an ADR once approved.

## 1. What this phase fixes

- Fields, sheds and ponds often have no signal. Today a worker who opens the phone there can record money quick entries,
  but not:
  - attendance, crop activities, harvests or field notes;
  - the morning milk, heat seen, a treatment;
  - the poultry daily record, herd counts;
  - the pond record, a water reading, a weight sample;
  - a compost log entry or a processing step.
- When the phone loses signal halfway through saving, the farmer cannot tell whether the record reached the server, and
  pressing *Save* again can record it twice for records the server does not de-duplicate.
- Nothing tells the user what is waiting on the phone, what was refused and why, or when the phone last had fresh data.

## 2. Principles

1. **The server decides.** The phone keeps a queue of records the user made (the **outbox**). Each is sent as the same API
   request the online form would send, and checked by the same rules. The phone never posts money or stock by itself.
2. **Every record is sent at most once.** Each record gets a client id when it is made; it is the `Idempotency-Key` of its
   request, so retries after a timeout, a crash or a reconnect never create duplicates.
3. **Nothing is silently lost.** A record the server refuses stays on the phone, with the server's reason, until the
   user fixes and resends it or deliberately discards it.
4. **Overwrites are never silent.** If a day record (attendance, milk, a poultry or pond day) changed on the server
   after the phone last saw it, the phone shows both versions and the user chooses.
5. **One person's phone data stays theirs.** Data on the phone is kept per user and farm business, and never sent
   under another account.

## 3. What works offline (N2)

| Area | Works offline | Kind of record |
|---|---|---|
| Money | Cash expense and sale quick entries (as now) | Added |
| Crops | Activities (with stock used), harvests, field notes | Added |
| Workers | The day's attendance | **Day record** (replaces the day) |
| Dairy | Milk by session; heat seen; treatment given | Day record (milk); added (notes) |
| Poultry, goats and sheep | The daily batch record; born, died, culled counts | Day record; added |
| Fish farming | The daily pond record; water readings; weight samples | Day record; added |
| Organic manure | Batch log entries; manure collected | Added |
| Processing | Steps | Added |

**Online only** (they need fresh stock or money positions, or are set-up work): starting batches, crops, ponds and
crop cycles; compost and processing output into stock; internal transfers; sales and bills with parties; payments; stock records; harvests of
fish; reports. The form says so when the phone is offline.

- **Added** records only add (an activity, a reading, a log entry): they never conflict.
- **Day records** replace what is stored for that day: they can conflict (N7).

## 4. What the phone keeps (N1, N3)

**The local store (N1).** An on-device database (`expo-sqlite`), one file per signed-in user, holding:

| Store | Contents |
|---|---|
| Outbox | Records waiting to be sent, sent and confirmed, or refused (section 5) |
| Reference data | The lists the offline forms need: crop cycles, workers, animal groups and batches, ponds and their crops, compost and processing batches, stock items and lots, feed items, finance categories and the cash account |
| Last views | The last loaded dashboard, milk day, attendance day, and batch, pond and cycle summaries, each with its "as of" time |
| Session | The user, their farm businesses, roles, permissions and enabled modules, as last seen |

The existing quick-entry storage moves into the outbox, once, keeping each entry's id.

**Reference data (N3)** is refreshed on every successful sync and when the user pulls to refresh. Every list and view
read from the store shows *As of 06:10 today*. A list older than 7 days shows a warning.

## 5. The outbox and sync (N4, N5, N6, N8)

Each outbox record holds: its **client id** (a UUIDv7, time-ordered), the farm business, the kind and target (for
example *milk day 2026-10-22, Milking herd*), the request to send, the version of a day record the phone last saw,
the time it was made, and its state:

| State | Meaning |
|---|---|
| **Waiting** | Not sent yet, or the network failed while sending |
| **Sent** | The server confirmed it; kept 7 days for the user to see, then removed |
| **Needs attention** | The server refused it (a rule, a permission, a conflict); the server's message is shown |

**Client ids (N4).** The client id is the request's `Idempotency-Key`. The server already de-duplicates finance records,
crop activities and harvests. This phase adds a **general idempotency store** on the server for every endpoint the phone
uses offline: the first request with a key is processed and its response kept for 60 days; a repeat with the same key
and the same body gets the stored response; a repeat with a different body is refused (`IDEMPOTENCY_KEY_REUSED`).

**Sync (N5, N6).**
- Sync runs when the app opens, when the network comes back, when the user taps *Sync now*, and every 5 minutes while
  the app is open with something waiting.
- Records go **oldest first**. Records for the same target (the same crop cycle, batch, pond or day) wait for an earlier
  one of that target that needs attention; records for other targets carry on.
- Network failures and server errors (5xx, rate limits) are retried with growing waits: 30 s, 2 min, 10 min, then every
  30 min. Refusals (4xx) are not retried; they go to *Needs attention*.
- The phone sends at most 5 requests a second, so a long day of offline work does not trip the server's rate limits.
- After the outbox is sent, reference data and the last views are refreshed.

**Needs attention (N8).** The list shows each refused record with the server's reason in the user's language, for
example *The period up to 30 Sep is locked*, *Not enough Urea in stock: 12 kg*, *This batch is finished*, *You no longer
have permission to record attendance*. The user can **edit and resend** (the form opens with the record) or **discard**
it (with confirmation). A discarded record is kept in a 30-day discard list for audit on the phone.

## 6. Day records and conflicts (N7)

Attendance, the milk day, the poultry daily record and the pond day replace what the server holds for that day. Two
people (or two phones) can change the same day.

- The server keeps a **version number** on each day record. A read returns it; a save sends the version it is based on.
- If the server's version is newer, the save is refused with `STALE_RECORD` and the current server version is returned.
- The phone shows **Yours** and **On the server** side by side (for example *Milking herd morning: yours 42 L, on the
  server 40 L, changed by Selvi at 08:15*), and the user chooses:
  - **Keep mine**: resend based on the server's current version;
  - **Use the server's**: discard the phone's record;
  - **Edit**: open the form with both, and send the result.
- Online forms on the web and phone send the version too, so the same protection applies when two people are online.

Added records never conflict; duplicates are prevented by the client id.

## 7. Signing in, permissions and protecting the data (N9, N10, N11, N12)

**Offline sign-in (N10).** The phone opens offline with the last signed-in user and farm business, from the local
store; the refresh token stays in the keystore and is not used until there is a network. When the network returns, the
session is refreshed first and then the outbox is sent.
- If the session cannot be refreshed (expired after 30 days, revoked, or the password changed), the outbox is kept and
  the user signs in again. Sending resumes only if **the same user** signs in.
- A different user signing in on the same phone gets their own store. The previous user's outbox stays locked under
  their account and is never sent as someone else.

**Permissions offline (N11).** Forms are shown from the last known permissions and modules. The server re-checks at sync;
a record refused for a removed permission goes to *Needs attention*.

**Dates and clock (N9).** Records carry the farm date the user chose, plus the phone's time when they were made. The
server checks dates as it does today (no future dates, the lock date). A phone whose clock is more than 10 minutes off the
server's time (seen at each sync) shows a warning, because farm dates are taken from it.

**Protecting local data (N12).**
- Data lives only in the app's private storage, per user; the device's own encryption protects it at rest. The refresh
  token stays in the platform keystore, as now.
- **Sign out** with records waiting asks: *Send now*, *Keep on this phone for my next sign-in*, or *Discard*. Signing out
  without waiting records clears that user's store.
- When the server says the user is no longer a member of a farm business, that business's reference data and last
  views are cleared from the phone; its waiting records stay, locked, for the user to discard (they cannot be sent).
- No passwords, card or bank numbers, or government ids are ever stored on the phone.

## 8. Status in the app (N13)

- A **status line** on every screen: *Offline · 6 waiting*, *Sending 3 of 6…*, *All sent · last sync 06:10*, or
  *2 need attention*.
- Each record in a list carries a small badge: waiting, sent, or needs attention.
- **More → Sync** shows the outbox (waiting, sent in the last 7 days, needs attention), *Sync now*, the last sync time,
  the clock check and the app version.

## 9. Compatibility (N14)

- Every request from the phone carries its app version. The server can refuse a version that is too old to sync safely
  (`CLIENT_UPGRADE_REQUIRED`); the phone then asks for an update and keeps the outbox.
- Outbox requests are stored as they were made; if the server's request format changes, it must keep accepting the
  previous version's requests for at least 90 days.

## 10. Worked example (October 2026)

Monday 20 October, 06:00. Murugan, a field supervisor, opens the app on the farm with no signal. The status line shows
*Offline · last sync Sun 19:40*.

| Time | Record | Kind |
|---|---|---|
| 06:05 | Attendance for 6 workers | Day record (version 3 seen) |
| 06:20 | Morning milk, Milking herd: 42 L | Day record (version 1 seen) |
| 06:40 | Poultry daily record, Layer shed A: 2 deaths, 850 eggs, 110 kg feed | Day record (none seen) |
| 07:30 | Crop activity, Samba 2026: fertiliser, Urea 50 kg | Added |
| 07:45 | Expense quick entry: diesel ₹600 | Added |
| 08:00 | Water reading, Pond 1: DO 3.2 | Added |

Meanwhile, at 08:15, Selvi corrects the morning milk on the web to 40 L (version 2), and the office uses 30 kg of the
last 40 kg of Urea on another field.

At 11:00 the phone gets signal. It refreshes the session and sends six records, oldest first:

| Record | Result |
|---|---|
| Attendance | **Sent** |
| Milk | **Needs attention**: the server has version 2 (40 L, Selvi, 08:15); Murugan's was based on version 1 (42 L) |
| Poultry daily record | **Sent** |
| Crop activity | **Needs attention**: *Not enough Urea in stock: 10 kg* |
| Expense quick entry | **Sent** |
| Water reading | **Sent**, with the out-of-limit flag shown |

Murugan chooses *Use the server's* for the milk, edits the activity to 10 kg and resends it (**Sent**). The status line
shows *All sent · last sync 11:02*. If the network had dropped after the attendance was saved but before the reply came
back, the retry would have returned the stored reply: one attendance day, not two.

## 11. Data and server changes (N4, N7)

**Server (one forward-only migration):**
- `sf_idempotent_requests`: tenant, user, key, method, path, request hash, response status and body, created at; unique
  per tenant and key; rows older than 60 days are removed by a daily job.
- An `@Idempotent` marker for endpoints; a filter that replays stored responses. Existing per-record idempotency stays.
- A `version` column on attendance days, milk sessions, poultry batch days and pond days; reads return it; saves take an
  optional `baseVersion` and refuse a stale one with `STALE_RECORD` (409) and the current record.
- The `X-Client-Version` header is logged, and a minimum version is configurable.

**Phone:** `expo-sqlite` and `@react-native-community/netinfo` (Expo SDK 54 versions), a sync module, the outbox and
reference stores, the status line and the Sync screen. Every offline form writes to the outbox and then tries to send at
once, so online use feels the same as today.

All new server tables get tenant keys. Every new endpoint and the replay filter get cross-tenant tests: a key used in
one farm business never replays a response in another.

## 12. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **N1** | Keep offline data in an on-device SQLite database, one per user, relying on the device's encryption (no extra database encryption)? | **Yes** |
| **N2** | The offline scope in section 3: field records (attendance, crop work, milk, livestock records, pond records, compost log, processing steps, quick entries); set-up, stock output, transfers and party records online only? | **Yes** |
| **N3** | Cache the lists the forms need and the last views, always shown with their "as of" time, refreshed on sync? | **Yes** |
| **N4** | Every offline record gets a UUIDv7 client id used as its `Idempotency-Key`, with a general server idempotency store (60 days) for the endpoints the phone uses? | **Yes** |
| **N5** | Send oldest first, holding back only later records of the same target when one needs attention? | **Yes** |
| **N6** | Sync on open, on reconnect, on *Sync now* and every 5 minutes; back off on network and server errors; never retry refusals; at most 5 requests a second? | **Yes** |
| **N7** | Version numbers on day records (attendance, milk, poultry day, pond day), refusing stale saves with a side-by-side choice on the phone, for online saves too? | **Yes** |
| **N8** | A *Needs attention* list with the server's reason, edit-and-resend or discard, and a 30-day discard list? | **Yes** |
| **N9** | Keep the farm date the user chose and warn when the phone clock is more than 10 minutes off? | **Yes** |
| **N10** | Open offline with the last user; refresh first when online; send only for the same user; lock another user's outbox? | **Yes** |
| **N11** | Use the last known permissions offline, with the server re-checking at sync? | **Yes** |
| **N12** | On sign-out with waiting records, ask to send, keep or discard; clear a farm business's data when membership ends, keeping its locked records for discard? | **Yes** |
| **N13** | A status line on every screen, record badges, and a Sync screen? | **Yes** |
| **N14** | Send the app version; allow a minimum version; accept the previous request format for 90 days? | **Yes** |
| **N15** | No offline web (a PWA) and no offline photos in this phase? | **Yes**; photos wait for attachment storage (ADR 0009) |

## 13. What Phase 13 delivers once this is approved

**Features:**
- the on-device store, outbox and reference data, with the quick entries moved into it;
- offline forms for the records in section 3, sending at once when online;
- the sync engine with ordering, retries, backoff and pacing;
- *Needs attention*, conflict resolution for day records, and the discard list;
- the status line, record badges and the Sync screen;
- offline sign-in, same-user sending, sign-out choices and clearing on lost membership;
- the server idempotency store, day-record versions and the client version check;
- Tamil and English throughout.

**Tests:**
- section 10 as an end-to-end scenario against the API (stale milk, short stock, a retried attendance);
- replay of every offline endpoint with the same key and body, refusal with a different body, and cross-tenant keys;
- stale saves on each day record, online and offline;
- the sync engine on the phone: ordering, holding a target, backoff, pacing, crash in the middle of a send;
- sign-in as another user, sign-out choices, lost membership;
- the move of existing quick entries without loss or duplicates;
- the migration on a copy of the current schema.
