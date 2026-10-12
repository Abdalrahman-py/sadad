# Team admin dashboard

Use the [published dashboard](https://abdalrahman-py.github.io/sadad/). Store accounts, financial balances, phone approval, recovery permits, and admin activity are integrated in this screen.

The shared test username is `admin_sadid`, provisioned in the live `sadid` Supabase project (`vhftjmiltqfwmrszdtgf`). Get its password from the team lead. Starting the dashboard does not create or reset an admin account.

## Open on any team machine

Open [the hosted dashboard](https://abdalrahman-py.github.io/sadad/) with internet access and sign in.
The page returned HTTP 200 on 12 October 2026. Backend/source deployment drift is recorded in
`docs/CONTINUE_HERE.md`; a successful page load does not verify its latest backend features.

For static publishing, run `node tools/build-admin-pages.cjs` from the repository root. It builds
`_site/` from `server/public/`. The local HTTP preview script has been retired.

## Create and activate a store

1. Create the account with a store name, unique username, subscription and temporary password of **12–128 characters**. Copy the displayed password and share it securely.
2. The owner signs in through the app and changes the temporary password.
3. The phone's enrollment request appears in the store's **اعتماد هواتف المتجر** section. Approve it with a reason.
4. The owner can synchronize the ledger once the account/subscription is active and the phone is approved.

The current backend allows **one approved ledger phone per store**. The login-device limit controls entry to the account; increasing it does not grant additional phones ledger synchronization. Approving a replacement phone revokes the preceding phone's approval. Revoked phones need a new installation registration before they can be approved again.

## Store administration

- Name, username, verification, active-contact limit, login-device limit, feature permissions save to live Supabase.
- Subscription duration, extension, pause, suspension and activation use hosted account rules.
- Password reset forces a change at next login and terminates both login and ledger session authorizations.
- Phone requests, approval, revocation, login-session termination and all-device revocation are available in the selected store. After revocation or restoring an archived account, allow the phone to log in from the login-device list, then approve its new signed registration separately. Allowing login does not approve ledger access.
- Recovery accepts the original command list from the replacement phone's recovery screen. Select the revoked old phone and approved replacement, enter a reason, approve the exact list and copy the resulting permit into the phone. Permits expire after 24 hours. Before approval the dashboard previews the list: entry counts by type, both money totals, the entry date range, every entry in full, and any problem that would make the server reject it — a command that does not originate from the selected old phone or generation, a missing or duplicated operation id, an unsupported schema version, or an invalid amount, direction or method. Approval stays disabled while a blocking problem remains, and the confirmation names the totals rather than only the count.
- Archiving disables the account and devices while preserving financial history. Enable **إظهار المؤرشفة** to inspect or restore it. Restoring leaves it suspended; activate it and approve a newly registered phone separately. Permanent deletion is available only when the account has no current ledger or installation history.

## Financial data and audit

The main screen reads current ledger tables through authenticated, audited server functions. The list and detailed balances use the latest debt correction and exclude reversed payments from paid totals. Original payments, corrections and reversals remain in the transaction report. Archived contacts remain visible with their historical balances.

Opening a store records the admin identity, reason, request ID and record counts. Financial summaries are also audited. Ledger activity, phone decisions, recovery permits and account actions appear within the store. CSV export includes the financial transactions.

Admins inspect the ledger and authorize recovery; financial corrections remain owner operations in the app's immutable ledger. The dashboard exposes signed recovery only. Stores awaiting their first ledger enrollment show empty financial data. The retired snapshot/backup restoration controls have been removed from source; publishing and database retirement are gated in `CONTINUE_HERE.md`.

## Backend deployment and checks

The `admin_dashboard_integration` migration and updated `sadad-api` function are deployed to the hosted project. The dashboard requires those backend changes. Migration source is in `Supabase-Setup/supabase/migrations/`; the edge function and its normalization tests are in `Supabase-Setup/supabase/functions/sadad-api/`.

For local database tests, install the pinned test runtime exactly as `README.md` documents it in
"الاختبارات" (one pinned pglite version, one place to change it):

```sh
npm install --prefix .local-tools --no-save @electric-sql/pglite@0.5.8
node Supabase-Setup/scripts/test-admin-dashboard.mjs
node Supabase-Setup/scripts/test-v3-ledger.mjs
```

For normalization/type checks, use Deno inside the `sadad-api` function directory:

```sh
deno test admin-ledger_test.ts
deno check --config deno.json index.ts
```

## GitHub Pages

The dashboard is published at <https://abdalrahman-py.github.io/sadad/> from the owner's mirror. The workflow is prepared for the original `ibrahem57/sadad` owner to enable GitHub Pages with GitHub Actions as well. `node tools/build-admin-pages.cjs` builds `_site/` using an explicit dashboard asset list.

Pages serves static UI; login and administration still go directly to live Supabase. Only the existing public project key is in frontend assets. Admin passwords, service credentials, databases and other delivery files are excluded.

The preview binds to `127.0.0.1` and serves static assets only; it does not control hosted authentication.
