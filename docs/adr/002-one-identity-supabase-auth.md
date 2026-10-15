# ADR-002: One identity on Supabase Auth

## Context

Three identity schemes coexisted (`docs/ARCHITECTURE.md` B3): scrypt passwords and `public.sessions` in `sadad-api`, a Supabase Auth JWT bound to legacy tokens through `sadad_auth_bindings`, and `X-Sadad-Admin` token hashes in `sadid-ledger`. Keeping two or three credentials consistent caused account-switch and revocation bugs. The test project is reset to one baseline (`Supabase-Setup/supabase/baseline/`, generated from `schemas/`), so nothing converts old rows.

## Decision

Supabase Auth is the only identity:
- **Store** = Auth user `<username>@stores.sadad.invalid`, linked to `public.stores` by `sadid_private.store_memberships(user_id, store_id)`. The `api` creates the Auth user, then calls `sadid_create_store`.
- **Admin** = Auth user with `app_metadata.role` `admin` (reads and writes) or `support` (reads only), read in SQL with `auth.jwt() -> 'app_metadata' ->> 'role'`; no access-token hook. Admin routines also require the Auth session to be live, so ending it removes access at once; a removed role takes effect when the JWT expires.
- **Device** = a row in `sadid_private.installations` (`label`, `staff_name`, `permissions`). Enroll and approve both refuse with `device_limit_reached` when the store already has `allowed_devices` active installations; approval never revokes a device by itself.
- **Capability gate**: `sadid_private.current_capability()` refuses a store whose status is not `active`, whose subscription is paused or expired, whose JWT has `force_password_change`, or whose Auth session was ended (`session_active` reads `auth.sessions`).

## Consequences

- Omitted from the baseline: `public.sessions`, `admins`, `login_rate_limits`, `sadad_auth_bindings`, `store_devices`, `sadid_private.installation_sessions`, the password columns of `stores`, the full-store `snapshot()` and the old helpers that read them.
- Ledger rules (idempotent `operationId`, receipts, epochs, immutable history, recovery permits) are unchanged; `sadid_apply_batch` applies up to 100 commands in one transaction and returns one receipt each. A command that raises (e.g. `dependency_pending`, `epoch_changed`) aborts the whole batch; the client resends it.
- Read routines (`sadid_changes`, `sadid_activity`, `sadid_outcomes`) take no row locks on `public.stores`.
- Store status, subscription, limits and permissions are columns of `public.stores` that the `api` updates with `service_role`. Lowering `allowed_devices` below the active count does not revoke devices; the `api` must refuse it or revoke first.
- A store row can be deleted only while it has no installations and no ledger (foreign keys restrict); its membership goes with it.
- The baseline is tested on pglite with a stubbed `auth` schema; that it loads and behaves the same on the reset cloud project is «غير محقّق» until ticket 27.

## Routines callable by `authenticated`

Only these `public` routines are the interface; the `sadid_private` routines behind them are not exposed through the Data API.

Store (caller's JWT; the ledger routines also need the `x-sadad-capability` header the `api` gets from `sadid_issue_capability`):
- `public.sadid_my_store() returns bigint`
- `public.sadid_enroll(p_installation uuid, p_key jsonb, p_nonce uuid, p_label text default '', p_staff_name text default '') returns jsonb`
- `public.sadid_apply(p_command jsonb) returns jsonb`
- `public.sadid_apply_batch(p_commands jsonb) returns jsonb`
- `public.sadid_changes(p_after bigint default 0, p_limit integer default 200) returns jsonb`
- `public.sadid_activity() returns jsonb`
- `public.sadid_outcomes(p_ids uuid[]) returns jsonb`

Admin and support (refuse every other caller with `admin_authorization_required`):
- `public.sadid_pending_installations() returns jsonb` (admin, support; devices with status `pending`)
- `public.sadid_admin_summaries(p_stores bigint[]) returns jsonb` (admin, support)
- `public.sadid_admin_controls(p_store bigint) returns jsonb` (admin, support)
- `public.sadid_support_snapshot(p_store bigint, p_reason text) returns jsonb` (admin, support)
- `public.sadid_approve_installation(p_installation uuid, p_reason text) returns jsonb` (admin)
- `public.sadid_revoke_installation(p_installation uuid, p_reason text) returns jsonb` (admin)
- `public.sadid_approve_recovery(p_old uuid, p_new uuid, p_manifest jsonb, p_reason text) returns jsonb` (admin)
- `public.sadid_admin_account_action(p_store bigint, p_action text, p_reason text) returns jsonb` (admin; `archive`, `unarchive`, `end-sessions`, `revoke-all`)

`service_role` only (the `api`, after it verifies the device proof or the admin role):
- `public.sadid_create_store(p_user uuid, p_name text, p_username text) returns bigint`
- `public.sadid_installation_key(p_user uuid, p_session uuid, p_installation uuid) returns jsonb`
- `public.sadid_issue_capability(p_user uuid, p_session uuid, p_installation uuid, p_nonce uuid, p_scope text, p_request jsonb default null) returns jsonb`
- `public.sadid_issue_recovery_capability(p_user uuid, p_session uuid, p_installation uuid, p_nonce uuid, p_request jsonb, p_permit uuid) returns jsonb`
