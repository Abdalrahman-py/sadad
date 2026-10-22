> Phase 3 planning notes, copied from the maintainer's local planning folder on 2026-10-18 so contributors without it can read them. `plan.md`, `state.md` and `store-permissions.md` stay local.

# Phase 3 gap inventory, 2026-10-17

What exists, what is missing, what each piece depends on. Facts come from the tree at 5fb6cd4; see
`state.md`. «غير محقّق» marks what could not be verified from the repo.

## 0. Server facts that shape the client (read from `schemas/` and `functions/api/`)

These change what the data layer must do, so they come first.

1. **Most per-command refusals abort the whole batch.** `24_apply_one.sql` raises *before* its per-type
   `begin … exception` block for `store_permission_denied` (`operation_allowed`, line 26),
   `unsupported_schema`, `invalid_input` (unparsable ids), `installation_revoked` (`origin_allowed`),
   `ledger_not_initialized`, `operation_id_reused`, `epoch_changed`, `invalid_dependencies`,
   `dependency_pending` and `invalid_timestamp`. A raise inside `apply_batch` rolls back every command in
   the batch. Only errors inside the per-type block (`overpayment`, `duplicate_phone`,
   `entity_version_changed`, …) and `dependency_rejected` become a `rejected` receipt.
   **Consequence:** one staff command the installation is not permitted to send (for example
   `payment.create` with `registerPayments = false`) makes every later sync fail forever: a poison outbox.
2. **The epoch never changes.** `sadid_ledger_state.epoch` is set once (`default gen_random_uuid()`,
   `02_ledger_tables.sql:4`, row created by `31_approve_installation.sql:32`). No routine updates it.
   So "full snapshot on epoch change" and "epoch change forces review" (ARCHITECTURE, GLOSSARY) have no
   trigger in the new backend. `recovery_epoch_allowed` always compares equal values.
3. **No route tells a device it was approved** except `enroll` itself: `23_enroll.sql` is idempotent for a
   known installation (updates label, returns the row with `status` and `permissions`) and raises
   `installation_revoked` for a revoked one. So the client polls `enroll` while pending. Good enough; no
   new route.
4. **`sadid_activity` and `sadid_outcomes` have no caller.** No `api` route and no dashboard code calls them
   (`grep` over `functions/` and `server/public/src`). The app does not need them either: history comes
   from `changes` events, and a resent command returns its first receipt. Dead interface; the clean-cut
   rule says delete.
5. **The lease key is its own function secret** (`SADAD_LEASE_PRIVATE_JWK`, `api/index.ts` `leaseSigner`;
   until 2026-10-22 it was derived from the service-role key). The lease
   response carries its own public key, so checking the signature against that key proves nothing; the
   Java app did exactly that (`SignedLedgerSync.verifyLease`). The server never checks leases: they are a
   client-side policy only.
6. **Fresh stores cannot enroll.** A store created or reset by an admin has `force_password_change`;
   `enroll` refuses it. The app needs a "change temporary password" step before enroll, then a token
   refresh (`account.ts` route description). `tools/smoke-api.ts` lacks this step, so ticket 27 step 7
   stalls (state.md §6).
7. **Installation permissions** (`registerPayments`, `deleteContacts`, `deleteRecords`) and store
   permissions (`contacts`, `debts`, `payments`) are enforced by `operation_allowed`. The device sees its
   own installation `permissions` in the `enroll` response; it cannot read the store's.
8. **Batch limits:** ≤100 commands and ≤512 KiB body (`mobile.ts`), `changes` limit ≤500 per page.
9. **After ticket 27, any schema change needs a new migration**, but `check.sh docs` requires
   `migrations/` to hold exactly one `*_baseline.sql`, and the memory rule forbids `db push` of renamed
   files. So server changes are cheap **before** 27 (edit `schemas/`, regenerate the baseline) and costly
   after (a second project reset, or a migration process that does not exist yet).

## 1. Data layer (Phase 3 item 2)

Nothing exists in `:app` (no Kotlin besides `KotlinSmokeTest.kt`); `:app` does not depend on `:ledger-core`.

| Piece | Missing | Depends on |
| --- | --- | --- |
| Room (read model + outbox) | everything: entities, DAOs, database, KSP + Room plugin wiring | catalog entries (§5), decision on how DAOs are tested (open question Q5) |
| Envelope key | one Keystore AES key wrapping a software AES-256-GCM data key, unwrapped once per process; used for outbox payloads and the refresh token | Keystore (device only; JVM tests use a software adapter) |
| DataStore | accounts list, active store, staff name, prefs, wrapped data key, lease | catalog |
| `AuthApi` | `POST /auth/v1/token?grant_type=password` with `<username>@stores.sadad.invalid`, `grant_type=refresh_token`, `POST /auth/v1/logout`, plus `POST /v1/me/password` | OkHttp, publishable key, Supabase URL in BuildConfig |
| `LedgerApi` + signing interceptor | enroll, lease, changes, operations; headers `x-sadad-installation/time/nonce/signature`; signed route = path from `/v1` + query; raw body bytes; `x-sadad-recovery` for recovery batches | `ProofText` (exists), installation key (P-256, Keystore alias per store), raw r‖s from DER |
| Error map | `contract/errors.json` (61 codes) → Arabic message. ARCHITECTURE wants a Gradle codegen task; packaging the JSON as an asset and reading it once is simpler and still one source | build wiring |
| Enrollment | first enroll with label + staff name, poll while `pending`, read `permissions`, stop on `installation_revoked`, `device_limit_reached` | `LedgerApi`, password-change step (§0.6) |
| Offline lease | renew when <24 h left; offline types may queue only under a valid lease; verify signature against a **pinned** public key (Q8) | `LedgerApi`, BuildConfig key |
| Recovery | sealed export (PBKDF2-HMAC-SHA256 600k → AES-256-GCM, AAD `sadid-recovery-v1`), plaintext manifest for the admin, import on the replacement device, permit entry, recovery batches with `x-sadad-recovery` holding only manifest commands | outbox, `LedgerApi`; PBKDF2WithHmacSHA256 is platform only from API 26 (the Java app hand-rolled it for 24–25) |
| `SyncWorker` | unique `OneTimeWork` on write (network constraint, backoff) + periodic work; one pass = session → enroll if not active → lease if due → push → pull → compact | everything above |
| `AppGraph` | manual container in `Application`, holding the current store scope (DB, keys, repository) and swapping it on account switch | all of the above |
| StrictMode | `penaltyDeath` in debug | `Application` |

### How the data layer uses `:ledger-core` (proposed decisions; see plan Q2–Q4)

- **`LedgerState` lives in memory**, one per open store, owned by a `LedgerRepository`. It is
  **persisted as Room rows** (`contacts`, `debts`, `payments` = `ContactRow`/`DebtRow`/`PaymentRow`, plus
  `ledger_meta` with `storeId`, `epoch`, `cursor`) and loaded once when the store opens. Re-folding events
  at start is O(history) and would miss the 1 s cold-start target at 50k ops.
- **Persisting a page:** `apply(page)` copies the maps once and keeps untouched row instances, so the
  rows to upsert are exactly those where `new[id] !== old[id]`. One Room transaction per page writes
  those rows, the page's events and the new cursor. A crash mid-sync keeps every finished page.
- **Events table** (slim: sequence, operationId, kind, entityId, installationId, createdAt, status, code,
  command JSON) backs the history screen ("latest 200", as the old `sadid_activity` did) and rejected
  verdicts. Read with a `LIMIT` query; no Paging 3.
- **Screens read one `StateFlow<LedgerView>`**, recomputed on `Dispatchers.Default` whenever the state or
  the outbox changes (`state.view(pending, failed, installationId, staffName)`). This is a deliberate
  departure from ARCHITECTURE Part 2 "UI reads Room `Flow`s": a SQL read model would re-implement
  `view()`'s pending-projection and money rules. `view()` is O(rows) per change; at 5k contacts / 50k
  rows its cost on a mid device is «غير محقّق» and must be measured by the Macrobenchmark write trace.
  Upgrade path if it misses 100 ms: incremental view per Contact.
- **Outbox → `pending` / `failed`:** an outbox row has `seq` (queue order), `operation_id` (unique),
  `entity_id`, `type`, `state`, `code`, `created_at` as plain columns and the command JSON encrypted with
  the data key. States: `queued` (in `pending`), `accepted` (in `pending` until the state holds its
  entity, then deleted — the "skip if entity in state" rule of `view()` covers the gap), `rejected` and
  `cancelled` (their ids form `failed`). Decrypt only when the outbox changes, never per query. A
  compound action (Contact + first Debt, a Payment split over Debts) is one Room transaction.
- **Epoch change:** keep `LedgerState.apply`'s refusal; on a different epoch the repository resets to
  `EMPTY` and pulls from `after=0`. A batch refused with `epoch_changed` stops sync with a message. No
  review UI (the server never changes the epoch, §0.2).
- **Receipts:** `accepted` → row state `accepted`; `rejected` → state `rejected` + `code`; dependents of a
  rejected or cancelled command are rejected by the server (`dependency_rejected`) or, if never sent,
  cancelled locally in the same transaction.
- **Online-only types** (`contact.update/archive/restore`, `debt.correct`, `payment.reverse`, `offline =
  false`): queue only while online and start sync at once; `view()` ignores them until the server
  answers.
- **Read model is plaintext in Room**, like the Java app's `SadadDatabase`; only outbox payloads and the
  refresh token are encrypted. `allowBackup=false` stays.

## 2. Compose UI (Phase 3 item 3)

Nothing exists. The Java screens (`NativeScreens.addPage`) are: home, contacts, contact_detail,
contact_form, debt_form, payment_form, history (+results), reports, notifications, settings, trash; login,
lock and recovery live in `MainActivity`. ARCHITECTURE's order: login/unlock → home → contacts → contact
detail → contact/debt/payment forms → history/reports → settings/recovery.

| Screen | ViewModel / state | Needs from data layer | Server gap |
| --- | --- | --- | --- |
| Login | username, password; `SavedStateHandle` for username only | `AuthApi`, accounts | none |
| Temporary password change | current, new (12–128) | `/v1/me/password` + refresh | `weak_password` from Auth becomes 500 (review-pr21 #3); Auth rate limit becomes 500 (#1) |
| Awaiting approval | staff name + label on first enroll; poll | enrollment | none |
| App lock (PIN + biometric) | PIN lockout on `elapsedRealtime`, not wall clock (A7) | DataStore | none |
| Home | totals, this month's debts/payments, recent movement | `LedgerView`, events | none |
| Contacts | search over 5k rows, `LazyColumn` | `LedgerView` | none |
| Contact detail | balance, Debts, Payments, WhatsApp share | `LedgerView` | none |
| Contact form (create/update), archive/restore ("trash") | `SavedStateHandle` | repository writes | none |
| Debt form (create, correct) | `SavedStateHandle`, amount → `Cents` parsing | repository | none |
| Payment form (one Debt or allocated over a Contact; reverse) | `SavedStateHandle` | `PaymentAllocation` | none |
| History | latest 200 events + failed outbox entries | events, outbox | none (`sadid_activity` not needed) |
| Reports + PDF/Excel | period filter, per-Contact statement | `LedgerView` | none (computed locally) |
| Notifications (due reminders) | debts due today/tomorrow; daily `Worker` + `POST_NOTIFICATIONS` | `LedgerView`, WorkManager | none |
| Settings | store display name, staff name, theme, export period, support link, change password, switch account, logout, sync status | DataStore, sync status | store name/owner are not readable by the app (no route); keep them local (Q9) |
| Recovery | export sealed copy + admin manifest, import, permit entry | recovery (§1) | none |

Every screen: strings in `res/values/` (Arabic default), one Compose UI test. Compose UI tests and Room
DAO tests need an Android runtime: a phone/emulator (human, adb) or Robolectric. Robolectric is not in
`docs/versions.md` (Q5). PDF uses `android.graphics.pdf.PdfDocument` (Android only); the Excel writer is
plain `java.util.zip` and ports to pure Kotlin (`LedgerTableExcel.java`, 10.5 KB; `LedgerTablePdf.java`,
11.7 KB: layout rules to carry over).

## 3. The switch (Phase 3 item 4)

Still present: 37 Java main classes, 10 Java `androidTest` runners (`QaInstrumentation` is the default
`testInstrumentationRunner`), 7 Java JVM tests, `standalone` flavor + `app/src/standalone/`,
`STANDALONE_MODE`, the `"123"` login, `resValues = true`, `org.json` test dependency, default
`applicationId com.sadad.app.test`, `rootProject.name = "sadad-test"`, `isMinifyEnabled = false`, template
`proguard-rules.pro`, Java-era `res/` (layouts are programmatic; `values/*.xml`, drawables, launcher
icons). Build config points at `sadad-api`/`sadad-auth`. `check.sh android`, `android-ci.yml` (TZ step,
artifact path) and `android-release.yml` (task names, URLs, `ibrahem57/sadad`) name flavor tasks.

R8 + `shrinkResources` with keep rules for kotlinx.serialization, Room, OkHttp and the Keystore absorbs
ticket 19. Testers reinstall (new app, clean cut).

**Evidence against "switch at the end":** after ticket 27 the Java app cannot sign in (its three
functions are deleted from the project), so it has no use as a working app. Keeping it while Kotlin is
written in the same module costs every ticket: two launchers, class-name collisions in `com.sadad.app`
(`LedgerApi`, `InstallationKey`, `SessionStore` exist in Java), two flavors in every Gradle task,
`check.sh android` building dead code, and R8 only testable at the very end. Its value as a behaviour
reference survives in a tag. See plan Q1.

## 4. Baseline Profile, Macrobenchmark, StrictMode (Phase 3 item 5)

Nothing exists. `benchmark-macro-junit4` / `baselineprofile` 1.5.0 and `profileinstaller` 1.4.1 are in
`versions.md` but marked "Phase 4, not build-proven"; ARCHITECTURE puts them in Phase 3. The targets need a
5k-contact / 50k-op store. Seeding that on the server needs the cloud (human); seeding Room directly in a
benchmark-only build measures start, scroll and write latency but not "sync time flat" (Q7). Running
benchmarks needs a phone (human).

## 5. Build gaps

- `:app` does not depend on `:ledger-core`.
- `java.time` (`Instant.parse`, `OffsetDateTime.parse`, `ChronoUnit`) in `:ledger-core` needs core library
  desugaring on API 24–25. `desugar_jdk_libs` is **not** in `versions.md`. Alternative: `minSdk 26` (Q3).
  Note: review-pr23 #1 (`Instant.parse` rejects `+hh:mm`) holds on desugared java.time and on platform
  java.time before Java 12 semantics; fix it in `:ledger-core` regardless.
- Catalog entries still missing (all versions decided in `versions.md`): Room runtime/ktx/compiler +
  Room Gradle plugin, KSP plugin, WorkManager, DataStore Preferences, lifecycle viewmodel-compose /
  runtime-compose, navigation-compose, biometric, core-ktx, OkHttp + mockwebserver, kotlinx-coroutines
  (+ test), Turbine, kotlinx-serialization plugin on `:app`, androidx test ext/runner/uiautomator, Compose
  `ui-test-junit4` / `ui-test-manifest` / `ui-tooling` (BOM-managed). `work-testing` and
  `room-testing` share their library's version but are separate artifacts (call it decided, or audit).
  Robolectric and `desugar_jdk_libs` are not decided at all.
- Unused catalog entries after the switch: `json`, `activity`, `ext-junit` (if unused).
- CI path filters miss `ledger-core/**` and `contract/**`; the non-UTC rerun skips `:ledger-core:test`.
- Release config: `applicationId` default `com.sadad.app.test`, the QA id `com.sadad.qa`, and the
  `SADAD_TEST_*` properties must be re-decided for one edition (Q10).

## 6. Server and contract gaps

| Need | Exists? | Proposal |
| --- | --- | --- |
| Per-command refusals as receipts | no (§0.1) | server ticket **before 27**: move the per-command checks into the receipt path |
| Activity / outcomes routines | dead (§0.4) | server ticket **before 27**: delete |
| Smoke run with a fresh store | broken (§0.6) | tools ticket **before 27**: change the temporary password, refresh, then enroll; print the lease public key for pinning |
| Lease public key for the app | derived, not published | human step after 27 (from the smoke output) → Gradle property; or drop the check (Q8) |
| Account error mapping | 500 for rate limit / weak password | api ticket, any time (functions-only redeploy) |
| Recovery from the phone | permit typed by the user; `x-sadad-recovery` on `operations` | none |
| Account and password change | `/v1/me/password` | none |
| Store name / owner shown in the app | not readable | keep local (Q9) or add `GET /v1/mobile/store` (not recommended) |
| Reports | local | none |
| Supabase branch load test, seeded 50k-op store (Phase 2 Verification) | not ticketed | human; cloud cost (Q7) |
| Delete-store ordering (review-pr17) | admin only | not Phase 3; keep as a note |

## 7. Human-only steps

1. Ticket 27 cutover (after the pre-cutover server tickets, Q6), with the smoke fix.
2. Lease public key into Gradle properties after 27 (if Q8 = pin).
3. Device runs over adb: Keystore/StrictMode instrumentation, Compose UI tests if not on Robolectric,
   the Phase 3 exit flow (login → offline add → sync → A→B→A → logout), Macrobenchmark and Baseline
   Profile generation, release smoke of the R8 build.
4. Remove stale remote branches, the `sadad-probe` worktree, `claimed` labels on closed issues.
5. Production signing key and Play internal track (Phase 4).
6. Pushes, merges and deploys throughout.

## 8. Docs

- `docs/CONTINUE_HERE.md`: rewrite to current state (Phase 2 merged, 27 pending, Phase 3 started).
- `docs/CHANGELOG.md`: lines for 14, 30, 21–26, 28, 31, 32, 16, 17.
- `GLOSSARY.md`: re-define domain nouns without Java symbols; delete Delta/Watermark/Revision/Legacy
  ledger; re-define Outbox for the Room outbox; rewrite "Where the sync seam lives".
- `docs/ARCHITECTURE.md`: mark Phases 1–2 status, record the Phase 3 departures the user approves (Q1–Q4).
- `AGENTS.md`: Android code-map rows at the switch.
