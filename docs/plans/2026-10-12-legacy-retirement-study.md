# Legacy retirement safety study — 12 October 2026

This is a point-in-time study, not a second work-status page. Current deployment gates live in
[CONTINUE_HERE](../CONTINUE_HERE.md). Baseline before retirement: pushed commit `7f897bf` on
`codex/supabase-ledger-correctness`.

## Decision and evidence

**Safe to remove the unused financial protocol from source.** The owner explicitly confirmed that
every store and all data are test-only. There are no real customers whose old clients or financial
records require backward compatibility. That removes the earlier reason to defer T9.

The Android connected edition already writes through `LedgerQueue` and reads the signed ledger.
Per-symbol searches found no current Android caller for snapshot/delta upload, old activity,
cloud WhatsApp configuration/report delivery, or old restore endpoints. The dashboard did still
read old financial summaries and expose restore controls: those callers were removed together.
Current login, password changes, store-device limits, account controls and signed recovery remain.

The source cleanup reaches:

- `sadad-api`: eight financial/WhatsApp routes, snapshot/delta/receipt helpers, restore routes,
  obsolete device permission/editing routes and the unused `ledger-sync` module/tests removed.
  Login no longer fetches financial snapshots; every new account uses the signed ledger, awaiting enrollment.
- Static dashboard: signed summaries and audited signed detail only; uninitialized accounts show
  empty finances. Old restore/archive and cloud WhatsApp controls removed. Login device metadata
  is read-only; restoring a revoked login device remains available with device-limit checks,
  independently of signed installation approval/revocation/recovery.
- Android: SQLite version 9 removes nine queue triggers and the two old sync tables. Its upgrade
  keeps standalone financial rows. A connected signed view removes unmapped pre-signed test rows;
  standalone export/import still uses its local financial tables.
- Installation identity: the global test identity is no longer adopted. Existing scoped identities
  remain stable across reopenings. A phone using the old global identity needs new enrollment and
  approval; this is accepted test-client breakage, not transparent identity migration.
- Repository: 99 tracked Flutter archive files removed after checking build/runtime consumers.
  They remain recoverable in Git, including `pre-consolidation-2026-10-11:archive/Sadad-Flutter`.

## Cloud dependency review

Read-only inspection of project `vhftjmiltqfwmrszdtgf` found seven old financial/protocol tables:
`contacts`, `debts`, `payments`, `store_backups`, `contact_archives`, `sync_receipts`, `audit_events`.
Five unused cloud WhatsApp configuration columns on `stores` also have no current consumers.
Their dependencies are owned constraints/indexes/types/triggers, plus the expected financial
foreign keys. There were no outside views or table consumers in the catalog dependency result.

Function-definition searches found eight old public RPCs and the private `block_legacy_write`
trigger function. Searches for calls to those routines found only that same retired family.
Catalog dependencies alone are insufficient because PL/pgSQL callers and Edge source can retain
text references; both function bodies and current/deployed Edge source were checked.

The prepared migration
[`20261012174647_retire_test_legacy_ledger.sql`](../../Supabase-Setup/supabase/migrations/20261012174647_retire_test_legacy_ledger.sql)
drops that family explicitly, in foreign-key order, transactionally, with `RESTRICT` and a five-second
lock timeout. Unexpected dependencies fail the migration rather than being deleted by `CASCADE`.
It does not touch signed ledger data, store identities, sessions or installation authorization.

**Applying it before deploying the cleaned API is unsafe.** The deployed API v6 still reads the old
tables in login/admin flows. Reviewed deployment order is: activity migration → ledger function →
cleaned API and dashboard → authenticated QA → retirement migration last. Do not automatically
push all local migrations: cloud/local migration numbering differs. The new migration is prepared
and tested locally only; no remote data has been deleted.

## Verification and limits

- Public RPC TDD slice: the retirement test failed with `Missing expected rejection` before the
  migration, then passed after it; the service role receives PostgreSQL `42883` for the old summary.
- Full local migration chain: 43 ledger, 10 authorization and 12 admin checks pass (**65**).
  Current writes, snapshots, activity, recovery, authorization and audited admin controls survive.
- Edge: API 12, Auth 11, ledger 5 tests pass (**28**); all three entrypoints type-check.
  API route tests reject legacy financial reads in the external database fake, exercise retired
  routes and cover both initialized and uninitialized dashboard accounts. A separate red→green
  route test preserves login restoration after revocation and rejects a full device limit.
- JVM: **72 tests, 71 passed, one skipped, no failures/errors**. Both Android editions and the
  instrumentation APK compile. The global installation adoption regression was observed failing
  before removal and passes afterward.
- Dashboard: syntax and static build pass; all 109 static ID selectors match the HTML.
- SQLite upgrade and Keystore checks are device instrumentation, not JVM guarantees. The isolated
  QA runner includes the upgrade regression but has not run on a phone; none is connected.
- No cloud migration or function/dashboard deployment was performed. Read-only function inventory
  remains API v6, Auth v4 and ledger v7; the checked-in API review patch compares the complete
  candidate bundle against v6. Auth does not require redeployment.
- Read-only security advisors reported 12 informational RLS-without-policy notices and one existing
  [leaked-password protection warning](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).
  The retirement migration adds no grants or policies; live Auth settings were not changed.

## What stays, and why

Supabase is the connected app's only backend. `sadad-api` and `sadad-auth` are current identity/admin
services inside Supabase, not an alternative financial backend. Removing them would break login,
device limits, account administration and the Auth envelope used by signed requests.

SQLite financial tables serve the standalone edition and connected screen projection. Removing
them would break both editions. The encrypted journal is the signed client's operation queue.
Applied SQL migration history and Git recovery refs document how the current schema was reached;
they are historical records, not active compatibility paths. Old protocol code need not remain
in the working tree to preserve that history.

The retirement DDL intentionally destroys old test financial rows and old backup/archive data.
Rollback would require a reviewed pre-deletion database export, not merely an Edge function rollback.
Record the export outside this public repository before the approved remote deletion.
