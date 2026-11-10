# ADR-005: Room is the only copy of the ledger

## Context

The app kept the whole ledger resident: `LedgerRepository` loaded every Contact, Debt and Payment
from Room into an in-memory `LedgerState`, and recomputed one `StateFlow<LedgerView>` every screen
read. Room already held the same rows, so the phone carried a second and third copy.

The cost was measured, not estimated:

- Device pass dev-92 (2026-11-03): the `ledger_load` span took **2,773 ms on every cold start**,
  before Home could show a balance.
- Laptop probe at 5,000 people and 45,000 Debts and Payments: the resident ledger took
  **≈140–160 MB**.

Tickets 159, 162, 163 and 165 had already moved every screen to a Room query, and ticket 167 made
every write check one person's rows. The resident projection was the last reader of itself.

## Decision

Room is the only copy of the ledger. No `LedgerState` or whole-store `LedgerView` is kept after an
operation ends:

- Home, People and quick add read the per-person `contact_summary` table.
- The contact page and every write read one person's rows (`LedgerStore.loadPerson`).
- Activity reads one period (`LedgerStore.loadPeriod`); a statement reads the whole store once and
  keeps it for that export only.
- A person's queued commands are projected over that person's rows with `LedgerState.view`.

Opening a store therefore reads no ledger rows: it answers with one count per table. The one-time
summary fill for phones arriving from schema 5, and a statement export, are the only whole-store
reads left; both drop the snapshot when they finish. Until the fill ends, Home, People and quick
add show their loading state instead of falling back to a view.

## Consequences

- Cold start goes straight to Home's summary queries, and the heap no longer grows with the store.
- Any new screen must read a Room query; there is no whole-store view to collect. A screen that
  needs facts across all people asks for the query it needs (a summary, a period, a statement),
  not for the ledger.
- `outbox.compact` no longer receives a `LedgerState`; it asks Room which entity ids exist, one
  chunked query per table.
- Sync no longer waits for the store to finish loading before its first pull, because nothing needs
  the resident copy to be folded first.
