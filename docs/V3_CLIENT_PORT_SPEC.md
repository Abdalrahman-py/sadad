# Spec: port the signed v3 ledger client into the product app

Target: move the signed v3 ledger client that lives only in the parked second Android
project into the product module `app/`.

Every claim below is cited `file:line`. Path shorthand used throughout:

- `CLIENT` = `Supabase-Setup/android-official/Sadad-AndroidStudio/app/src/main/java/com/sadad/app/`
  — **this tree is no longer in the working tree.** It was the reference for the port and was removed on
  2026-10-11 once every class below had landed in `APP`. Read any cited file with
  `git show pre-consolidation-2026-10-11:<path>` (also on `archive/pre-consolidation`). One piece is still
  unported and matters: the `view()` projection in `CLIENT/LedgerRepository.java`, which folds the signed
  snapshot into the tables the screens read.
- `APP`    = `app/src/main/java/com/sadad/app/`
- `EDGE`   = `Supabase-Setup/supabase/functions/`
- `MIG`    = `Supabase-Setup/supabase/migrations/`

Project ref for the deployment is `vhftjmiltqfwmrszdtgf` (hardcoded in
`CLIENT/LedgerTransport.java:10`). Repo state: `3852af6`, 2026-10-11.

---

## 1. Client inventory

All 13 Java files in `CLIENT/`. "Depends on" lists compile-time class dependencies inside
`com.sadad.app`. Citation `:N` = total lines from `wc -l`.

| Class | Lines | Purpose | Depends on | WebView/Activity-specific? |
|---|---|---|---|---|
| `InstallationKey.java` | 32 | Non-exportable ECDSA P-256 key in AndroidKeyStore; device UUID; the canonical signed-text builder; SHA-256 hex; base64url; DER↔raw ECDSA conversion | Android Keystore, `SharedPreferences` `sadid.installation` | No |
| `LedgerTransport.java` | 24 | HTTP layer: legacy (gateway) requests + signed (sadid-ledger) requests; builds `X-Sadad-*` headers; `refresh()` | `SessionStore`, `InstallationKey`, `BuildConfig` | No |
| `LedgerRepository.java` | 59 | Orchestrator: `enroll()`, `renewLease()`, offline `add()`, online `online()`, `sync()`, `view()` projection | `EncryptedJournal`, `SessionStore`, `InstallationKey`, `LedgerTransport`; uses `android.provider.Settings`, `android.os.SystemClock` | No |
| `EncryptedJournal.java` | 57 | AES-GCM encrypted command journal + snapshot cache on raw SQLite/WAL (not Room) | `InstallationKey.hash`, Android Keystore | No |
| `RecoveryCrypto.java` | 19 | PBKDF2-HMAC-SHA256 (600k) + AES-256-GCM sealed recovery file | `InstallationKey.b64/unb64` | No |
| `SessionStore.java` | 103 | Encrypted device token + Auth envelope + account in `sadad.session` | Android Keystore, `BuildConfig.API_BASE_URL` | No |
| `LocalSecurity.java` | 57 | Local app-lock PIN verifier (salt+PBKDF2) | `SharedPreferences` | No |
| `LedgerSyncWorker.java` | 10 | WorkManager one-shot worker calling `repo.sync()` | `SessionStore`, `LedgerRepository`, **`androidx.work`** | No (but needs WorkManager dep) |
| `LedgerActivity.java` | 49 | The v3 **WebView** UI + `@JavascriptInterface` bridge | `WebView`, `Activity` | **Yes — DROP** |
| `MainActivity.java` | 435 | The parked whole-app WebView shell | `WebView`, `Activity` | **Yes — DROP** |
| `SadadDatabase.java` | 399 | Legacy SQLite used only by the parked WebView app | — | Yes (parked-app only) — DROP from port |
| `SyncDatabase.java` | 59 | Room `@Database` (Cache/Command entities) | `androidx.room` | **No consumer — dead code** |
| `ApiClient.java` | 43 | Generic JSON HTTP helper | `BuildConfig` | **No consumer — dead code** |

**Dead code confirmed:** `SyncDatabase` is referenced only inside itself
(`CLIENT/SyncDatabase.java:9,13,14`) — never by `LedgerRepository` or `EncryptedJournal`.
`ApiClient.java` is referenced only by its own declaration (`CLIENT/ApiClient.java:8`).
`SadadDatabase`/`LocalSecurity` (parked copies) are referenced only by the parked
`MainActivity` (`CLIENT/MainActivity.java:48,50,59,61`). Only `LedgerActivity.java` and the
parked `MainActivity.java` import `android.webkit.*` (`CLIENT/LedgerActivity.java:7`,
`CLIENT/MainActivity.java:15-21`) — those two are the WebView-specific files to drop.

**Port set (no new Gradle dependencies needed):**
`InstallationKey`, `LedgerTransport`, `EncryptedJournal`, `RecoveryCrypto`, `LedgerRepository`,
plus the Auth-envelope additions to `SessionStore`. These use only JDK + `android.*` +
`org.json`. `LedgerSyncWorker` needs `androidx.work` and is optional (see §4).

---

## 2. Full wire protocol

### 2.1 Two base URLs

| Base | Value | Used by |
|---|---|---|
| Signed (hardcoded) | `https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadid-ledger/api` | `CLIENT/LedgerTransport.java:10` |
| Legacy / auth | `BuildConfig.API_BASE_URL` | `CLIENT/LedgerTransport.java:14`, `CLIENT/SessionStore.java:89-90` |

In the parked app `API_BASE_URL` is the **sadad-auth** gateway
`.../functions/v1/sadad-auth/api` (`CLIENT/../build.gradle.kts:8`). The v3 client's
`legacy()` calls (`mobile/login`, `mobile/auth/refresh`, `mobile/change-password`,
`mobile/logout`) go there, not to `sadad-api` directly.

### 2.2 Common headers on every signed request

Built in `CLIENT/LedgerTransport.java:18-21`:

- `apikey: <BuildConfig.SUPABASE_PUBLISHABLE_KEY>` (`:18`)
- `Content-Type: application/json; charset=utf-8` (`:18`)
- `Authorization: Bearer <auth.accessToken>` (`:19`)
- `X-Sadad-Session: <deviceToken>` (`:20`, also added for `mobile/auth/refresh`)
- `X-Sadad-Installation: installation.id()` (`:21`)
- `X-Sadad-Time: System.currentTimeMillis()` as a 13-digit string (`:21`)
- `X-Sadad-Nonce: UUID.randomUUID()` (`:21`)
- `X-Sadad-Signature: <sig>` (`:21`)
- `X-Sadad-Recovery: <permit>` — only when a recovery permit is present (`:21`)

`signed()` calls `refresh()` first (`CLIENT/LedgerTransport.java:16`).

### 2.3 Signature computation (must match byte-for-byte)

Client (`CLIENT/InstallationKey.java:28`):

```
text = "sadid-v3"        + "\n" +
       method            + "\n" +   // as passed, upper-case ("POST"/"GET")
       route             + "\n" +   // path after BASE, e.g. "/mobile/v3/operations"
       sha256hex(rawBody) + "\n" +   // raw = body.toString() or "" for GET
       time              + "\n" +
       nonce             + "\n" +
       installationId
signature = base64url_nopad( DER→raw( ECDSA-SHA256_sign(UTF-8(text)) ) )   // 64-byte raw r||s
```

- `route` = `address.substring(BASE.length())` → includes the leading `/` (`:21`).
- `sha256hex` = lower-case hex of the SHA-256 digest of the UTF-8 body (`:27`).
- base64url: `URL_SAFE | NO_WRAP | NO_PADDING` (`:25`).
- DER→raw for signing output (`:29`); raw→DER for verification (`:30`).

Server (`EDGE/sadid-ledger/proof.ts`):
`proofText` = `[ 'sadid-v3', method.toUpperCase(), route, digest, time, nonce, installation ].join('\n')` (`:4`);
`route` = pathname minus `/sadid-ledger` and `/api` (`EDGE/sadid-ledger/index.ts:22`);
validation: `id`/`nonce` are UUIDs, `time` is 13 digits, `|now-time| ≤ 300000`, signature
≤100 chars (`proof.ts:10`); signature must decode to exactly 64 bytes (`proof.ts:15`);
the JWK must be EC/P-256 without `d` (`proof.ts:11`). Client `route`/`method` match these.

Public JWK shape sent at enrol: `{kty:"EC", crv:"P-256", x, y}` with 32-byte fixed
big-endian coordinates, base64url (`CLIENT/InstallationKey.java:23-24`). Server requires
`x`/`y` length 43 (`MIG/20261010100856_…:5-7`).

### 2.4 Legacy endpoints (base = gateway)

| Method + path | Body | Headers | Response |
|---|---|---|---|
| `POST mobile/login` | `{username, password, deviceId, deviceName, staffName}` (`CLIENT/LedgerActivity.java:31`) | `apikey`; **no** `Authorization`, **no** `X-Sadad-Session` | `200 {…session(token,expiresAt), forcePasswordChange, snapshotDeferred, snapshot, account, device, deviceStaffName, auth:{accessToken,refreshToken,expiresAt}}` (`EDGE/sadad-auth/handler.ts:146`; snapshot/device from `EDGE/sadad-api/index.ts:657`) |
| `POST mobile/auth/refresh` | `{refreshToken}` (`CLIENT/LedgerTransport.java:15`) | `apikey`, `X-Sadad-Session` (added because path ends with `mobile/auth/refresh`, `:20`) | `200 {ok:true, auth:{accessToken,refreshToken,expiresAt}}` (`handler.ts:187`) |
| `POST mobile/change-password` | `{currentPassword, password}` (`CLIENT/LedgerActivity.java:32`) | Bearer + `X-Sadad-Session` | `200 {ok:true, forcePasswordChange:false}` (`EDGE/sadad-api/index.ts:916-925`) |
| `POST mobile/logout` | `{}` (`CLIENT/LedgerActivity.java:38`) | Bearer + `X-Sadad-Session` | `200`; gateway deletes the auth binding and revokes the Supabase session (`handler.ts:237-245`) |

The gateway enforces route shape `/mobile/[a-z0-9/-]+` (`handler.ts:91`) and, for every
private route, requires both a verified JWT **and** the `x-sadad-session` device token
(`handler.ts:192`), then re-checks store/suspension/permissions upstream (`handler.ts:213-235`).
`login`/`auth/refresh` are handled before that gate (`handler.ts:104`, `:149`).

### 2.5 Signed endpoints (base = sadid-ledger)

Route gate: only `GET`/`POST` (`EDGE/sadid-ledger/index.ts:24`); body cap 65536 bytes
(`:26`). All non-enrol routes require a valid Bearer JWT (`:38-43`), a resolved
installation key (`:51-52`, else `403 installation_revoked`), and a valid proof (`:53-54`).

**`POST /mobile/v3/enroll`** (`:45-50`)
- Body: `{publicKey:{kty,crv,x,y}}` (`CLIENT/LedgerRepository.java:17`).
- Proof is verified against the **submitted** `publicKey` (`index.ts:46`); `x-sadad-session`
  is also required (`:47-48`).
- Server: `rpc sadid_enroll(p_user, p_session, p_legacy_hash=sha256(legacy_token), p_installation, p_key)`.
- Response: `200 {ok:true, result:{installationId, generation, status}}`
  (`MIG/20261010100856_…:30`). `status` starts `"pending"` (`:24`).

**`POST /mobile/v3/lease`** (`:59,63-69`)
- Body: `{}`.
- Response: `200 {ok:true, lease:{payload, signature, publicKey:{kty,crv,x,y}}}`, where
  `payload = base64url(JSON{schemaVersion:1, projectRef:"vhftjmiltqfwmrszdtgf", storeId,
  userId, installationId, generation, issuedAt, expiresAt:issuedAt+7d,
  allowed:["contact.create","debt.create","payment.create"]})` (`index.ts:65`), signed ECDSA
  by the lease key derived from the service key (`index.ts:9-14,66-68`).
- Client verifies the envelope with `publicKey` and asserts `expiresAt-issuedAt == 7 days`
  (`CLIENT/LedgerRepository.java:18-19`).

**`GET /mobile/v3/snapshot`** (`:57`)
- No body. Response: `200 {ok:true, snapshot:{…}}`.
- Snapshot shape (latest definition, `MIG/20261010102007_…:156-166`):
  `{schemaVersion:1, storeId, epoch, cursor, currency,
    debtBalances:[{debtId,amountCents,paidCents,remainingCents,version}],
    contacts:[…sadid_contacts rows…], debts:[…], payments:[…],
    adjustments:[…], reversals:[…], outcomes:[…operation results…]}`
  (base shape without `debtBalances` in `MIG/20261010100605_…:310-316`).

**`POST /mobile/v3/operations`** (`:56`)
- Body: one command object (the immutable wire bytes the client stored at queue time).
- Command shape (`CLIENT/LedgerRepository.java:40`):
  `{schemaVersion:1, operationId:<uuid>, entityId:<uuid>, type,
    originInstallationId, originGeneration, createdAt:"…ISO8601 ±hh:mm",
    dependsOn:[...], expectedEpoch:<epoch>, payload:{...}}`
  and `expectedVersion` appended for corrections (`CLIENT/LedgerRepository.java:43`).
- Offline types: `contact.create`, `debt.create`, `payment.create` (`LedgerRepository.java:35`).
  Online types: `debt.correct`, `payment.reverse`, `contact.archive`, `contact.restore`
  (`MIG/20261010140258_…:39-93`; net list also `V3_CONTRACT_AR.md:25`).
- Server validation: `schemaVersion` must be `"1"` and ≤65536 bytes (`MIG/20261010140258_…:15`);
  `expectedEpoch` must equal the store epoch unless a recovery epoch is allowed (`:27`);
  `createdAt` must parse (`:34-35`); per-type rules (`:39-93`).
- Response: `200 {ok:true, result:{operationId, entityId, status:"accepted"|"rejected",
  code, sequence, epoch, serverTime}}` (`MIG/20261010140258_…:101`).
- Idempotency: re-sending the same `operationId` with identical JSON returns the stored
  result; different content → `operation_id_reused` (`MIG/20261010140258_…:22-25`).

**`POST /mobile/v3/outcomes`** (`:58`)
- Body: `{operationIds:[…]}`. Response: `200 {ok:true, result:[<result>,…]}`
  (`MIG/20261010100605_…:326-332`). **Note:** the parked client never calls this — it reads
  outcomes from the snapshot instead (`grep` over `CLIENT/` finds no `/mobile/v3/outcomes`).

Error envelope on any failure: `{ok:false, code, message}` with status chosen in
`EDGE/sadid-ledger/index.ts:75-77` (409 for `dependency_pending`, `epoch_changed`,
`operation_id_reused`, `replayed_request`, `installation_key_changed`,
`session_installation_conflict`; 400 for `invalid_*`; 403 otherwise).

The client maps a failure to `LedgerTransport.Failure(status,code,message)`
(`CLIENT/LedgerTransport.java:12,22`) and to journal states `blocked`/`uncertain`
(`CLIENT/LedgerRepository.java:53-54`).

### 2.6 Enrolment / authorization sequence end to end

1. **Login (legacy).** `POST mobile/login` to the gateway → the gateway verifies the store
   credentials against `sadad-api`, mints an opaque Supabase Auth identity via
   `generateLink`+`verifyOtp`, inserts a row in `sadad_auth_bindings`, and returns the Auth
   envelope (`EDGE/sadad-auth/handler.ts:104-146`). Client stores
   `{deviceToken:token, auth:{accessToken,refreshToken,expiresAt}}` plus `account`
   (`CLIENT/SessionStore.java:28-47`).
2. **First signed request = enrol.** `POST /mobile/v3/enroll` with `X-Sadad-Session` = device
   token and the JWK in the body (`CLIENT/LedgerRepository.java:17`). Proof is checked
   against the submitted JWK (`EDGE/sadid-ledger/index.ts:46`). Result
   `{installationId, generation, status}` is written to journal metadata key `installation`
   (`CLIENT/LedgerRepository.java:17`).
3. **Admin approval.** Until approved the installation is `pending` and `sync()` aborts
   (`CLIENT/LedgerRepository.java:51`). Approval is an admin action
   (`EDGE/sadid-ledger/index.ts:32` → `sadid_approve_installation`,
   `MIG/20261010100856_…:33-52`), which activates the new installation and revokes the prior
   one, and initialises `sadid_ledger_state` (epoch + cursor). For every subsequent signed
   request the server resolves the key through `sadid_installation_key` (`:51`,
   `MIG/20261010100856_…:54-60`) and verifies the proof against the **stored** key.
4. **Lease.** `POST /mobile/v3/lease` → 7-day offline authorisation; client stores it under
   metadata key `lease` with `payload, envelope, elapsed, wall, boot`
   (`CLIENT/LedgerRepository.java:19`). `canAddOffline()` gates all offline writes on it
   (`:20`).
5. **Snapshot.** `GET /mobile/v3/snapshot` → `journal.merge(snapshot)`
   (`CLIENT/LedgerRepository.java:51,56`; merge logic `CLIENT/EncryptedJournal.java:44-50`).
6. **Operations.** Drain the journal: for each non-terminal entry, `startSend` then
   `POST /mobile/v3/operations` with the stored `wire` bytes (`CLIENT/LedgerRepository.java:52-54`).
7. **Re-snapshot.** `GET /mobile/v3/snapshot` again to fold receipts into the cache
   (`CLIENT/LedgerRepository.java:56`).

What must be stored locally for the *next* request: `deviceToken`, `auth.{accessToken,
refreshToken,expiresAt}` (all encrypted), `account`/`ledger_owner`, the Keystore EC private
key + installation UUID, and journal metadata `installation` (generation/status), `lease`,
`cache` (epoch/cursor), `sync`. Derived scope =
`vhftjmiltqfwmrszdtgf|<account.id>|<jwt.sub>` (`CLIENT/LedgerRepository.java:15`).

---

## 3. Local storage

### 3.1 `SessionStore` — `SharedPreferences("sadad.session")` (`CLIENT/SessionStore.java:22`)

| Key | Format | Where |
|---|---|---|
| `token` | base64(`iv[12] ‖ AES-GCM(ct)`) of JSON `{deviceToken, auth:{accessToken,refreshToken,expiresAt}}` | written `:31-46`, read `:49-59` |
| `expires_at` | long (epoch ms) | `:41,78` |
| `account` | JSON string | `:42,83-87` |
| `ledger_owner` | string = `account.id` | `:43,88` |
| `revision` | long | `:44,79-80` |
| `force_password_change` | boolean | `:45,81-82` |

Encryption key: AndroidKeyStore AES alias `sadad.session.aes`, GCM/NoPadding, randomised IV,
128-bit tag (`:23,93-102`). IV is prepended to the ciphertext (`:34-38`). This file is the
**only** place the Auth JWT lives; the repo reads `auth().accessToken` for the Bearer header
and `account().id` for the scope.

### 3.2 `InstallationKey`

- `SharedPreferences("sadid.installation")` key `id` = random UUID, committed (`CLIENT/InstallationKey.java:21`).
- AndroidKeyStore EC P-256 private key, alias `sadid.installation.ec.v1`, `PURPOSE_SIGN|VERIFY`,
  digest SHA-256, `secp256r1` (`:18,22`). Private key is never exported.

### 3.3 `EncryptedJournal` — SQLite file `sadid-v3-<sha256hex(scope)>.db`, WAL (`CLIENT/EncryptedJournal.java:24-25`)

Tables (`:26-28`):
- `entries(order_id INTEGER PK AUTOINCREMENT, identity_hash TEXT UNIQUE, data BLOB)`
- `metadata(id TEXT PK, data BLOB)`
- `worker_lock(id INTEGER PK, owner TEXT, until_ms INTEGER)`

Every BLOB is AES-GCM encrypted with AndroidKeyStore AES alias
`sadid.journal.<sha256hex(scope)>` (`:24,30`); stored layout = `iv[12] ‖ ct` (`:31`); AAD =
`scope + "|" + purpose` where purpose is the metadata key or `"entry:"+identity_hash`
(`:31-32`). Cleartext columns are only `order_id` and `identity_hash` (= `sha256hex(operationId)`,
`:36`). Decrypt rejects blobs `< 29` bytes (`:32`).

Metadata keys used: `installation`, `lease`, `cache`, `sync`, `recovery`, `export`,
`priorEpochCache` (`:33-34,43-48,54`).

Entry JSON (per command): `{command, wire, state, sentEver, attempts, lastAttemptAt, receipt,
history[], replacementOperationId, cancelledAt, projectionExcluded, error}` (`:38-43`).
States: `queued, sending, uncertain, acknowledged, confirmed, rejected, blocked, cancelled, reviewed`.

Concurrency: single-worker advisory lock in `worker_lock`, 180 s TTL
(`acquire` `:51`, `renew` `:52`, `release` `:53`).

### 3.4 `RecoveryCrypto` (sealed export file)

Envelope JSON (`CLIENT/RecoveryCrypto.java:17`):
`{format:"sadid-recovery-v1", kdf:"PBKDF2-HMAC-SHA256", iterations:600000,
  salt:b64url(16), iv:b64url(12), data:b64url(ct)}`;
AES-256-GCM, AAD `"sadid-recovery-v1"` (`:17-18`); password ≥12 chars (`:17`); `open()`
rejects a mismatched format/kdf/iterations/salt/iv (`:18`).

---

## 4. Recommended integration design for the root app

Constraints: the app already has **one** sync entry point —
`APP/LedgerSyncService.java` (a `JobService`) driven by `APP/SyncScheduler.java` and backed by
`APP/SadadDatabase.java` (schema v7, `sync_outbox` + `sync_control`, `APP/SadadDatabase.java:40-55`).
Keep that shape.

### 4.1 Runtime v3-vs-legacy decision (probe, not mid-sync 409)

For a v3 store the backend answers `409 legacy_ledger_read_only` from the gate at
`EDGE/sadad-api/index.ts:927`, but that gate sits **after** the `/mobile/session` and
`/mobile/change-password` handlers (`:915-916`) and **before** `/mobile/snapshot-page`,
`/mobile/sync-delta`, `/mobile/snapshot`, `/mobile/sync` (`:932-968`). So:

- `GET /mobile/session` → `200` for both store kinds.
- `GET /mobile/snapshot` → `200` for a legacy store, `409 {code:"legacy_ledger_read_only"}`
  for a v3 store.

**Recommendation:** after a successful login, call `GET /mobile/snapshot` once and persist
the result as a `ledger_mode` pref (`v3` on 409, `legacy` on 200) in `SessionStore`. Do **not**
infer the mode from a mid-sync 409 — the durable v3 queue lives in a different store
(§4.3) and cannot be reconstructed from the legacy `sync_outbox`.

### 4.2 Files that must change

| File | Change |
|---|---|
| `app/build.gradle.kts` | Point the **server** flavor `SADAD_API_BASE_URL` default at `.../functions/v1/sadad-auth` (so login returns the Auth envelope; the gateway proxies every `/mobile/*` route, `EDGE/sadad-auth/handler.ts:64,248`), or add a separate `AUTH_BASE_URL` buildConfigField used only for login/refresh. Add `androidx.work:work-runtime` **only** if you port `LedgerSyncWorker`; the core port needs no new deps. Do **not** add Room — `SyncDatabase` is dead code. |
| `APP/SessionStore.java` | Persist the Auth envelope: read `response.getJSONObject("auth")` in `save()` (currently absent — root `save` reads only `token`, `:31-58`), add `auth()`/`updateAuth()` mirroring `CLIENT/SessionStore.java:65-75`, add `ledgerMode()`/`setLedgerMode()`. |
| `APP/LedgerSyncService.java` | Branch on `session.ledgerMode()`. v3 → construct `LedgerRepository` and call `repo.sync()` on the worker thread (or `repo.online()` for online-only edits); legacy → existing `/mobile/sync`+`sync-delta` code path unchanged. Keep `SyncScheduler.LOCK` and the two job IDs. |
| `APP/MainActivity.java` | `loginLocal()` (`:401-479`) must run the snapshot probe and switch databases; `markLedgerChanged()` (`:586-592`) must enqueue a v3 command; `syncLocalChanges()` (`:611-670`), `checkServerUpdates()` (`:723-746`), `requestServerSnapshot()` (`:712-721`), `applyServerSnapshot()` (`:683-693`) each need a v3 branch. |
| `APP/NativeScreens.java` | The edit call sites that call `host.markLedgerChanged()` / `host.database.save*` (`:613,660,851,908,990,1405,1410`) are where user edits originate; for v3 they must route to `LedgerRepository.add(type,payload)` instead of the local `SadadDatabase.save*`. |
| New files under `APP/` | Port `InstallationKey`, `LedgerTransport`, `EncryptedJournal`, `RecoveryCrypto`, `LedgerRepository` (package-private `final class` as-is). Adapt `SessionStore` (above) rather than importing the parked one. |

`APP/PagedSnapshotTransfer.java` is not used by the v3 path (a v3 snapshot is one consistent
read); leave it for the legacy branch.

### 4.3 The id model — decided on 12 October 2026

Chosen: **a stable UUID column on the existing tables** (option 1 of the three below, without a
separate mapping table). Each of `contacts`, `debts` and `payments` carries `remote_uuid` +
`remote_version`, with a unique index on the UUID; `SadadDatabase.importLedgerView` matches rows on
the UUID and inserts what it does not have, so every existing integer id — and everything that
points at it: the screens, the exports, the reminders — keeps working unchanged.

Why this one: the signed ledger is the store's only writer, so a row that the ledger does not have
must disappear, and a row that it does must keep its local identity across snapshots. Option 2
(rendering the screens from `view()`) would have meant touching every read path in `NativeScreens`
for no behavioural gain; option 3 (a separate ledger database plus an adapter) would have left two
places holding the same money. A local row with **no** UUID is left alone rather than deleted: those
are entries typed before the device joined the signed ledger, and a silent delete is a lost record.
`SadadDatabase.unmappedRowCount()` counts them so the cutover can be reported instead of guessed.

`LedgerRepository.view()` — the projection the parked client kept in `LedgerRepository.java` — was
ported as `LedgerProjection` and is pure JSON, tested in the JVM (13 tests). The Android half is
`LedgerQueue`.

The three options as originally posed, kept for the record:

`APP/SadadDatabase` keys everything on `INTEGER PRIMARY KEY AUTOINCREMENT` ids
(`APP/SadadDatabase.java:154-180`), and the UI reads `getSnapshot()/getOverview()/getPersonSnapshot()`
from it (`:622-628`). The v3 model uses **UUID** ids (`MIG/20261010100605_…:54-93`) and
`LedgerRepository.view()` returns its own shape with string ids
(`CLIENT/LedgerRepository.java:22-32`). Bridging v3 into the existing integer-keyed schema is
the central design decision and is **not** solved by copying code. Options:

1. Add a `uuid TEXT` surrogate column to `contacts/debts/payments` and a mapping table, keeping
   integer PKs for the UI and FK graph. Most invasive; preserves all existing screens.
2. Render the ledger UI directly from `LedgerRepository.view()` and keep `SadadDatabase` only
   for reminder/profile data. Least code in the sync layer; means touching every read path in
   `NativeScreens`.
3. A dedicated v3 SQLite DB (the `EncryptedJournal` cache) plus a thin adapter that materialises
   `view()` into `SadadDatabase` under stable integer ids assigned per UUID. Middle ground.

The decision was taken on 12 October 2026 and is implemented: option 1. The options below are kept
as the record of what was weighed.

### 4.4 Keep both paths, or replace legacy?

- **Keep both** (recommended, gated by `ledger_mode`): safety net for any non-v3 store; the
  gateway still serves legacy routes. Cost: two sync stacks, two storage shapes, a larger test
  matrix, and the UI adapter must serve both shapes. Because the only live stores are v3
  (`sadid_is_v3_store(2)`/`(3)`/`(7)` true; store 6 is the one store still on the legacy protocol,
  and only it keeps the legacy path alive) the legacy path is
  already dead in production — you are paying maintenance for no live traffic.
- **Replace legacy:** one code path, matches production reality, smallest surface. Risk: if any
  legacy store still exists, it stops syncing; and the v3 path's first-run needs admin approval
  (§2.6 step 3), so a botched cutover leaves a store unable to write.

Recommendation: keep the legacy code **byte-for-byte** behind the persisted `ledger_mode`
flag, defaulting to v3. That preserves the fallback without a second live code path.

---

## 5. Verification plan

### 5.1 Without a device (run now)

- `deno test` in `EDGE/sadid-ledger/` — `proof_test.ts` proves the signature text, replay
  window, content-tamper and wrong-key rejection (`EDGE/sadid-ledger/proof_test.ts:15-17`).
- `deno test` in `EDGE/sadad-api/` — `ledger-sync_test.ts` covers the legacy delta/merge
  helpers (`EDGE/sadad-api/ledger-sync_test.ts`).
- `deno test` in `EDGE/sadad-auth/` — gateway tests (`EDGE/sadad-auth/handler_test.ts`).
- `Supabase-Setup/scripts/test-v3-ledger.mjs` exercises the v3 RPCs through pglite
  (referenced for `sadid_snapshot`/`sadid_apply` in that script).
- **Signature parity check:** assert the client's `proofText` equals the server's
  `proofText(method,route,digest,time,nonce,id)` (`EDGE/sadid-ledger/proof.ts:4`) and that
  `rawToDer(sign(...))` verifies under the paired JWK. `InstallationKey.rawToDer` (`:30`) makes
  the reverse conversion available for this test.

### 5.2 Instrumentation (device)

- **Offline projection + recovery suite:** model on `CLIENT/../androidTest/…/LedgerInstrumentation.java:17-52`
  — it drives `EncryptedJournal`/`LedgerRepository` against a synthetic snapshot with a local
  device token (no server): pending-payment projection, restart persistence, cancel-after-send
  refusal, receipt-not-removing-projection, recovery round-trip, tamper rejection, worker-lock
  exclusivity, SQLite write-failure handling.
- **Live mode:** the same file's `liveMode()` (`:53-64`) runs `repo.sync()` and asserts real
  counts (`:59`), and `mode=login` reads `test-login.json` and enrols (`:57`). It needs real
  store credentials and an **approved** installation.
- **End-to-end acceptance (login → edit → sync → row present):** in `app/src/androidTest`
  (QA id `com.sadad.qa`), add a test that: (1) logs in with a real store number/password;
  (2) drives `LedgerRepository.add("contact.create",…)` (+ one debt and one payment);
  (3) calls `repo.sync()`; (4) asserts the operation reaches `confirmed` state and
  `GET /mobile/v3/snapshot` (or `repo.view()`) shows the rows with server-assigned
  `sequence`/`epoch`. Because the first installation is `pending`, the test harness must first
  approve via `POST /admin/installations/approve` (`EDGE/sadid-ledger/index.ts:32`).

### 5.3 Server-side confirmation (independent of the app)

- Query `public.sadid_contacts`/`sadid_debts`/`sadid_payments` filtered by `store_id` and match
  the UUID `entityId` the client generated, or read the admin route
  `POST /admin/ledger/read` → `sadid_support_snapshot` (`EDGE/sadid-ledger/index.ts:35`).
- Cross-check `public.sadid_ledger_state.cursor`/`epoch` and the receipt `sequence` in
  `public.sadid_operations.result` (`MIG/20261010140258_…:101-105`).

---

## 6. Blockers and unknowns

1. **UUID-vs-INTEGER id model** (§4.3) — **settled on 12 October 2026**, no longer a blocker:
   option 1, the `remote_uuid`/`remote_version` columns, implemented in
   `SadadDatabase.importLedgerView`. The original note, kept for the record: it was a
   repository-wide decision, not a porting detail.
2. **Login must go through the sadad-auth gateway.** The root build targets `sadad-api`
   (`app/build.gradle.kts:17`), whose `/mobile/login` returns **no** `auth` envelope
   (`EDGE/sadad-api/index.ts:657`). Without the Auth JWT the signed endpoints all fail `401`
   (`EDGE/sadid-ledger/index.ts:38-43`). The gateway (`EDGE/sadad-auth/handler.ts:146`) is the
   only issuer. The parked app already uses the gateway (`CLIENT/../build.gradle.kts:8`).
3. **Admin approval has no in-app flow.** `sync()` throws until the installation is `active`
   (`CLIENT/LedgerRepository.java:51`); approval is an admin-only RPC. `ANDROID_AUTH.md:28`
   records that the project has no real admin or store accounts, so a live e2e run needs one
   provisioned first. Whether a *first* install on a fresh store also needs approval was not
   verified end to end — the code implies yes (`MIG/20261010100856_…:24` inserts `pending`).
4. **New Gradle deps.** The root app has an empty `dependencies { }` (`app/build.gradle.kts:75`).
   Porting `LedgerSyncWorker` needs `androidx.work`; porting `SyncDatabase` would need
   `androidx.room` — but `SyncDatabase` is dead code and should be dropped.
5. **Hardcoded project ref / lease key.** The signed base is fixed to `vhftjmiltqfwmrszdtgf`
   (`CLIENT/LedgerTransport.java:10`) and the lease key is derived from the service key
   (`EDGE/sadid-ledger/index.ts:10`). The release build must reject any other project ref
   (consistent with `AGENTS.md` server-URL pinning), and the lease private key must never reach
   the app — it does not; only the public JWK is sent.
6. **Lease clock/boot heuristic is unverified on-device.** `canAddOffline()` reads
   `Settings.Global.getInt(resolver,"boot_count",-1)` (`CLIENT/LedgerRepository.java:16,20`).
   `boot_count` is not a documented public setting; behaviour across OEMs/reboots is untested
   here.
7. **Recovery / epoch-reconciliation paths not exercised end to end.** `acceptEpoch`
   (`CLIENT/EncryptedJournal.java:43`), the `X-Sadad-Recovery` permit flow, and
   resubmit-of-rejected (`CLIENT/LedgerActivity.java:34`) depend on admin RPCs
   (`sadid_issue_recovery_capability`, `sadid_approve_recovery`) that were read but not run.
8. **`/mobile/v3/outcomes` is server-ready but unused by the client** — the parked client reads
   `snapshot.outcomes` instead. Confirm no other consumer expects the client to call it.
9. **`X-Sadad-Recovery` permit validation regex is client-side only** — the WebView bridge
   checks `[0-9a-fA-F-]{36}` (`CLIENT/LedgerActivity.java:45`); the native port must re-validate.
10. **Current root app cannot meaningfully log into a v3 store.** `loginLocal()` reaches
    `PagedSnapshotTransfer.download()` → `GET /mobile/snapshot-page` → `409` for v3 stores
    (`EDGE/sadad-api/index.ts:927,932`), which the existing `catch(Exception)` swallows
    (`APP/MainActivity.java:451,469-476`). This confirms the port is required, not optional.
