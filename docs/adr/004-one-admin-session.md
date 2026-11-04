# ADR-004: One signed-in admin session per account

## Context

Supabase Auth accepts a password from any number of browsers at once, and its access token stays
valid until it expires. For an account that administers every store — freeze, archive, revoke
devices, read any ledger — two live sessions mean one of them can be someone the owner does not
know about, and the audit trail only shows the account, not the person. GoTrue has no "one session
only" switch, and the dashboard cannot be trusted to enforce it: the build that is live today, a
cached bundle, or `curl` all reach the same api.

## Decision

The api holds one seat per account, in `sadid_private.admin_session_lease(user_id, session_id,
claimed_at, seen_at)` (`schemas/42_admin_session_lease.sql`).

- **Claim on use.** `sessionLease` runs before every `/v1/admin/*` route and calls
  `sadid_admin_session_claim()`, which reads `sub` and `session_id` from the JWT — never from the
  body. The first request of a session claims the account; the same session renews it.
- **Refuse the second session.** A different live session gets `409 session_in_use`
  (`contract/errors.json`), before the route reads anything. The dashboard calls `POST
  /v1/admin/session` right after sign-in, so the refusal lands on the sign-in card instead of under
  a half-drawn board, and it signs out at once.
- **Release on sign-out.** `DELETE /v1/admin/session` deletes the caller's own row, so the next
  browser can sign in immediately. Both routes are registered before the lease middleware: releasing
  a seat must work even when the seat was taken.
- **Ten silent minutes free the account.** An open board renews every 60 s (`HEARTBEAT_MS` in
  `controller.ts`). A browser closed without signing out stops renewing, and the lease goes stale, so
  the account is not locked until someone remembers it. Ten minutes is the constant in
  `claim_admin_session()` and in the `session_in_use` message.
- **Per account, per role alike.** `support` claims its own seat too; a store account has no seat
  and is refused by the role check, not by the lease.
- **`force_password_change`** claims nothing: the user has to change the password before the board
  opens, and the release route answers with the same refusal.

## Consequences

- Every admin request costs one extra small RPC row read or update. Admin traffic is a handful of
  requests per minute, so this is accepted.
- The seat is claimed at sign-in and cannot be claimed again from a second device; a phone or a
  second laptop has to wait for the other board to sign out or go silent. There is no "take over"
  button, by design: a takeover would hand the account to whoever typed the password.
- Taking the account needs the same password the holder used. This protects against a second
  person with the same credentials and against a forgotten session, not against a stolen password.
- A raised `seen_at` window widens the lock-out; lowering it makes an idle board lose its seat
  sooner. Both knobs live in one place.
- Deleting an Auth user cascades the lease row away (`on delete cascade`).
