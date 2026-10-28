# Glossary — سدد

One home for the nouns this repository uses. Two vocabularies live here: the **domain** nouns (what a
store owner's business is made of) and the **architecture** nouns (how the code is shaped). Use these
words exactly; when a term is missing, add it here rather than inventing a synonym at the call site.

Names in `monospace` are the real symbols, so a reader can jump from the word to the code.

## Domain

**Store** (`متجر`) — the tenant. One store owner's business, one ledger, one subscription (`stores`,
`store_memberships`). The admin dashboard manages stores; the app signs into one store at a time.

**Ledger** (`السجل`) — the store's financial record: who owes what, and what was paid. Server-side it
lives in `sadid_ledger_state`, `sadid_contacts`, `sadid_debts`, `sadid_payments`,
`sadid_adjustments`, `sadid_reversals`, `sadid_operations` and `sadid_events`; on the device it is
folded into `LedgerState` and projected with the outbox into `LedgerView`.

**Contact** (`شخص`) — a customer or a supplier (`sadid_contacts`, `ContactRow`, `ContactView`).
Its `kind` (`زبون` / `مورد`, `ContactKind`) is required and decides a Debt's direction
(`ContactKind.debtDirection`); it is editable and existing Debts keep their direction. Balances are
derived, never stored: `LedgerView.net` is open receivable minus open payable for the person.

**Debt** (`دين`) — an amount owed in one direction (`Direction.RECEIVABLE` / `receivable` = the
contact owes the store, `Direction.PAYABLE` / `payable` = the store owes the contact; `sadid_debts`,
`DebtRow`, `DebtView`). Money is integer cents (`Cents`, `amount_cents`).

**Payment** (`دفعة`) — a settlement against a debt (`sadid_payments`, `PaymentRow`, `PaymentView`).
Can be reversed (`sadid_reversals`); a reversed payment stays listed but stops counting toward the
debt's remaining balance.

**Credit** (`رصيد له`) — money the store holds for a customer because they paid more than they owed.
It is a `payable` Debt whose `origin` is `overpayment` (`DebtOrigin.OVERPAYMENT`, `sadid_debts.origin`,
the column default `manual` for every Debt typed in by hand); `PaymentAllocation.allocateWithCredit`
splits a payment into the parts that settle Debts plus the excess that becomes the credit.

**Offset** (`خصم من الرصيد`) — the one command that spends a credit: `debt.offset`
(`CommandType.DEBT_OFFSET`, payload `DebtOffset { receivableDebtId, payableDebtId, amountCents }`,
row `sadid_offsets`, `OffsetRow`). Both Debts belong to the same Contact and face opposite ways, the
amount is at most the remaining of each, and an offset is **not cash**: Activity's «تحصيل» and «دُفع»
and the payment counts never include it. A Debt's remaining is `amount − payments − offsets`
(`DebtView.offsetted`).

**Installation** — a device identity scoped to project and store: a non-exportable P-256 key in the
Android Keystore plus a stable UUID (`installations`, `Origin`). Every mobile ledger request carries a
device signature over `ProofText`, and the installation must be enrolled and approved before it may
write.

**Operation** — one signed ledger command (`LedgerCommand`, built by `Commands`; stored in
`sadid_operations` and `sadid_events`), carrying an `operationId`, an `entityId`, a `CommandType` and
the operation IDs it `dependsOn`. Every request to the mobile ledger endpoints is signed by the
installation's P-256 key.

**Compound action** — one user save represented by several commands sent together in an
`OperationsRequest`, such as creating a contact with its first debt or splitting a payment across open
receivable debts (`PaymentAllocation.allocate`).

**Outbox** — the device's queue of commands not yet settled by the server (Room table, Phase 3), with
states `queued`, `accepted`, `rejected` and `cancelled`.

**Receipt** — the server's per-command verdict (`Receipt`, `ReceiptStatus`): `accepted`, or `rejected`
with an error `code`. A receipt settles a queued command in the outbox.

**Epoch** — the store's ledger generation UUID in `sadid_ledger_state.epoch`. The baseline never
changes it (`02_ledger_tables.sql`), so a change only means "pull again from 0".

**Lease** — the server-signed permission, valid for a bounded window, that allows an approved
installation to queue offline commands (`CommandType.offline`).

**Recovery** — the sealed export of a device's unsettled commands, and its admin-approved restoration
onto another installation (`sadid_private.recovery_permits`). Sealed with PBKDF2-HMAC-SHA256 then AES-256-GCM;
the installation private key is never exported.

**Activity** — the latest 200 change events (`ChangeEvent` from `sadid_events`) kept on the device (no
server routine).

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

The device reads one `LedgerView` computed by `LedgerState.view` from the confirmed `LedgerState`
plus the outbox's unsettled `LedgerCommand`s. Sync is one pass (session, enroll, lease, push
`OperationsRequest`, pull `Changes`, compact outbox) run by WorkManager (Phase 3 tickets 45–47).
