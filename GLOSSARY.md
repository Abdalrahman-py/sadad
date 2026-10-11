# Glossary — سدد

One home for the nouns this repository uses. Two vocabularies live here: the **domain** nouns (what a
store owner's business is made of) and the **architecture** nouns (how the code is shaped). Use these
words exactly; when a term is missing, add it here rather than inventing a synonym at the call site.

Names in `monospace` are the real symbols, so a reader can jump from the word to the code.

## Domain

**Store** (`متجر`) — the tenant. One store owner's business, one ledger, one subscription. The admin
surface stores it; the app is logged into exactly one at a time (`SessionStore.syncAccountId`).

**Ledger** (`السجل`) — the store's financial record: who owes what, and what was paid. Locally it is
`SadadDatabase` (`contacts` / `debts` / `payments`); server-side it is the `sadid_*` tables plus the
legacy `contacts` / `debts` / `payments`.

**Contact** (`شخص`) — a customer or a supplier. Balances are derived, never stored: debts minus
recorded payments.

**Debt** (`دين`) — an amount owed in one direction (`receivable` = the contact owes the store,
`payable` = the store owes the contact). Money is integer cents internally (`amount_cents`), shekels
on the wire (`amount`).

**Payment** (`دفعة`) — a settlement against a debt. Can be reversed; a reversed payment stops
counting toward the debt's remaining balance.

**Snapshot** — a whole ledger revision, as one object (`getSnapshot`, `importSnapshot`).

**Delta** — the queued slice of changes, bounded at 500 rows, uploaded in one batch and acknowledged
as a unit (`pendingDelta`, `acknowledgeDelta`).

**Outbox** (`sync_outbox`) — the local queue of rows that changed and have not been acknowledged.
Written by database triggers, never by hand.

**Watermark** — the outbox sequence number captured *before* a download starts. The commit is refused
if the watermark moved, so edits made during a download are never overwritten
(`outboxWatermark`, `commitSnapshotStage`).

**Revision** — the server's monotonic version of a store's ledger. The app carries its own copy
(`SessionStore.revision`) and only ever adopts the value the server reports.

**Conflict** — the server is ahead *and* the device has unsynced edits. Nothing is overwritten: the
owner is asked which side to keep.

**Ledger mode** (`ledger_mode`) — which protocol a store's ledger speaks: `legacy` (`/mobile/*`) or
`v3` (the signed ledger). Set once from the server's answer at login, never guessed mid-sync.

**Legacy ledger** — the older protocol. Still correct, and still what a non-v3 store uses.

**Signed ledger** (`v3`) — the adopted ledger. Every request carries a device signature, and the
device must be enrolled and approved before it may write. Every real store currently runs on this.

**Installation** — the signed ledger's identity for one app installation: a non-exportable P-256 key
in the Android Keystore plus a UUID (`InstallationKey`). Lost with the device by design.

**Store device** — a phone registered to a store (`store_devices`), with a staff name, permissions,
and its own revocation state. Distinct from an installation.

**Operation** — a single signed ledger change (`contact.create`, `debt.create`, `payment.create`),
carrying an `operationId`, an `entityId`, and the operations it `dependsOn`.

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

`LedgerSync` is the module; `SyncSession`, `SyncLedger` and `SyncTransport` are its seam, implemented
by `SessionStore`, `SadadDatabase` and `HttpSyncTransport` respectively. `LedgerSync.Outcome` is the
vocabulary a caller acts on. Callers keep the lock, the threading, and every dialog — the module owns
the protocol and nothing else.
