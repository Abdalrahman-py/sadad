# Sadad — Architecture Audit & Production Plan

## Context

User paused feature work to define "done": a production-ready Sadad (Android app + Supabase backend + admin dashboard) that is fast, maintainable, scalable, and easy for AI agents to work in. This doc = (1) senior audit of where code departs from best practice, with evidence, (2) target state, (3) phased plan.

Decisions taken (user, 2026-10-14):
- Android: **strangler migration to Kotlin + Compose** inside `app/`, screen by screen, ledger core kept and ported behind tests.
- **Drop standalone edition** (`standalone` flavor, `STANDALONE_MODE` branches, `"123"/"123"` local login).
- Dashboard: **stay static, move to TypeScript + Vite build + typed API client**.
- Backend: **consolidate on Supabase** — one auth system (Supabase Auth), shared Edge module, delta sync, batched ops, generated types.

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
- UI reads Room `Flow`s. A sync writes Room and Compose recomposes only what changed: no `refreshCurrentScreen`, no wiped forms.
- Form state lives in `SavedStateHandle`, so it survives process death.
- Navigation Compose with type-safe routes; predictive back for free.
- Money is `Cents(Long)` everywhere; it is formatted only in UI.
- Strings are in `res/values/strings.xml` (Arabic default).
- The outbox is a Room table. Payload is encrypted with **one** Keystore-wrapped AES data key, unwrapped once per process (envelope encryption). `state` and `operation_id` are plain columns, so queries never decrypt. Settled entries are compacted once the server outcome is in `ledger_meta`. (Room is for read models and the outbox only — the encrypted-journal crypto model would not survive a generic Room table, so no SQLCipher; see the deleted draft plan's reasoning.)
- Sync is driven by WorkManager: a unique `OneTimeWork` on write (network constraint, exponential backoff) plus a periodic job. There are no Activity-driven syncs and no hourly in-app poll.

**Libraries (each replaces hand-written code; nothing speculative)**
- Kotlin 2.4.x + Compose BOM `2026.09.00` + `org.jetbrains.kotlin.plugin.compose` (verified via Context7), Material 3, `activity-compose` 1.13, `lifecycle-viewmodel-compose` 2.11. These replace `NativeScreens`, `SadadDialog` and the custom toast and undo banner.
- Navigation Compose replaces the `ArrayDeque<NavigationEntry>` and `onBackPressed`.
- Room (KSP) replaces `SadadDatabase` raw SQL, `changeToken` cache and `onOpen` schema.
- WorkManager replaces `SyncScheduler`, `LedgerSyncService`, `statusPoll`, the connectivity callback and the `DueReminderManager` alarm.
- OkHttp + kotlinx.serialization + coroutines replace both `HttpURLConnection` clients and org.json.
- DataStore (Preferences) replaces the scattered `getPreferences(0)` keys.
- androidx.biometric replaces platform `BiometricPrompt` + `FingerprintManager`.
- `ActivityResultContracts` (CreateDocument/OpenDocument/PickVisualMedia) replace request codes. androidx.exifinterface.
- Baseline Profile + Macrobenchmark; a crash reporter (Sentry, with PII scrubbing; one tool across app, Edge and dashboard).
- **Deliberately not added:**
  - Hilt: one container, a few consumers; add when manual graph hurts.
  - Retrofit: about 10 endpoints, and signing needs body bytes anyway.
  - Paging 3: only for history if measurement demands it.
  - Coil: one profile photo.
  - KMP.
  - Any MVI framework.

**Backend (Supabase, consolidated)**
- **One identity: Supabase Auth.** Store users and admins are Auth users. Role goes in `app_metadata` (`admin | support | store`) via a custom access-token hook. Device limit and subscription checks stay in SQL (`current_capability`).
  - This deletes custom scrypt sessions, `sadad_auth_bindings`, the `x-sadad-session` legacy token, `X-Sadad-Admin`, and the whole `sadad-auth` gateway.
  - The app talks to `/auth/v1/token` directly; the publishable key is enough.
- **One Edge function** `api`: Hono router + zod schemas. Routes are `/v1/mobile/*` and `/v1/admin/*`. JWT is verified with `auth.getClaims()` (asymmetric signing keys, local JWKS; Context7-confirmed as Supabase's recommended faster path). This cuts per-request round trips from 4 to 2.
- **Delta sync:**
  - `GET /v1/mobile/changes?after=<cursor>` returns `sadid_events` rows after the cursor; the table already exists with PK `(store_id, sequence)`.
  - Full snapshot only on first sync or epoch change.
  - `POST /v1/mobile/operations` takes a batch (≤100) in one signed request and returns per-op receipts.
  - Lease renewal happens only when under 24 h remain; enroll only when the installation is not active.
- **Read paths:** no `FOR UPDATE` on `stores`; a read-only consistent query is enough.
- **Schema hygiene:**
  - One declarative file per routine (`supabase/schemas/` + `supabase db diff` generates migrations), so the deployed `apply()` is readable in one place.
  - Rows get pruned by pg_cron: expired capabilities, nonces and sessions.
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
- Hosting: keep GitHub Pages, or move to Cloudflare Pages for real security headers (`_headers`: CSP, `frame-ancestors`, HSTS). Decide in Phase 6.

**Repo / agents**
- `AGENTS.md` stays under 150 lines: map, rules, one check command. `CLAUDE.md` is a one-line pointer to it.
- `docs/CONTINUE_HERE.md` is reset to under 80 lines of **current state only**; history goes to CHANGELOG/git.
- Decisions go in `docs/adr/NNN-*.md`. `docs/ARCHITECTURE.md` = Part 2 of this doc.
- `delivery/` evidence, `.patch` files, the committed APK and `docs/archive/releases/` are removed from the tree (tag first; still in history).
- `./tools/check.sh` runs format check, lint, all tests and builds. CI calls the same script, so an agent runs exactly what CI runs.
- Formatters: ktfmt via Spotless; detekt with the `LargeClass` / `LongMethod` / `MaxLineLength=140` rules; `deno fmt`/`deno lint`; Biome.
- Fix `.gitignore` `**/data/` (it would hide `app/.../data/`). Untrack `.temp/linked-project.json`. Pin all actions by SHA.

---

## Part 3 — Phased plan

Each phase ends green on `./tools/check.sh` + CI, and gets a CHANGELOG line and a CONTINUE_HERE update. Cloud steps (migrations, function deploys, auth changes) need explicit user approval per AGENTS.md. Phases 3–4 and 6 can run in parallel after Phase 2.

### Phase 0 — Stop the bleeding (S)
1. Security:
   - `findAccount` uses `.eq` on the normalized lowercase username, in both copies. Add a test with `a_c`.
   - Record the enroll nonce.
   - Delete the unused `sadid-ledger/admin.ts` identity routes if the dashboard truly doesn't call them (grep first).
   - Add `[functions.sadid-ledger] verify_jwt=false` to `config.toml`.
2. App correctness:
   - Move `addContact`/`addDebt`/`addPayment` onto the existing executor with callbacks, reusing the `updateContactAsync` pattern (`MainActivity.java:682-716`).
   - Don't `refreshCurrentScreen()` while on a form route.
   - `removeUnseen` uses a temp table join instead of `NOT IN (?…)`, and refuses to wipe on an empty view.
   - Remove the double `markLedgerChanged`.
3. Hygiene:
   - Fix `.gitignore` `**/data/` and untrack `.temp`.
   - Remove dead code (A8 list).
   - Commit this audit as `docs/ARCHITECTURE.md` and delete the untracked `ARCHITECTURE_MIGRATION_PLAN.md`.
   - Exit: existing tests + new regression tests green; manual phone check of add/pay/sync.

### Phase 1 — Foundation for agents and Kotlin (M)
1. Add `tools/check.sh` and wire CI to it; add the formatters/linters (format-only commit first, so later diffs stay small).
2. Add Kotlin + Compose plugins and the version catalog entries; delete unused catalog entries; enable R8 + `shrinkResources` with tested keep rules.
3. **Drop standalone:** remove the flavor, the `STANDALONE_MODE` branches, the `"123"` login, the `src/standalone/` manifest and the CI build step. ADR-001.
4. Create `:ledger-core` (Kotlin):
   - Port `LedgerCommand`, `LedgerProjection`, payment allocation and `Cents`.
   - Port `LedgerProjectionTest`/`LedgerCommandTest`/`LedgerQueueTest` first; the tests are the contract.
   - The Java callers use the Kotlin API.
5. Reset `CONTINUE_HERE.md`, trim `AGENTS.md`, add the `CLAUDE.md` pointer, prune `delivery/` + archives.
   - Exit: same app behaviour, smaller tree, `check.sh` green, ledger-core coverage at or above the old tests.

### Phase 2 — Backend sync v4, additive (M)
1. Migrations (via declarative `schemas/`):
   - `sadid_changes(after bigint, limit int)` reading `sadid_events`.
   - Batch apply wrapper.
   - Read paths drop `FOR UPDATE`.
   - Indexes from B8; pg_cron cleanup.
   - Set-based `admin_summaries` without per-load audit spam.
   - Consolidate `apply()` into one readable definition.
2. Add the new `api` function (Hono + zod + `getClaims`) serving `/v1/mobile/changes`, `/v1/mobile/operations` (batch), lease and enroll. v3 routes stay live until the app is cut over.
3. Add `contract/` (fixtures + `errors.json`) and OpenAPI generation; pglite tests for changes, batch and the lock change. Make `test-v3-authorization.mjs` load the real migrations or delete it.
4. Deploy automation workflow with an approval gate.
   - Exit: pglite + Deno tests green; staging (Supabase branch) load test with a 50k-op fixture shows flat sync time; user approves the cloud apply.

### Phase 3 — Android data layer (L)
1. Room DB (read model + outbox), `AppGraph`, DataStore, OkHttp `LedgerApi` with a signing interceptor, `SyncWorker` using v4 delta + batch.
2. One-time migration:
   - Read the old `EncryptedJournal` and copy non-settled entries into the outbox **preserving operationIds**.
   - Drop the old SQLite read model and rebuild it from `changes`.
   - Test on a device with pending entries.
3. Repositories expose `Flow`. The legacy `NativeScreens` reads repositories through a thin Java-facing adapter, so the UI is unchanged but now off the main thread.
4. StrictMode `penaltyDeath` in debug.
   - Exit: instrumentation `LedgerCorrectnessInstrumentation` + `HardwareLedgerInstrumentation` pass on a phone; no StrictMode violations; journal growth bounded.

### Phase 4 — Compose UI strangler (L)
1. `MainActivity` → `ComponentActivity` + `NavHost` + `enableEdgeToEdge()`.
   - Change `NativeScreens` so builders return a `View` instead of calling `host.setContentView` (5 call sites: `NativeScreens.java:129,212,235,259,312`).
   - Un-migrated routes render through `AndroidView`, so old and new screens coexist in one nav graph.
2. Migration order, one PR per screen with a Compose UI test each: settings → login/unlock → home → contacts → contact detail → contact/debt/payment forms → history/reports → recovery/admin flows.
3. Delete `NativeScreens`, `SadadDialog`, custom toast/undo and the old `MainActivity` code when the last route moves. Port the PDF/Excel exporters to Kotlin, running on IO.
4. Baseline Profile + Macrobenchmark module (the one allowed extra module, test-only).
   - Exit: Part 2 UI-thread, scroll, start and write-latency targets met.

### Phase 5 — One identity (M)
1. Migrate store and admin principals to Supabase Auth users. Data is test data, so this is a password reset: admin-issued temporary passwords, `force_password_change` kept as an `app_metadata` flag. Roles go in the access-token hook.
2. App logs in via Auth directly; login/logout/switch account each manage **one** session. That fixes the open A→B→A account switching item.
3. Delete `sadad-auth`, the custom session tables/functions and `sadad_auth_bindings`; fold the remaining sadad-api admin routes into `api`, then delete `sadad-api` and the v3 routes once app + dashboard are cut over. ADR-002.
   - Exit: one auth path tested end to end; old functions undeployed after the user approves.

### Phase 6 — Dashboard (M, parallel after Phase 2)
1. Vite + TS project in `server/public/` (keeping the AGENTS.md path); `tools/build-admin-pages.cjs` replaced by `vite build` → `_site/`.
2. Split views; lit-html; generated API types; server pagination/search; role-aware UI (support = read-only).
3. Biome, Vitest, Playwright smoke; hosting headers decision.
   - Exit: Lighthouse budget met; all lists server-paged; XSS test suite green.

### Phase 7 — Production hardening (M)
1. targetSdk 36 (edge-to-edge and predictive back are already handled by Compose).
2. Production signing key (open item); Play internal track; crash reporter live.
3. Perf budgets in CI (APK size, Macrobenchmark on a Gradle Managed Device if the runner allows, else manual pre-release).
4. Backup/restore drill on a Supabase branch (the full cloud restore with grants is still untested per CONTINUE_HERE); runbook in `docs/RUNBOOK.md`.
5. Accessibility pass: 48dp targets, TalkBack labels, font scale; Compose semantics tests.
   - Exit: Part 2 table all green; "done".

---

## Critical files

- App: `app/src/main/java/com/sadad/app/{MainActivity,NativeScreens,SadadDatabase,EncryptedJournal,LedgerQueue,SignedLedgerSync,LedgerClient,SessionStore,SyncScheduler,LedgerSyncService,DueReminderManager}.java`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`
- Backend: `Supabase-Setup/supabase/functions/{sadad-api,sadad-auth,sadid-ledger}/`, `migrations/20261010102007_*` (snapshot, lock), `20261010140258_*` (apply), `20261010100856_*` (enroll), `config.toml`
- Dashboard: `server/public/admin.{html,js,css}`, `tools/build-admin-pages.cjs`, `.github/workflows/{android-ci,admin-pages,android-release}.yml`
- Docs: `AGENTS.md`, `docs/CONTINUE_HERE.md`, `docs/CHANGELOG.md`, new `docs/ARCHITECTURE.md`, `docs/adr/`

Reuse, don't rewrite:
- Signed-ledger protocol and its JVM tests (`SignedLedgerSyncTest`, `LedgerProjectionTest`, `LedgerClientTest`).
- pglite migration harness (`Supabase-Setup/scripts/test-v3-ledger.mjs`).
- `updateContactAsync` off-main pattern (Phase 0).
- `sadid_events` sequence for delta sync.
- `InstallationKey` proof format.
- `LedgerTablePdf`/`LedgerTableExcel` logic.

## Verification

- Every phase: `./tools/check.sh` (Phase 1 onward); before that, `./gradlew testServerDebugUnitTest assembleServerDebug`, `deno test -A` per function, and the `node Supabase-Setup/scripts/*.mjs` pglite suites.
- Phase 0: new unit tests (ilike wildcard rejected; enroll replay rejected; `removeUnseen` with 5,000 rows on an API 24 emulator); manual phone flow: add contact + debt + payment offline, then online sync, while typing in a form during sync.
- Phase 2: pglite tests for `changes`/batch/lock; Supabase branch load test with a seeded 50k-op store comparing v3 snapshot vs v4 changes payload and latency.
- Phase 3–4: instrumentation suites on a real phone (`QaInstrumentation` runner, `com.sadad.qa`); StrictMode clean; Macrobenchmark startup/scroll/write traces versus Part 2 targets.
- Phase 5: end-to-end login → sync → switch account A→B→A → logout on a phone + dashboard admin/support role tests.
- Phase 6: Vitest + Playwright against `vite preview`; Lighthouse CI.
- Phase 7: signed release via `android-release.yml` gates; Play internal track install; restore drill on a branch.

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
- OpenCode parses only `name`, `description` and `slash` from skill frontmatter, so `disable-model-invocation` is ignored there. Harmless.
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
