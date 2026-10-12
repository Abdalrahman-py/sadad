# Independent audit and action plan — سدد

Date: 12 October 2026. This is a point-in-time technical audit and proposed work list, not a second status page. `docs/CONTINUE_HERE.md` remains the status authority. No implementation, migration, deployment, account change, or data deletion was performed by this audit.

What this repo does: Sadad is an Arabic Android debt and payment ledger, with connected and standalone editions. The connected edition uses Supabase Auth, signed ledger operations, an encrypted local journal, and a static administration dashboard. I assumed the documented use: small store ledgers, intermittent connections, background sync, and up to three saved stores on one phone; correctness of money and account isolation matter more than line count.

**Decision:** fix payment safety, the backend origin, enrollment, and account/session state before deleting code. Build the activity replacement with the latest authorization rules. Keep the legacy server routes until every unsigned store and remaining caller is accounted for. The supplied plan has useful invariants, but is not safe to execute in its current order.

## Evidence and measured baseline

| Check | Result in this audit |
| --- | --- |
| Current checkout | `/home/jade/.codex/worktrees/7cb0/sadad`, HEAD `fe79c22fc752b3b24ecc000d66a1f29b85b4a605`, initially clean, detached HEAD |
| Other checkout | `/home/jade/sadad`, same HEAD, six modified files; read only, not imported or stashed |
| Original input | `/home/jade/sadad/.hermes/plans/2026-10-12_172453-sadad-supabase-only.md` and attached audit |
| Tracked files | 338 before adding these audit documents |
| Current Java census | Main: 41 files / 7,067 lines; JVM tests: 7 / 1,721; instrumentation: 8 / 460; total 9,248 lines |
| Other checkout Java census | Main: 41 / 7,026; JVM tests: 7 / 1,721; instrumentation: 8 / 480; total 9,227 lines |
| JVM tests | 67 tests, 1 skipped, 0 failures, 0 errors, summed from newly generated XML |
| Android verification | `testServerDebugUnitTest assembleServerDebug assembleStandaloneDebug assembleServerDebugAndroidTest --offline`: BUILD SUCCESSFUL; JDK 21, SDK from `ANDROID_HOME=/home/jade/Android/Sdk` |
| Edge verification | `deno check index.ts` and `deno test -A` pass in all three directories: API 22, Auth 11, ledger 3; 36 tests total |
| Dashboard | `node tools/build-admin-pages.cjs` succeeds; six source assets; output script `admin.e8ff598efe.js` |
| Production functions, read only | API v6, Auth v4, ledger v7; all three report `verify_jwt=false` |
| Deployed ledger contents | `index.ts`, `proof.ts`, `deno.json`; no `admin.ts`, no activity route. Publishing the current tree also publishes the newer admin surface |
| Remote migration history | 22 entries; October 12 payment-method and contact-update migrations present |
| Production authorization | Read-only function-definition probes confirm current snapshot/apply use `lock_authorization`; enroll rejects installation reuse across stores |
| Production store inventory | Five stores. IDs 2, 3, 7 have initialized signed ledgers. ID 6 (`900001`) has no initialized signed ledger and has 4 legacy contacts, 1 debt, **1 payment**. ID 8 has no initialized signed ledger and empty legacy contact/debt/payment tables |

The old plan's total Java line count describes the other checkout, but its file counts do not: neither checkout has 24 main / 5 JVM / 5 instrumentation Java files. Its four-store inventory and five-row legacy-data claim have drifted. Ledger initialization is the criterion used by `isV3Store`; it is not proof that a particular phone is currently using a route.

Two disposable Java probes are in `/tmp/sadad-audit-repro/`. One invokes the real compiled signed-sync implementation using the existing test fixtures and shows that SYNCED leaves installation metadata empty. The other executes the verbatim `MainActivity.addPayment` method with fake cache/queue collaborators; it demonstrates the overpayment side effect below. These are audit reproductions, not permanent regression tests or device tests.

## Must fix

1. **A rejected payment can still change the ledger.**
   - What this is: `MainActivity.addPayment` allocates a payment across the oldest open debts (`app/src/main/java/com/sadad/app/MainActivity.java:673-703`).
   - Problem: it queues each allocation at line 698, then checks for an excess at line 701. A payment of 200 against a balance of 100 queues 100 and reports an error. The UI does not check the upper bound before calling it (`NativeScreens.java:887-915`). A later sync can send that queued payment although the user was told the save failed. The extracted-method probe reproduced this exact case.
   - Fix: validate the entire allocation before writing anything, then enqueue all slices in one journal transaction. Validate the first contact/debt compound action the same way: `addContact` currently queues a contact before parsing its initial debt at lines 637-641.
   - If we skip it: rejected or interrupted saves can leave financial operations the user did not knowingly accept.

2. **Old preferences can redirect store credentials.**
   - What this is: `SessionStore.apiBaseUrl()` reads an editable persisted address (`SessionStore.java:215`), restored from saved accounts at line 112; `MainActivity.apiRequest` sends its bearer token to that address at lines 524 and 546.
   - Problem: removing the setter in the pending changes does not remove old preference values. An upgraded device can send its token, and password-change request body, to an earlier configured host.
   - Fix: return the build-time origin and remove origin persistence/restoration. In this checkout, also remove the remaining configuration setter and its UI callers when transferring the reviewed pending changes. Explicitly reject redirects in credential-bearing requests, matching the signed request path.
   - If we skip it: the claimed fixed-origin release behavior remains false. Test an upgrade with a foreign saved address; the destination must still be the build-time backend.

3. **Successful enrollment is never saved where writes look for it.**
   - What this is: signed sync accepts enrollment at `SignedLedgerSync.java:73-78`, while `LedgerQueue.add` and `online` require local installation metadata at `LedgerQueue.java:84-90` and `:129-139`.
   - Problem: no production code writes that metadata. Even after an active enrollment, the status remains empty and writes fail with the approval message. The probe reproduced SYNCED with `metadata("installation") == {}`.
   - Fix: validate and persist the server's installation identity/status/generation as part of enrollment; update it on pending/revoked transitions, and prevent a stale active state from authorizing writes.
   - If we skip it: the app can appear synchronized yet refuse every new signed-ledger entry.

4. **One phone cannot enroll into its second store.**
   - What this is: `InstallationKey.java:18-22` uses one app-wide UUID/key alias. Every account opens it identically (`LedgerQueue.java:37`).
   - Problem: enrollment binds the globally unique installation ID to one store and rejects another store with `installation_key_changed` (`20261010100856_sadid_v3_installation_control.sql:18-30`). The current production enroll definition retains that rejection.
   - Fix: provide a stable installation identity for each store scope. Preserve the already-bound first-store identity and its existing journal ownership; do not silently regenerate identifiers for commands already queued. Read actual binding before assigning the old identity to a scope.
   - If we skip it: changing the account-switch route to signed sync still does not make multi-store use work. Test two stores, returning to the first, restart, approval, and previously queued operations.

5. **Signed synchronization leaves account switching blocked.**
   - What this is: signed login and edits set `sync_dirty=true` (`MainActivity.java:418`, `:575`), while add/switch-account guards reject a dirty or blocked session (`:1202`, `:1208`).
   - Problem: signed success does not clear/recompute that flag (`applySignedResult`, lines 612-624). Only legacy paths clear it. Simply replacing `/mobile/snapshot` in B.1 leaves the earlier guard blocking the operation. Old `sync_blocked` values can also prevent background work (`LedgerSyncService.java:17`).
   - Fix: base the signed account guard on actual journal work and recovery state, not the legacy flag. Keep the SQLite outbox/triggers and `sync_control` until a separately tested schema change; this does not require dropping installed tables.
   - If we skip it: even a fully synchronized store can be stuck on its current account. Do not clear the flag unconditionally when sync reports failure, approval wait, or unsettled operations.

6. **Saved accounts restore stale Auth envelopes.**
   - What this is: the client refreshes Auth and calls `SessionStore.updateAuth` (`LedgerClient.java:116-135`). Saved-account activation restores `encryptedAuth` (`SessionStore.java:115`).
   - Problem: `updateAuth` updates only the current envelope (`SessionStore.java:229-232`), not the saved bundle. After multiple refreshes, switching away and back can restore an older refresh token outside the documented reuse exceptions. Separately, `save` writes Auth before validating account capacity at lines 40-44 and leaves old Auth untouched when a response lacks it.
   - Fix: validate the complete login response before any mutation; save the matching device token/account/Auth together; update the matching saved bundle on refresh, and clear incompatible old Auth. Require a fresh gateway login for an older session without an envelope; retain its logout fallback.
   - If we skip it: saved stores may require unexpected re-login or combine an account's device session with another account's Auth envelope. Refresh rotation is documented in [Supabase Auth sessions](https://supabase.com/docs/guides/auth/sessions).

7. **Account changes can race an in-flight sync.**
   - What this is: sync owns the worker-side `SyncScheduler.LOCK` (`MainActivity.java:829-838`), but `switchStoreAccount` changes the shared session and closes the database/queue on the UI thread before submitting its network task (`:1209-1213`, `:1235-1240`). The queue captures store/user, while its client reads the mutable session for each request (`LedgerQueue.java:29-39`, `LedgerClient.java:90-92`).
   - Problem: a clean account can be switched while its hourly sync is awaiting a response. That sync can then operate on closed collaborators or credentials from the next account, and its UI callbacks refer to current screens rather than the captured account. Repairing the dirty guard makes this race easier to reach.
   - Fix: serialize identity changes with sync; capture the account and collaborators for each operation; discard results that no longer belong to the active scope. Keep Android UI operations on the UI thread rather than blocking it on a network lock.
   - If we skip it: account switching remains unreliable and account isolation is not proven. This is a source-traced race, not a device reproduction; test it with a deliberately delayed server response.

8. **Two screens read a protocol that signed stores reject.**
   - What this is: activity calls `/mobile/activity` (`MainActivity.java:1178`); account switching calls `/mobile/snapshot` and imports the old shape (`:1215-1219`).
   - Problem: the API explicitly refuses these routes for signed stores (`sadad-api/handler.ts:935-938`). The proposed activity renderer also drops actor attribution and shows raw command kinds. Ledger events do not include all login/admin audit events.
   - Fix: after findings 3-7, switch accounts through signed sync with explicit offline/error handling. Add the bounded signed activity RPC/route with Arabic labels, meaningful actor/device information, and a clearly defined ledger-operation history contract. Keep session/account status refresh through its retained identity route if required; the signed snapshot does not supply the old account object.
   - If we skip it: the screens remain broken, or the new activity screen promises a user-audit history it cannot deliver.

## Should fix before the proposed rollout

9. **Phase C deletes shared behavior and removes a data-source guard.**
   - What this is: the plan removes `makeSnapshot` and the blanket v3 guard while retaining login/admin/WhatsApp.
   - Problem: `makeSnapshot` has live login/admin/backup/restore/WhatsApp callers (`handler.ts:689`, `:781`, `:792`, `:798`, `:817`, `:824`, `:1023`). Removing the guard also opens WhatsApp routes for signed stores although their report reads the old tables. An empty or stale legacy balance is not the signed store's balance.
   - Fix: keep these helpers and the effective guard until their callers are converted or explicitly restricted. Include `/mobile/sync-delta` in any retirement list. Inventory both unsigned stores and installed clients; recommend preserving legacy routes for now rather than accepting breakage.
   - If we skip it: cleanup breaks login/admin or makes signed-store reports use the wrong data.

10. **The proposed activity SQL copies an outdated authorization implementation.**
    - What this is: B.2 follows the first snapshot migration, not its later hardening.
    - Problem: current snapshot locks authorization and checks the capability again (`20261010102007_sadid_v3_authorization_hardening.sql:146-155`); the proposal omits this. A SQL function with elevated privileges needs both correct role grants and current per-store authorization.
    - Fix: copy the hardened authorization sequence, retain empty `search_path` and the executor/invoker pattern, cap the result and order deterministically by sequence. Test store separation, expired/forged/wrong-scope capability, revoked session/installation, and suspended store.
    - If we skip it: the new read path has weaker revocation guarantees than the ledger snapshot. See [Supabase database function security](https://supabase.com/docs/guides/database/functions).

11. **Existing green tests do not verify the Android bridge or current SQL contract.**
    - What this is: JVM tests exercise protocol fakes; the real database harness explicitly loads migrations only through `20261010140258` (`Supabase-Setup/scripts/test-v3-ledger.mjs:26-37`). The authorization harness uses a simplified model. CI path filters and jobs omit migrations/scripts/database tests (`.github/workflows/android-ci.yml:9-28`).
    - Problem: the two applied October 12 changes and a proposed activity RPC can bypass meaningful database verification; fake journals do not detect the missing installation persistence. The old plan's client-only test cannot establish server authorization.
    - Fix: add focused regressions for findings 1-8, test the actual supported migration chain including its latest changes, and run the database harness in CI for SQL/script changes. Update FakeLedger when adding `LedgerApi.activity()`.
    - If we skip it: the same defects can survive a green build. A built instrumentation APK is not a completed device test.

## Ordered tasks and acceptance conditions

Each task must leave a compilable, testable checkpoint. Commit implementation units after their relevant checks pass; do not copy the original per-task boundaries when they sever dependencies. No implementation is authorized by this audit report alone.

| Order / task | Scope and smallest complete change | Acceptance / dependencies |
| --- | --- | --- |
| T0 — preserve and reconcile inputs | Review the six pending diffs, transfer the required changes intentionally into a named branch from the current checkout, preserving the source checkout. Snapshot the complete intended starting tree, including those diffs, before deletion. Do not force-reset archive refs or stash prerequisites away. | Record commit/patch identity and tracked-file count; confirm the snapshot contains pending work. The audit baseline above applies only to current HEAD, not the pending checkout. |
| T1 — protect credentials | Pin `SessionStore.apiBaseUrl`; remove persisted account origin/configuration callers; make credential-bearing HTTP redirect handling explicit. | Foreign legacy preference and saved-account address cannot change the destination; both Android editions build. No cloud change. |
| T2 — make compound writes atomic | Fix payment allocation validation and transactionally enqueue all slices in existing `EncryptedJournal`; apply the same all-or-nothing behavior to contact plus first debt. | Overpayment/invalid amount/invalid first debt add zero operations; a valid split enqueues the exact total; inject a failure on the second insertion and prove rollback. Preserve operation IDs across retries. No cloud change. |
| T3 — repair installation lifecycle | Persist validated enrollment status/generation; give stores stable installation scopes; adopt existing global identity only for its verified original binding. | Fresh approved install can add offline within a valid lease; pending/revoked cannot. Two stores work after restart; old queued commands retain origin identity and scope. Needs QA approval/device tests to validate real enrollment; do not change the database to weaken its binding. |
| T4 — repair session/account lifecycle | Update saved Auth on refresh; validate before saving; derive switch guards from signed journal state; serialize switch/login/logout with sync and close/reopen the queue on identity changes, including same-store user changes. Replace the snapshot route with signed sync and retain necessary identity refresh. | Successful settled sync permits switching; unresolved work is preserved; A→B→A after refresh restores current Auth. Delayed A sync cannot write into B's cache or show its results. Older sessions get a clear re-login path and can still revoke via logout fallback. Depends on T3. |
| T5 — database verification | Extend the existing real PGlite harness to the supported current migration chain, cover payment methods/contact updates, and add CI triggers/job for SQL/scripts. | Current applied contracts run in a clean local test DB; synthetic two-store authorization tests pass; no remote DDL. Do this before T6 migration rollout. |
| T6 — signed activity end to end | New migration, hardened private activity + public invoker wrapper, signed Edge route, LedgerApi/Client/Queue method, Arabic screen rendering, actor/device semantics and latest-200 limit. Update existing fakes. | SQL/Edge/client tests prove signatures, authorization, schema, store boundary and deterministic ordering. Show exact migration and full ledger bundle diff for review. Deployment gate: SQL first, ledger second, authenticated QA read third, then ship the client. A bare 401 is insufficient. |
| T7 — retire client engine | After prerequisites and fixed account switch, remove unreachable legacy branches with per-symbol references checked across main/tests/instrumentation; delete engine/transport/interfaces/test, together with their now-unused database staging methods. | Remove the interface and dependent methods/files in a buildable unit; update forced-password flow in the same unit that removes its legacy fields/callback. Preserve SyncFailure, hasPendingSync, changeToken, snapshot/report APIs, sync_control and logout fallback. Re-run JVM and both builds; instrumented upgrade/offline QA remains required. |
| T8 — review and deliver working changes | Review the exact deployed/tree bundle difference, including ledger admin additions and migration dependencies; preserve custom Auth/device/signature checks and verified per-function JWT configuration. Prepare rollback and QA credentials externally. | No deployment until explicitly authorized. Authenticated approved-device enrollment, signed write/read/activity, refresh, logout, account switch, admin access, and relevant WhatsApp behavior pass. Distribute a tested APK; old installed clients still matter. Deploy only changed functions; no mechanical Auth shrink. |
| T9 — conditional server retirement | Keep Phase C out of the first fix release. Later, migrate/settle both unsigned stores, preserve debts AND payments, inventory installed clients, and resolve every retained legacy caller. | Explicit reviewed store decisions; backup/reconciliation of balances and IDs; valid-session tests prove removed routes are unavailable. Keep makeSnapshot/guard wherever needed. Remove unused helpers only after complete caller proof. No table drop or arbitrary data loss. |
| T10 — optional preview and accurate docs | Last: delete the 14-line static preview wrapper if desired and update its runbook; update CONTINUE_HERE/CHANGELOG/README/GLOSSARY to describe verified results and remaining legacy callers. Archive superseded narration only if useful. | Dashboard build remains successful; local preview instructions match the published assets' script/connect policies. Document app-tested versus device-tested versus deployed separately. This has no priority over financial/session fixes. |

Recommended delivery units: (1) T0-T4 correctness fixes with their regressions; (2) T5-T6 tested activity backend/client and reviewed rollout; (3) T7 client deletion once no live caller depends on it; (4) T9 server retirement after consumer cutover. T8 is a release gate around the units that need deployment, not permission to publish the whole tree unexamined.

## Corrections to the supplied plan

- A.2 removes fields and `offerLegacyDataChoice` while `changeServerPassword` still uses them until A.3. Move that caller update into the same buildable task.
- A.4 removes `importSnapshot` before B.1 removes account switching's call, and drops an interface while the engine remains. Fix B.1 first; remove connected dependency sets together.
- `LedgerSync\\.` in a substring search also matches `SignedLedgerSync.`. Use symbol boundaries and inspect references individually. A.1's expected two matches overlooks `HttpSyncTransport` and its interface while they still exist.
- `LedgerSyncTest` currently has **14 passing tests**. Removing only it would mathematically leave 53 baseline tests, not 57. Added regressions change that number; measure the eventual suite rather than using either forecast as acceptance.
- A branch/tag created before committing pending changes snapshots only HEAD. A recovery point must include the actual intended source tree. Do not use forced ref updates casually.
- Do not make old activity-route deletion depend only on a new client file existing. Ensure the new route/RPC is deployed, authorized and works; check installed-client support requirements.
- Do not expect an unauthenticated removed `/mobile/sync` to return 404 while `requireSession` precedes route selection; 401 can be correct. Likewise a nonexistent signed route can return 401 before routing. Use authenticated QA and bundle inspection.
- The activity log must describe what it covers: ledger operations including rejected operations, not all user/login/admin events. Do not expose raw protocol kinds as user-facing labels.
- Applied migration history is not repaired by renaming or editing old files. Compare local/remote history and review only the proposed DDL. Follow [Supabase migration workflow](https://supabase.com/docs/guides/local-development/cli-workflows); dry-run shows selection, not successful execution.

## Deferred work and practical limits

Do not drop the installed SQLite outbox/control/triggers in this change. Do not add another backend, Android project, dashboard copy or recovery framework. Do not prioritize cosmetic protocol renaming, dead production-function DDL, or gateway reduction over the defects above. Keep Phase E exclusions unless needed to fix a verified caller.

Verdict: fix rejected-payment side effects and credential origin first, then enrollment and multi-store session behavior. Treat the protocol deletion as a dependent cleanup, not the proof that the app works.

Lean: **-758 lines, -0 dependencies possible** from the measured six legacy client files (744) and optional preview wrapper (14), before branch/database deletions and newly required fixes/tests. This is gross removable text, not an asserted net diff; server deletion savings are deliberately unestimated while shared callers remain.

Not checked: no Android emulator/phone execution, no signed live financial write or credentialed login, no new migration application, no deployment, no installed-phone inventory, no cloud restore drill. The existing PGlite suite was inspected but not executed here because its workspace dependency is absent; T5 is required verification, not a passing result. The whole Flutter archive, PDF/Excel layout, biometric paths and every unrelated admin feature were not deeply audited. Several findings are source-traced, while the two probes and listed baseline checks were executed.

Official documentation and deeper backend analysis are in [the companion research](2026-10-12-supabase-research.md). Additional relevant guidance: [Supabase Auth sessions](https://supabase.com/docs/guides/auth/sessions), [Edge authorization](https://supabase.com/docs/guides/functions/auth-headers), and [per-function configuration](https://supabase.com/docs/guides/functions/function-configuration). The audit conclusions combine these contracts with the verified repository and read-only production evidence; documentation alone does not establish that a device flow works.
