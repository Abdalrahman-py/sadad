# Glossary — سدد

One home for the nouns this repository uses. Two vocabularies live here: the **domain** nouns (what a
store owner's business is made of) and the **architecture** nouns (how the code is shaped). Use these
words exactly; when a term is missing, add it here rather than inventing a synonym at the call site.

Names in `monospace` are the real symbols, so a reader can jump from the word to the code.

## Domain

**Store** (`متجر`) — the tenant. One store owner's business, one ledger, one subscription. The admin
surface stores it; the app is logged into exactly one at a time (`SessionStore.syncAccountId`).

**Ledger** (`السجل`) — the store's financial record: who owes what, and what was paid. Locally it is
`SadadDatabase` (`contacts` / `debts` / `payments`); server-side it is the `sadid_*` tables.
The old remote financial tables are scheduled for deletion by the reviewed retirement migration.

**Contact** (`شخص`) — a customer or a supplier. Balances are derived, never stored: debts minus
recorded payments.

**Debt** (`دين`) — an amount owed in one direction (`receivable` = the contact owes the store,
`payable` = the store owes the contact). Money is integer cents internally (`amount_cents`), shekels
on the wire (`amount`).

**Payment** (`دفعة`) — a settlement against a debt. Can be reversed; a reversed payment stops
counting toward the debt's remaining balance.

**Snapshot** — a whole ledger state. Signed sync caches the server's snapshot in `EncryptedJournal`;
`SadadDatabase.importLedgerView` projects it with pending operations into the screen tables.
`getSnapshot` remains for local export and the standalone edition.

**Delta** — the removed protocol's batch of changed rows. The connected Android edition sends
signed operations; no current client uses a row delta endpoint.

**Outbox** (`sync_outbox`) — removed local storage for the old protocol. SQLite schema version 9
drops its table and triggers; signed sync uses the encrypted journal.

**Watermark** — the old client's outbox sequence captured before downloading. Its staging engine
was removed; it is not the signed ledger cursor.

**Revision** — a retained server counter for account metadata changes. Signed ledger positions
use cursor and epoch; `SessionStore` no longer stores the old protocol revision.

**Conflict** — a change cannot be accepted against the expected version or recovery epoch. Signed
sync preserves the original journal operation and its verdict; it does not replace the whole ledger.

**Ledger initialization** — presence of `sadid_ledger_state` for a store. `sadid_is_v3_store`
reports this readiness state; it does not select a second financial protocol.

**Legacy ledger** — the removed snapshot/delta protocol. All stores are test stores, as confirmed
by the owner; remaining cloud retirement work is gated in `docs/CONTINUE_HERE.md`.

**Signed ledger** (`v3`) — the adopted ledger. Every request carries a device signature, and the
device must be enrolled and approved before it may write. Not all test stores are initialized;
the measured inventory and cutover gate are in `docs/CONTINUE_HERE.md`.

**Installation** — a signed identity scoped to project and store: a non-exportable P-256 key in the
Android Keystore plus a stable UUID (`InstallationKey`). Different stores on one phone have distinct
identities. Old global test identities are never adopted by a store.

**Store device** — a phone registered to a store (`store_devices`), with a staff name, identity metadata,
and its own revocation state. Distinct from an installation.

**Operation** — a single signed ledger change (`contact.create`, `debt.create`, `payment.create`),
carrying an `operationId`, an `entityId`, and the operations it `dependsOn`.

**Compound action** — one user save represented by several operations, such as a contact with its
first debt or a payment split over debts. `LedgerQueue` validates it before writing;
`EncryptedJournal.addBatch` persists all operations in one transaction or none.

**Activity** — the latest 200 ledger events, including operation verdicts, scoped to a store and
ordered by descending sequence (`sadid_activity`). Login and admin audit events are separate.

**Receipt** — the server's per-operation verdict: `accepted`, or a rejection with a code. A receipt
is what turns a queued operation into a confirmed one.

**Epoch** — the server's recovery generation. A change of epoch invalidates in-flight receipts and
forces review before further sending.

**Lease** — the server-signed permission, valid for a bounded window, that allows a device to add
entries while offline.

**Recovery** — the sealed export of a device's journal, and its permitted restoration onto another
device. Sealed with PBKDF2-HMAC-SHA256 then AES-256-GCM; the installation key is never included
(`RecoveryCrypto`).

## Architecture

**Module** — anything with an interface and an implementation: a function, class, package, or a
tier-spanning slice. Not "unit", not "component", not "service".

**Interface** — everything a caller must know to use a module correctly: the signature, plus
invariants, ordering, error modes, required configuration, and performance. Not "API", not
"signature" alone.

**Implementation** — what is inside a module. A module may be internally composed of small pieces;
they are simply not part of the interface.

**Depth** — leverage at the interface: how much behaviour a caller gets per unit of interface they
must learn. **Deep** = a lot of behaviour behind a small interface. **Shallow** = the interface is
nearly as complex as the implementation.

**Seam** — the location where a module's interface lives; the place where behaviour can be altered
without editing in there. Not "boundary".

**Adapter** — the concrete thing that satisfies an interface at a seam. One adapter means a
hypothetical seam; two mean a real one.

**Leverage** — what callers get from depth: one implementation paying back across N call sites and M
tests.

**Locality** — what maintainers get from depth: change, bugs and verification concentrated in one
place. Fix once, fixed everywhere.

**Deletion test** — imagine deleting the module. If the complexity vanishes it was a pass-through; if
it reappears across N callers it was earning its keep.

**The interface is the test surface** — callers and tests cross the same seam. A test that must reach
past the interface is evidence the module is the wrong shape.

### Where the sync seam lives

`LedgerQueue` is the application interface for reads, writes, activity and one sync pass.
`SignedLedgerSync` owns the signed protocol through `LedgerApi`, `LedgerJournal`,
`InstallationSigner`, `DeviceEnvironment` and `LedgerSession`.
`SignedLedgerSync.Outcome` is the vocabulary a caller acts on. `MainActivity` and
`LedgerSyncService` serialize sync and identity changes with `SyncScheduler.LOCK`; UI dialogs stay
with the activity. The older `LedgerSync` and its transport/interfaces have been removed.
