# Sadad — Architecture Audit & Production Plan

## Context

User paused feature work to define "done": a production-ready Sadad (Android app + Supabase backend + admin dashboard) that is fast, maintainable, scalable, and easy for AI agents to work in. This doc = (1) senior audit of where code departs from best practice, with evidence, (2) target state, (3) phased plan.

Decisions taken (user, 2026-10-14, revised 2026-10-15):
- Android: **direct rebuild in Kotlin + Compose** inside `app/`, then one switch that deletes the Java app. (Was a screen-by-screen strangler; replaced 2026-10-15.)
- **Drop standalone edition** (`standalone` flavor, `STANDALONE_MODE` branches, `"123"/"123"` local login). It goes with the Java app at the Phase 3 switch.
- Dashboard: **stay static, move to TypeScript + Vite build + typed API client**, as part of the backend cutover.
- Backend: **one cutover** to Supabase Auth + one `api` function + a squashed schema baseline, with a reset of the test cloud project.
- **Clean cut, no legacy handling** (2026-10-15): all data is test data, so nothing is migrated, kept compatible or bridged (Part 3, "The clean-cut rule").

AGENTS.md rules still hold: one source per component, no second Android project, no parallel server; cloud migrations need review/approval; public repo = no secrets.

Deliverable of this plan's execution step 0: commit this audit as `docs/ARCHITECTURE.md` (replaces untracked `docs/ARCHITECTURE_MIGRATION_PLAN.md`, which overlaps and should be merged/deleted, not kept as second doc).

---

## Part 1 — Audit (evidence-cited)

### What is good — keep it

- Signed-ledger protocol design: idempotent `operationId`, per-installation ECDSA proof, epochs, receipts, server-signed offline lease, admin-approved recovery. Correct shape for money. Port, don't redesign.
- Money stored as integer cents end to end in DB and ledger (`amount_cents`, `centsOf`).
- Secrets discipline: only `sb_publishable_` in app/dashboard; service role only in Edge env.
- Dashboard basics: CSP meta, escaping helper, AbortController stale-response guard, hashed asset names.
- Real tests exist: JVM ledger tests, Deno handler tests, pglite SQL tests, CI running all four.

### A. Android app — critical (performance / correctness)

A1. **Every money write does O(N) crypto + full rebuild on the UI thread.**
- `NativeScreens.java:758,815,897` call `host.addContact/addDebt/addPayment` directly from click handlers (main thread).
- Those call `ledgerQueue()` → `journal.add` → then `afterSignedWrite()` → `materializeLedger()` (`MainActivity.java:540-543,752-756`).
- `materializeLedger` = `LedgerQueue.view()` = `LedgerProjection.view(journal.exportPlain())` (`LedgerQueue.java:69-71`). `exportPlain()` decrypts the whole snapshot blob plus **every journal entry ever** with Android Keystore AES-GCM, one Keystore op per row (`EncryptedJournal.java:35,86`).
- Then `SadadDatabase.importLedgerView` upserts every contact/debt/payment row again (`SadadDatabase.java:777+`).
- `payContact` does a full `view()` too (`LedgerQueue.java:146`); `dependenciesOf` and `hasPendingWork` decrypt all entries (`:94,175`).
- Journal entries are never pruned (no DELETE on `entries`) → cost grows forever with store history. Guaranteed ANR/jank as stores age.
- `markLedgerChanged()` runs twice per write (`afterSignedWrite` + caller at `NativeScreens.java:761,819,899`).

A2. **Sync is chatty and wasteful.** One pass (`SignedLedgerSync.java:72-135`) = `enroll` + `lease` + full `snapshot` + one POST **per operation**, sequential + second full `snapshot` if anything sent. Each request = Keystore ECDSA sign. Triggered: on resume, on every network `onAvailable`, 750 ms after every write, hourly in-activity poll (`MainActivity.java:86-100`), hourly JobScheduler job, expedited pending job. Idle store still pays ≥3 signed requests per trigger.

A3. **Threading model is ad hoc and racy.**
- One `newSingleThreadExecutor` for all network + disk + reminders, each task wrapped in global `synchronized (SyncScheduler.LOCK)` (`MainActivity.java:64-72`). A slow sync blocks every user action queued behind it.
- UI-thread writes (A1) don't take `SyncScheduler.LOCK`; `ledger` and `database` fields are reassigned on the executor (`switchDatabaseForAccount` from `loginLocal`/`switchStoreAccount`, `:382,1263,1288-1294`) and read on UI thread with no synchronization/volatile → data race.
- Second export executor (`:969`). `LedgerSyncService` has its own executor and opens a second `LedgerQueue`/`SadadDatabase` on same files (`LedgerSyncService.java:12-30`).
- Hand-rolled "account scope" string compare used as cancellation token everywhere (`:73-76`).

A4. **No lifecycle/state architecture.** `android.app.Activity` (not `ComponentActivity`), no ViewModel, no `onSaveInstanceState`: nav back-stack (`ArrayDeque<NavigationEntry>`, `:111-115`), form drafts, route → lost on process death. Rotation hidden via `configChanges` + `screenOrientation=portrait` (manifest). `NativeScreens.show()` rebuilds entire view tree on every refresh (`NativeScreens.java:93-102`, `refreshCurrentScreen` → `screens.show(currentRoute)`), triggered after every sync. Contacts list = `LinearLayout` rows with manual "show more 60" paging (`:438-453`) and `snapshot()` re-read per keystroke (`:421`). Cache keyed on `changeToken` (`:1559-1566`) — a hand-made memo for what Room `Flow` + Compose does natively.

A5. **God classes.** `MainActivity` 1,496 lines owns: session, login (2 modes), HTTP client, sync orchestration, ledger writes, recovery UX, PDF save/share/print, photo pick/EXIF/crop, voice input, biometrics (platform `BiometricPrompt` + deprecated `FingerprintManager`), theme, toasts, undo banner, navigation, account switching. `NativeScreens` 1,624 lines = every screen + palette + widgets. 151 lines >200 chars; `EncryptedJournal.java` methods are single 1–2k-char lines (`:39,56,57,87`). Unreadable for humans and costly/error-prone for agents (one edit = whole-line diff).

A6. **Data model is JSON everywhere.** `org.json.JSONObject` is the domain model, DTO, DB return type and UI model (`snapshot().optJSONObject("totals")`, `person.optDouble("receivable")`). No types, `opt*` silently defaults on typos. UI does money math in `double`/`BigDecimal` from doubles (`NativeScreens.java:791,799,831,885`; `SadadDatabase.fromCents` `:973`; `addPayment` returns `amount / 100.0d` `MainActivity.java:642`).

A7. **Platform/API debt.**
- `targetSdk = 34` (`app/build.gradle.kts`) — Play requires 35 for updates since Aug 2025; 36 cycle next. Bumping to 35 forces edge-to-edge → current `setStatusBarColor`/`setSystemUiVisibility` (`MainActivity.java:1296-1306`) stop working.
- `isMinifyEnabled = false` in release → no R8 shrink/obfuscation; proguard file is template.
- Deprecated: `Toast.setView` custom toasts (ignored from background on API 30+, `:1326-1328`), `onBackPressed` (predictive back), `startActivityForResult`, `FingerprintManager`, `android.media.ExifInterface`.
- Biometric unlock not bound to a `CryptoObject` — UI gate only; PIN lockout uses wall clock (`LocalSecurity.java:27`) → reset by changing device time.
- Hardcoded support phone `wa.me/970599562401` (`MainActivity.java:293`), hardcoded project ref in Gradle defaults.
- Default `applicationId = "com.sadad.app.test"`, `rootProject.name = "sadad-test"`; unused catalog entries (appcompat/material/constraintlayout/espresso).
- All user strings hardcoded Arabic in Java; no `strings.xml` → no translation, no lint for missing strings, no Compose previews in other locales.

A8. **Correctness landmines found.**
- Sync wipes open forms: every sync result calls `refreshCurrentScreen()` (`MainActivity.java:572,578`) → full rebuild of `contact_form`/`debt_form`/`payment_form` while user types.
- `removeUnseen` builds `NOT IN (?,…)` with one bind per row (`SadadDatabase.java:920-926`). Android SQLite before 3.32 (pre-API 30, minSdk is 24) caps binds at 999 → import throws once a store has >999 payments, sync permanently broken on those phones. Empty set → `DELETE FROM table` (wipe risk on an empty/partial view).
- Schema objects (`ledger_meta`, unique `remote_uuid` indexes) created in `onOpen`, not `onCreate`/`onUpgrade` (`:38-48`).
- Pending undo commits (`undoHandler`) survive `onDestroy` (`MainActivity.java:1485-1495`).
- `DueReminderReceiver` does DB work in `onReceive` on main thread without `goAsync`; reminders only check the active account.
- `LedgerSyncService` returns `false` (no retry) when activity is alive; errors swallowed.
- N+1: `latestPersonDebt` per history row (`NativeScreens.java:941`); `findContactIdByPhone` full-table Java scan; delete-to-trash builds full snapshot for one person.
- Zero `Log.*` calls in main code; 16 bare `catch` blocks → field failures are invisible.
- Request code 7316 used for both permission request and voice draft.
- Dead code: `ThemeSceneView`, `StatementPdfWriter`, `printPdf`, `importSnapshot`, unused `colors.xml` (43 colors), identical `values-night/themes.xml`.

A9. **No observability, no crash reporting, no perf measurement.** No baseline profile, no startup/jank metrics, no crash reporter → "massively improve performance" is currently unmeasurable.

A10. **Standalone edition** (to be removed): fixed login `"123"`/initial password `"123"` (`MainActivity.java:416`, `LocalSecurity.verifyLoginPassword`), plain SQLite, every write method branches on `STANDALONE_MODE`/`signedLedger()` (`:592,614,632,673,686,721`).

### B. Backend (Supabase) — critical

B1. **Full-store snapshot on every sync.** `sadid_private.snapshot()` (`migrations/20261010102007_sadid_v3_authorization_hardening.sql:146+`) returns all contacts, debts, payments, adjustments, reversals and **every operation outcome ever** as one JSONB. `cursor` exists in state but no delta endpoint. Payload and CPU grow O(store history) per sync, ×2 per pass (A2).

B2. **4 sequential round trips per ledger request** (`functions/sadid-ledger/handler.ts`): `db.auth.getUser(jwt)` (network call to Auth) → `rpc sadid_installation_key` → `rpc sadid_issue_capability` → user-client `rpc`. Supabase now recommends `auth.getClaims()` with asymmetric signing keys (local JWKS verify, cached). Session revocation is already enforced by `sadid_installation_key`/capability RPCs, so `getClaims` is safe here.

B3. **Two identity systems.** `sadad-api` runs its own scrypt passwords + opaque session tokens (`handler.ts:1-60`, `SESSION_MS`, `hashToken`); `sadad-auth` issues Supabase Auth JWT "envelope"; `sadid-ledger` needs both (`x-sadad-session` legacy token hashed at enroll, `Authorization` JWT). Admin uses a third scheme: `X-Sadad-Admin` hashed token on ledger, Bearer on api. App stores both `token` and `auth.accessToken` (`SessionStore`). Every flow (login, logout, switch account) has to keep 2–3 credentials consistent — source of the account-switch bugs still open in `CONTINUE_HERE.md`.

B4. **Edge code shape.** Manual `if (route===...)` routing, copy-pasted CORS/JSON/error helpers per function (`sadid-ledger/handler.ts:3-5`, `sadad-api/handler.ts:12-45`, `sadad-auth/handler.ts:11-28`), no shared `_shared/` module, no schema validation library, minified one-line style in `sadid-ledger`, `Access-Control-Allow-Origin: *`, hardcoded `projectRef:'vhftjmiltqfwmrszdtgf'` in lease payload (`handler.ts` lease block). No API contract (OpenAPI/JSON Schema/shared types) between app, dashboard and functions.

B5. **Security bugs found (fix first, Phase 0):**
- `findAccount` uses `.ilike("username", username)` (`sadad-api/handler.ts:266`) while `safeUsername` allows `_` (`:95`). `_` is an ILIKE wildcard → `a_c` logs in as `abc`; per-account rate-limit bucket is keyed on typed name → wildcard variants get fresh 10-try buckets. Same copy in `sadid-ledger/admin.ts:69,121`. Fix: `.eq` on `lower(username)` (unique index exists).
- Enroll proof nonce never recorded → replayable for 5 min (`sadid_private.enroll`, `migrations/20261010100856…:1-31`).
- `sadad-auth` sends service-role key as `apikey` on internal HTTP hop to `sadad-api` (`sadad-auth/handler.ts:57,64`) — internal function-to-function HTTP with god key.
- create-store / reset-password return plaintext passwords in JSON (`sadad-api/handler.ts:413,509`) — acceptable only if shown once; must never be logged.
- Admins have no roles: any admin session = full rights incl. permanent delete (`admins` table has no role column).

B6. **Read paths take write locks.** `lock_authorization()` does `FOR UPDATE` on `public.stores` row (`…authorization_hardening.sql:21`), and `snapshot()`/`activity()` call it → every read serializes against every write and admin action for that store.

B7. **Migration history is not reviewable.** `sadid_private.apply` body redefined 5× across files, then patched by string-replacing `pg_get_functiondef` output (`20261012102400…:17`, `20261012110608…:32`). No file contains the deployed `apply()`. Two prefixes (`sadad_`/`sadid_`), two version formats, epoch-ms bigint vs `timestamptz` mixed.

B8. **Ops gaps.** No cleanup of expired `sessions`, `sadad_auth_bindings`, `capabilities`, `request_nonces` → unbounded growth. Missing indexes: `sessions(kind, principal_id)`, `control_events(store_id)`, `admin_access_events(store_id, created_at)`. `sadid_admin_summaries` loops per store (~6 queries) and inserts one audit row per store **per dashboard load**. `config.toml` has no `[functions.sadid-ledger]` entry → CLI deploy defaults `verify_jwt=true`, breaking admin calls; deployed config not reproducible. No deploy automation (`supabase functions deploy` / `db push` manual). Unknown error codes → 503 `temporary_service_failure` even for business errors. No structured logs/request ids. `.temp/linked-project.json` tracked. `test-v3-authorization.mjs` is a toy model not loading real migrations; pglite = PG 18.3 vs prod 17.6.

B9. **Duplication.** `sadid-ledger/admin.ts` is a near-verbatim copy of sadad-api auth/session code, apparently unused by dashboard. CORS/JSON/bearer/`sessionId` helpers ×3. Admin-session SQL inlined in ~6 functions despite `admin_session_username` helper. Error-code→Arabic message table mirrored by hand in `LedgerMessages.java`.

### C. Dashboard

C1. Single 598-line IIFE, closure-global state (`admin.js:1-23`), `innerHTML` templates everywhere (`:192-593`); a few unescaped values (`store.id` `:201,244`).
C2. **All pagination/search client-side**: fetches every store (`:174`) and full ledger snapshot (`:255`), slices 100/page locally. Same O(history) problem as B1.
C3. Two backends, two auth headers derived by string replace of URL (`:67-78`).
C4. Inline `style=` blocked by own CSP (`:201` vs `admin.html:11`); `notice()` timer race (`:55-59`); `#resetPassword` null-deref on `current` (`:367`); native `confirm()/prompt()`.
C5. Tests use hand-made fake DOM + regex on `innerHTML` (`tools/admin-dashboard.test.cjs`); `test-admin-recovery.cjs` slices source text between string markers — breaks on rename. No type check, no lint.
C6. GitHub Pages only → no real headers (`frame-ancestors`, HSTS, cache-control); CSP via meta only.

### D. Repo / agent-friendliness

D1. `docs/CONTINUE_HERE.md` is a 741-line, 77 KB append-only log with stale "next step" sections (`:689-741` describes retired legacy routes). Agents told to read it every session (AGENTS.md) → burns context, contradicts itself.
D2. Docs sprawl: 21 tracked markdown (3.6k lines), `delivery/` 24 evidence files + 132 KB `.patch`, committed outdated APK `delivery/apk/Sadad-2.5.11-server-release.apk`, `docs/archive/releases/` 26 files. AGENTS.md rule "no second status page" already violated in spirit.
D3. No formatter/linter anywhere (no ktlint/spotless/detekt, no eslint/prettier, no `deno fmt/lint` config, no `.editorconfig`, no pre-commit). Code style = hand-compacted lines → agents can't rely on stable diffs.
D4. Flat package `com.sadad.app` with 37 files; nothing tells an agent where a feature lives except the AGENTS.md table.
D5. `.gitignore` `**/data/` would silently ignore a future `…/app/data/` source package (relevant to the target layout!).
D6. Contract lives in prose (`Supabase-Setup/docs/V3_CONTRACT_AR.md`) + 3 hand-mirrored copies (Java, TS, dashboard). Every protocol change = 3 manual edits with no check that they agree.

D7. CI: actions pinned by tag in `android-ci.yml`, SHA in `admin-pages.yml`; no instrumentation/emulator run; no release-size/perf budget; no migration lint against cloud.

### Root causes (why the symptoms above exist)

1. **No layers.** UI, data, network, crypto and policy share two god classes, so every fix lands in the same file and every change can break anything.
2. **JSON as the domain model.** Nothing is typed, so the compiler can't help. Money leaks to `double`, and typos fail silently.
3. **Snapshot-everything sync.** The server sends full state and the client re-decrypts and re-imports full state. Cost grows with history at every hop.
4. **Two and a half identity systems** (custom sessions, Supabase Auth envelope, admin header). The complexity piles up in login, logout and switch flows.
5. **Hand-built framework.** There are hand-written versions of ViewModel, Room, WorkManager, Navigation, RecyclerView, DI and an HTTP client. Each is partial and none is tested.
6. **No guardrails for agents.** There is no formatter, no single check command, and no contract source of truth. The status log only grows. As a result, agents re-derive context every session and produce wide, risky diffs.

---

## Part 2 — Target state ("done" = this is true)

### Success criteria (measurable)

| Area | Target | How measured |
| --- | --- | --- |
| UI thread | Zero disk, crypto or network work on main | StrictMode `penaltyDeath` in debug + instrumentation run |
| Write latency | Save debt or payment gives feedback in under 100 ms on a mid device | Macrobenchmark trace |
| Scale | Store with 5k contacts and 50k ops: contacts list scrolls with no frame over 32 ms; sync time flat | Macrobenchmark + seeded fixture |
| Idle sync | 1 request, under 2 KB, server under 300 ms p95 | Edge logs |
| Ledger op | Edge p95 under 200 ms (1 local JWT verify + 1 RPC) | Edge logs |
| Cold start | p50 under 1 s on a mid device with a Baseline Profile | Macrobenchmark `StartupTimingMetric` |
| Dashboard | First load under 1 s; JS under 100 KB gz; all lists paged server-side | Lighthouse in CI |
| Security | 0 open findings from B5; admin roles; one identity system | Review checklist + tests |
| Release | Production signing key, targetSdk current (36), R8 + resource shrink on, Play internal track | `android-release.yml` gates |
| Reliability | Crash-free sessions at or above 99.5% on internal track | Crash reporter |
| Agent DX | `./tools/check.sh` is the one gate; no file over 400 lines; a feature lives in one folder; contract has one source | CI script |

### Target architecture

**Android (single Gradle project, two modules — no more)**
```
:ledger-core   pure Kotlin/JVM: Cents value class, commands, projection, payment allocation,
               proof text, DTOs (kotlinx.serialization). No Android. Fast JVM tests, no org.json hack.
:app           Compose UI + Room + WorkManager + OkHttp; depends on :ledger-core
  com.sadad.app/
    ui/theme, ui/components          Material 3 theme from current palette, shared widgets
    feature/{auth,home,contacts,contact,debt,payment,history,reports,settings,recovery}/
        <Feature>Screen.kt  <Feature>ViewModel.kt  (UiState data class + StateFlow)
    data/db       Room: contacts, debts, payments, trash, ledger_meta, outbox (journal)
    data/net      LedgerApi (OkHttp + signing interceptor), AuthApi
    data/sync     SyncWorker (WorkManager), SyncRepository
    AppGraph.kt   manual DI container held by Application
```
Rules:
- Screens read one `StateFlow<LedgerView>` computed from an in-memory `LedgerState` saved as Room rows plus the outbox, and the read model is plaintext in Room (only outbox payloads and the refresh token are encrypted). Compose recomposes only what changed: no `refreshCurrentScreen`, no wiped forms.
- Form state lives in `SavedStateHandle`, so it survives process death.
- Navigation Compose with type-safe routes; predictive back for free.
- Money is `Cents(Long)` everywhere; it is formatted only in UI.
- Strings are in `res/values/strings.xml` (Arabic default).
- The outbox is a Room table. Payload is encrypted with **one** Keystore-wrapped AES data key, unwrapped once per process (envelope encryption). `state` and `operation_id` are plain columns, so queries never decrypt. Settled entries are compacted once the server outcome is in `ledger_meta`. (Room is for read models and the outbox only — the encrypted-journal crypto model would not survive a generic Room table, so no SQLCipher; see the deleted draft plan's reasoning.)
- Sync is driven by WorkManager: a unique `OneTimeWork` on write (network constraint, exponential backoff) plus a periodic job. There are no Activity-driven syncs and no hourly in-app poll.

**Libraries (each replaces hand-written code; nothing speculative)**
- Kotlin 2.4.x + Compose BOM `2026.09.00` + `org.jetbrains.kotlin.plugin.compose` (verified via Context7), Material 3, `activity-compose` 1.13, `lifecycle-viewmodel-compose` 2.11. These replace `NativeScreens`, `SadadDialog` and the custom toast and undo banner.
- Navigation Compose replaces the `ArrayDeque<NavigationEntry>` and `onBackPressed`.
- Room (KSP) replaces `SadadDatabase` raw SQL, `changeToken` cache and `onOpen` schema. Nothing is migrated from the old database (Part 3, clean-cut rule).
- WorkManager replaces `SyncScheduler`, `LedgerSyncService`, `statusPoll`, the connectivity callback and the `DueReminderManager` alarm.
- OkHttp + kotlinx.serialization + coroutines replace both `HttpURLConnection` clients and org.json.
- DataStore (Preferences) replaces the scattered `getPreferences(0)` keys.
- androidx.biometric replaces platform `BiometricPrompt` + `FingerprintManager`.
- `ActivityResultContracts` (CreateDocument/OpenDocument/PickVisualMedia) replace request codes. androidx.exifinterface.
- Baseline Profile + Macrobenchmark; a crash reporter (Sentry, with PII scrubbing; one tool across app, Edge and dashboard — Phase 4, per `docs/versions.md`).
- **Deliberately not added:**
  - Hilt: one container, a few consumers; add when manual graph hurts.
  - Retrofit: about 10 endpoints, and signing needs body bytes anyway.
  - Paging 3: only for history if measurement demands it.
  - Coil: one profile photo.
  - KMP.
  - Any MVI framework.
- Build toolchain: AGP `9.3.3`, Gradle `9.7.1`, `compileSdk 37`, `targetSdk 36` (ticket 68) — Compose BOM `2026.09.00` (Compose 1.12) requires `compileSdk 37`, which requires AGP ≥ 9.2 and Gradle 9.

**Backend (Supabase, consolidated)**
- **One identity: Supabase Auth.** Store users and admins are Auth users. Role goes in `app_metadata` (`admin | support | store`) via a custom access-token hook. Device limit and subscription checks stay in SQL (`current_capability`).
  - This deletes custom scrypt sessions, `sadad_auth_bindings`, the `x-sadad-session` legacy token, `X-Sadad-Admin`, and the whole `sadad-auth` gateway.
  - The app talks to `/auth/v1/token` directly; the publishable key is enough.
- **One Edge function** `api`: Hono router + zod schemas. Routes are `/v1/mobile/*` and `/v1/admin/*`. JWT is verified with `auth.getClaims()` (asymmetric signing keys, local JWKS; Context7-confirmed as Supabase's recommended faster path). This cuts per-request round trips from 4 to 2.
- **Delta sync:**
  - `GET /v1/mobile/changes?after=<cursor>` returns `sadid_events` rows after the cursor; the table already exists with PK `(store_id, sequence)`.
  - First sync pages `changes` from 0 (or on epoch change).
  - `POST /v1/mobile/operations` takes a batch (≤100) in one signed request and returns per-op receipts.
  - Lease renewal happens only when under 24 h remain; enroll only when the installation is not active.
- **Read paths:** no `FOR UPDATE` on `stores`; a read-only consistent query is enough.
- **Schema hygiene:**
  - The migration chain is squashed into one baseline generated from `supabase/schemas/`; the test project is reset to it once.
  - One declarative file per routine (`supabase/schemas/` + `supabase db diff` generates migrations), so the deployed `apply()` is readable in one place.
  - Rows get pruned inline, per installation, by the routine that writes them: nonces after 15 minutes, capabilities once expired. Supabase Auth owns `auth.sessions`.
  - Missing indexes get added.
  - The per-store dashboard loop becomes a set-based query.
- **Single contract source:**
  - zod schemas generate OpenAPI (`@hono/zod-openapi`), then `openapi-typescript` types for the dashboard.
  - Kotlin DTOs are checked by shared JSON fixtures in `contract/fixtures/` that both test suites load.
  - `contract/errors.json` (code → Arabic message) is the one table; a Gradle task generates the Kotlin map and the function imports it.
- **Deploy:** a GitHub Actions job runs `supabase db push` + `functions deploy` behind a protected environment (manual approval). `config.toml` declares every function.

**Dashboard (static, typed)**
- Vite + TypeScript. ES modules per view (`stores`, `store`, `ledger`, `installations`, `recovery`, `audit`), generated API types, and `lit-html` for templating (auto-escaping removes the XSS class; about 3 KB).
- Server-side pagination and search on every list endpoint (`?cursor&limit&q`).
- Biome for lint + format. Vitest + happy-dom for units, one Playwright smoke test against the preview build.
- Hosting: keep GitHub Pages, or move to Cloudflare Pages for real security headers (`_headers`: CSP, `frame-ancestors`, HSTS). Decide in Phase 2.

**Repo / agents**
- `AGENTS.md` stays under 150 lines: map, rules, one check command. `CLAUDE.md` is a one-line pointer to it.
- `docs/CONTINUE_HERE.md` is reset to under 80 lines of **current state only**; history goes to CHANGELOG/git.
- Decisions go in `docs/adr/NNN-*.md`. `docs/ARCHITECTURE.md` = Part 2 of this doc.
- `delivery/` evidence, `.patch` files, the committed APK and `docs/archive/releases/` are removed from the tree (tag first; still in history).
- `./tools/check.sh` runs format check, lint, all tests and builds. CI calls the same script, so an agent runs exactly what CI runs.
- Formatters: ktfmt via Spotless (detekt dropped 2026-10-15: no stable release for Gradle 9 / Kotlin 2.4); `deno fmt`/`deno lint`; Biome.
- Fix `.gitignore` `**/data/` (it would hide `app/.../data/`). Untrack `.temp/linked-project.json`. Pin all actions by SHA.

---

## Part 3 — Phased plan

### The clean-cut rule (user decision, 2026-10-15)

Every store, user and row is test data. The plan rebuilds; it never migrates, bridges or keeps an old thing alive:

- **No data migration.** Old local databases, journals, cloud rows and accounts are dropped. Testers reinstall and get fresh test accounts.
- **No compatibility.** No old-client routes, no dual login, no feature flags between old and new, no period where old and new backends both serve.
- **No bridges.** New code never wraps or adapts old code: no Java↔Kotlin adapters, no `JSONObject` shims around the new core, no `AndroidView` hosting of old screens. Old code is deleted when its replacement switches on.
- **Replace and delete in one phase.** A phase is not done while the code, table, function or route it replaces still exists.

If a ticket seems to need an upgrade path, a fallback or an adapter, it is wrong. Stop and ask.

**Accepted gap:** the Phase 2 cutover deletes the backend the current Java app talks to. From then until the Phase 3 switch there is no working Android app. The dashboard moves in Phase 2, so admins keep working.

Each phase ends green on the check commands (see Verification), with a CHANGELOG line and a CONTINUE_HERE update. Cloud steps (reset, migrations, deploys, Auth changes) need explicit user approval per AGENTS.md.

### Phase 0 — Stop the bleeding (S) — done 2026-10-15

Security fixes (exact username match, enroll nonce, function config, duplicate admin routes), off-main writes, sync keeps open forms, `removeUnseen` without per-row binds, dead code. Deployed: `sadad-api` v10, `sadid-ledger` v9, migration `20261014121200`. Verified on a phone; the defects found there only matter until the Phase 2 cutover.

### Phase 1 — Foundation (S) — done 2026-10-16

Tooling for the code that will be written, not for the code that will be deleted.

1. `tools/check.sh` is the one gate, and CI calls it. It runs whatever exists: Gradle tests and build, `deno test` per function, pglite suites, dashboard tests.
2. Formatters and linters apply to new code only: ktfmt via Spotless on Kotlin sources (detekt dropped 2026-10-15: no stable release for Gradle 9 / Kotlin 2.4), `deno fmt`/`deno lint` on the new `api` function, Biome on the new dashboard. Java, the old functions and `admin.js` are never reformatted; they are deleted.
3. Kotlin + Compose plugins and catalog entries; unused catalog entries removed.
4. Docs reset: `CONTINUE_HERE.md` under 80 lines of current state, `AGENTS.md` trimmed, `CLAUDE.md` pointer, `delivery/` and release archives pruned (tag first).
   - Exit: `check.sh` green locally and in CI; a Kotlin smoke test compiles.

### Phase 2 — New backend and dashboard, then cutover (L) — merged 2026-10-17; cutover (ticket 27) pending

Built and tested locally (pglite + Deno), then switched on in one cloud reset.

1. **Schema baseline:** declarative files in `Supabase-Setup/supabase/schemas/`, one file per table group and one per routine. A single baseline migration is generated from them. The 26 old migration files leave the tree (git history keeps them). Contents:
   - ledger tables (`sadid_*`), events with a `(store_id, sequence)` cursor, operations and outcomes;
   - `stores` without password columns; `store_memberships(user_id → auth.users)`; `installations` as the only device registry (label, staff name, permissions, status), capped by `stores.allowed_devices`;
   - `apply()` in one readable definition, batch apply, `changes(after, limit)`, no `FOR UPDATE` on read paths;
   - status and subscription enforced in `current_capability`;
   - B8 indexes; inline per-installation pruning of nonces (after 15 minutes) and capabilities (once expired); set-based admin summaries.
2. **One identity (ADR-002):** Supabase Auth only. A store is the Auth user `<username>@stores.sadad.invalid`. An admin is an Auth user with `app_metadata.role` = `admin` or `support`; the JWT already carries `app_metadata`, so no hook. `force_password_change` lives in `app_metadata`. Supabase Auth rate limits replace `login_rate_limits`.
3. **One function `api`:** Hono + zod + `auth.getClaims()`. One auth middleware, one CORS/error module, error codes from `contract/errors.json`.
   - `/v1/mobile/*`: enroll, lease, `changes` (first sync pages `changes` from 0), batch `operations` (≤100, per-op receipts), recovery.
   - `/v1/admin/*`: stores (create and reset password through the Auth admin API), subscription, installations approve/revoke, recovery approve, ledger view, summaries, audit. Role checks from claims; `support` is read-only.
   - `contract/`: zod schemas → OpenAPI (`@hono/zod-openapi`) → `openapi-typescript` types; JSON fixtures that the Deno tests and later the Kotlin tests both load.
4. **Dashboard:** Vite + TypeScript + lit-html in `server/public/`, Supabase Auth sign-in, generated API types, server-side paging and search, role-aware UI, Biome + Vitest + one Playwright smoke. `tools/build-admin-pages.cjs` becomes `vite build` → `_site/`.
5. **Deploy automation:** a GitHub Actions workflow behind a protected environment (manual approval) applies the schema and deploys `api`. `config.toml` declares only `api`.
6. **Cutover (human, approved):** reset the test project (drop the old schemas, Auth users, functions and migration history), apply the baseline, deploy `api`, create the first admin, publish the dashboard. Then delete from the tree: `sadad-api`, `sadad-auth`, `sadid-ledger`, the old pglite scripts and dashboard tests, and `admin.js`.
   - Exit: pglite + Deno + dashboard tests green; a Deno smoke script against the live project signs in as a store, enrolls a generated P-256 installation, pushes a batch and reads `changes`; the dashboard creates a store and approves that installation; no old function exists in the project.

### Phase 3 — New Android app (L)

Written directly in Kotlin inside `app/` against the Phase 2 contract.

**Phase 3 decisions (user, 2026-10-17):**
- The Java app is deleted first (ticket 39), not at the end.
- Screens read one `StateFlow<LedgerView>` computed from an in-memory `LedgerState` saved as Room rows, and the read model is plaintext in Room (only outbox payloads and the refresh token are encrypted).
- `minSdk` 26, so no core library desugaring.
- Server fixes land before the cutover (tickets 34–36).
- Room and Compose tests run on Robolectric.
- Performance is measured on a store seeded in the test project through the API (5,000 Contacts, 50,000 operations).
- The offline lease is checked against a pinned public key and does not survive a reboot.
- Phase 3 is a rewrite with the Java app's features, with no new features (store-permission flags and device limits stay as they are).

1. `:ledger-core` (pure Kotlin/JVM) — done (tickets 16, 17): `Cents(Long)`, typed commands, projection over `changes` events, payment allocation, proof text. The behaviour of today's `LedgerProjectionTest`/`LedgerCommandTest`/`LedgerQueueTest` is rewritten as Kotlin tests against the new API; they never call Java.
2. Data layer: Room (read model + outbox), the outbox payload encrypted with one Keystore-wrapped AES key, DataStore, OkHttp `AuthApi` (`/auth/v1/token`) and `LedgerApi` with a signing interceptor, `SyncWorker` on WorkManager, `AppGraph`.
3. Compose UI: `ComponentActivity` + `NavHost` + ViewModels reading `StateFlow<LedgerView>`; screens in order: login/unlock → home → contacts → contact detail → contact/debt/payment forms → history/reports → settings/recovery. Each screen has a Compose UI test. PDF/Excel exporters ported to Kotlin on IO.
4. **Release hardening:** enable R8 + `shrinkResources` with tested keep rules (the Java app, the `standalone` flavor, `STANDALONE_MODE`, the `"123"` login, the old `androidTest` runners and `res` leftovers are deleted first in ticket 39). Testers reinstall.
5. Baseline Profile + Macrobenchmark module (the one allowed extra module, test-only); StrictMode `penaltyDeath` in debug.
   - Exit: on a phone, login → add contact/debt/payment offline → sync → switch account A→B→A → logout; no StrictMode violations; Part 2 UI-thread, scroll, start and write-latency targets met; no `.java` file left under `app/src/main`.

### Phase 4 — Production hardening (M)

Decisions of 2026-10-21: Sentry stays; no Play Console and no internal track (the signed APK is sideloaded); after the exit a teammate owns QA and polish and chooses how to report.

1. targetSdk 36, edge-to-edge and predictive back (ticket 68).
2. Crash reporter across app, Edge and dashboard with PII scrubbing (tickets 69–71); production signing key and the first signed release (ticket 72, human).
3. Perf budgets in CI: APK size and Baseline Profile presence fail the build; Macrobenchmark on a Gradle Managed Device if the runner allows, else manual pre-release (ticket 73).
4. `docs/RUNBOOK.md` (ticket 74) and a backup/restore drill on a Supabase branch (ticket 75, human).
5. Accessibility pass: 48dp targets, TalkBack labels, font scale; Compose semantics tests (ticket 76).
   - Exit: the signed release build passes the exit run (ticket 77, human); docs and `docs/QA.md` handoff (ticket 78). Part 2 table all green; "done".

---

## Critical files

- App: `app/src/main/java/com/sadad/app/{MainActivity,NativeScreens,SadadDatabase,EncryptedJournal,LedgerQueue,SignedLedgerSync,LedgerClient,SessionStore,SyncScheduler,LedgerSyncService,DueReminderManager}.java`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`
- Backend: `Supabase-Setup/supabase/functions/{sadad-api,sadad-auth,sadid-ledger}/`, `migrations/20261010102007_*` (snapshot, lock), `20261010140258_*` (apply), `20261010100856_*` (enroll), `config.toml`
- Dashboard: `server/public/admin.{html,js,css}`, `tools/build-admin-pages.cjs`, `.github/workflows/{android-ci,admin-pages,android-release}.yml`
- Docs: `AGENTS.md`, `docs/CONTINUE_HERE.md`, `docs/CHANGELOG.md`, new `docs/ARCHITECTURE.md`, `docs/adr/`

Carry over as behaviour, not as code (the clean-cut rule deletes the old files):
- The signed-ledger protocol: idempotent `operationId`, the `InstallationKey` proof format, epochs, receipts, offline lease, recovery. Its JVM tests (`SignedLedgerSyncTest`, `LedgerProjectionTest`, `LedgerClientTest`) are the checklist for the new Kotlin tests.
- The pglite approach from `Supabase-Setup/scripts/test-v3-ledger.mjs` for testing SQL without a local stack.
- `sadid_events` sequence for delta sync.
- `LedgerTablePdf`/`LedgerTableExcel` layout rules for the Kotlin exporters.

## Verification

- Every phase: `./tools/check.sh` (Phase 1 onward); before that, `./gradlew testServerDebugUnitTest assembleServerDebug`, `deno test -A` per function, and the `node Supabase-Setup/scripts/*.mjs` pglite suites.
- Phase 0 (done 2026-10-15): unit tests for the wildcard and enroll replay; `LedgerCorrectnessInstrumentation` on a phone; manual flow offline add → online sync → type in a form during sync.
- Phase 2: pglite tests on the baseline schema (claims-based authorization, roles, device cap, status gate, `changes`, batch, no read locks); Deno tests per route with contract fixtures; dashboard Vitest + Playwright; Supabase branch load test with a seeded 50k-op store; the live smoke script after cutover.
- Phase 3: Kotlin unit tests for `:ledger-core` and the data layer with the contract fixtures; Compose UI tests per screen; the phone flow in the Phase 3 exit; Macrobenchmark startup/scroll/write traces versus Part 2 targets.
- Phase 4: signed release via `android-release.yml` gates; Play internal track install; restore drill on a branch.

---

## Pre-step — Branch housekeeping (requested 2026-10-14)

Goal: no archive branch is left that could be pushed by accident, and nothing is lost.

Facts checked:
- `archive/pre-consolidation` = tag `pre-consolidation-2026-10-11` = `2075d05`.
- `archive/pre-supabase-ledger-correctness` = tag `pre-supabase-ledger-correctness-2026-10-12` = `6887693`.
- So both branches are redundant with tags that already preserve the same commits.
- `fork` (public) already has `archive/pre-consolidation` + its tag.
- The three new local archives hold private evidence (2.5.13 APK, QA screenshot, cutover patch) and an old dashboard draft. They have never been pushed.
- A quick secret scan of `archive/pre-consolidation` found only a test fixture (`password: "password"` in `handler_test.ts`). AGENTS.md says old credentials existed in pre-cleanup trees, and that tree is public on the fork, so **confirm those credentials were rotated** (user check; not verifiable from here).

Steps:
1. `mkdir ~/sadad-private` (outside the repo).
2. `git bundle create ~/sadad-private/sadad-archives-2026-10-14.bundle archive/private-evidence-2026-10-13 archive/reviewed-local-consolidation-2026-10-13 archive/f0dd-dashboard-draft`, then `git bundle verify` on it.
3. `git branch -D` those three branches. This is safe only after the verify in step 2 passes.
4. `git branch -D archive/pre-consolidation archive/pre-supabase-ledger-correctness` (the tags keep the commits), then `git push fork --delete archive/pre-consolidation` (the tag stays).
5. Edit the AGENTS.md "الحفظ والاسترجاع" section so it references the two tags only (drop the branch names). Add one line for the bundle path and the restore command:
   `git fetch ~/sadad-private/sadad-archives-2026-10-14.bundle 'refs/heads/archive/*:refs/heads/archive/*'`
6. Result:
   - Local branches: `main` only.
   - Remote branches on fork: `main` only.
   - Nothing private is left in refs that `git push` or `git push --all` could send.
   - No hook needed (YAGNI).

Verify:
- `git branch -a` shows only `main`, `fork/main` and the origin refs.
- `git bundle list-heads` on the bundle shows all 3 archives.
- `git tag -l` still lists both preservation tags.

---

## Execution setup — cheaper model in OpenCode (v2.0.28, `~/.opencode/bin/opencode`)

Split by model strength:
- **Planning in Claude Code, this session.** Run `/setup-matt-pocock-skills` → `/to-spec` → `/to-tickets` here. The audit context lives only in this conversation.
- **Implementing in OpenCode.** A cheaper model works one ticket per fresh session.

### What already works in OpenCode (verified in OpenCode docs via Context7)
- OpenCode auto-loads `AGENTS.md` (repo) + `~/.config/opencode/AGENTS.md` (global; it already has the Context7 rule).
- OpenCode discovers skills from `~/.claude/skills/*/SKILL.md`, so all of Matt's skills (`tdd`, `implement`, `to-tickets`, `diagnosing-bugs`, `code-review`, …) are visible.
- **Every discovered skill becomes a slash command**, so `/implement`, `/tdd` etc. work.
- OpenCode v2.0.29 honours `disable-model-invocation`: such skills (`implement`, `to-spec`, `to-tickets`, …) run only as typed slash commands, never model-loaded. The ticket prompt covers what `implement` says.
- Context7 MCP is already configured globally in `~/.config/opencode/opencode.json`. That file holds an API key, so **never copy it into the repo's `opencode.json`** (public repo).

### Gaps to fix before the first OpenCode ticket (setup ticket "00")
1. **Ponytail is not active in OpenCode.** It only runs as a Claude Code hook here.
   - Add `"plugin": ["@dietrichgebert/ponytail"]` to `~/.config/opencode/opencode.json`. This is from the ponytail README "OpenCode" section; it injects the rules every turn and adds the `/ponytail-*` commands.
   - Offline alternative: point to the absolute path `~/.claude/plugins/cache/ponytail/ponytail/4.10.0/.opencode/plugins/ponytail.mjs`.
2. **Git/deploy guardrails.** `git-guardrails-claude-code` is a Claude hook, so it does nothing in OpenCode. Use OpenCode permissions in a repo-root `opencode.json` (no secrets in it).
   - Deny: `git push*`, `git reset --hard*`, `git branch -D*`, `git clean*`, `git rebase*`, `supabase db push*`, `supabase functions deploy*`, `supabase migration repair*`.
   - Ask: `rm *`, `gh *`.
   - Allow: `./gradlew *`, `deno *`, `node *`, `git status*`, `git diff*`, `git log*`, `git add*`, `git commit*`.
   - The v2 docs show two shapes (`"permission": {"bash": {...}}` object and a newer `"permissions": [...]` array). Use the shape your `opencode` validates against its `$schema`, then **prove it**: in OpenCode, ask the agent to run `git push --dry-run fork main`. It must be blocked.
3. **Skill noise.**
   - `~/.config/opencode/skills/` holds 20 `speckit-*` skills, and `~/.claude/skills` holds more. A cheap model may pick the wrong spec system.
   - Add to repo `AGENTS.md`: "Use only: implement, tdd, diagnosing-bugs, code-review, ponytail, supabase skills, writing-for-agents (doc tickets). Never use speckit-* or to-spec/to-tickets during implementation."
4. **The `CLAUDE.md` pointer** (Part 2) stays for Claude Code. OpenCode prefers `AGENTS.md`, so `AGENTS.md` remains the one source.

### Ticket quality bar (pass this text to `/to-tickets`)
Every ticket file must be self-contained, because the implementer has no memory of this conversation:
- **Context:** 2–4 sentences + a link to the `docs/ARCHITECTURE.md` section (e.g. "A1", "B5").
- **Files:** exact paths + line anchors to read first; "files you may edit" and "files you must not edit".
- **Test first:** name of the failing test, its file, and the assertion it makes (TDD red step).
- **Steps:** numbered, one change per step, each with the command that proves it (`./gradlew testServerDebugUnitTest --tests '*X*'`, `deno test -A <file>`, `node Supabase-Setup/scripts/<x>.mjs`).
- **Done when:** checklist; the last item is always the full check command passing (`./tools/check.sh` once it exists; before that the 4 commands in Verification).
- **Out of scope:** an explicit list.
- **Stop and ask the human if:**
  - a cloud or remote command is needed;
  - a test outside the ticket breaks;
  - an edit is needed in an unlisted file;
  - a money, crypto or auth rule seems wrong;
  - the ticket text and the code disagree.
- **Risk:** `low | high`.
  - `high` = touches money math, crypto/signing, migrations, auth, or sync.
  - Run `high` tickets with a stronger model, or at least run `/code-review` with a stronger model before committing.
- **Commit:** a single commit to `main` (or a ticket branch if preferred), with the message given in the ticket. No push.

### Per-ticket session loop (OpenCode)
1. `/new` (fresh session) → `/implement .scratch/<feature>/issues/NN-<slug>.md`
2. The agent works red → green via `tdd`, runs the ticket's check commands, then `code-review`, then commits.
3. Human: read the diff, push yourself (`git push fork main`), and mark the ticket file `Status: done`.
4. Next ticket = any ticket whose "Blocked by" are all done.
