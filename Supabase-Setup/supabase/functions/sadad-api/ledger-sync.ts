/**
 * Pure helpers for the Android offline-sync contract:
 *   - the batched delta upload   (POST /mobile/sync-delta)
 *   - the paged ledger download  (GET  /mobile/snapshot-page)
 *
 * Kept out of index.ts so they can be unit-tested with `deno test`, the same pattern as
 * admin-ledger.ts. Wire shape: camelCase keys, money in shekels — exactly what the app reads.
 *
 * Semantics mirror the legacy Node server (server/server.js: function syncDelta), which is the
 * implementation the 2.5.10 app was built and tested against.
 */

export type WireRow = Record<string, any>;
export type WireSnapshot = { contacts: WireRow[]; debts: WireRow[]; payments: WireRow[] };
export type DeltaBatch = {
  contacts?: WireRow[];
  debts?: WireRow[];
  payments?: WireRow[];
  deleted?: Record<string, unknown>;
};

export const LEDGER_TABLES = ["contacts", "debts", "payments"] as const;
export type LedgerTable = (typeof LEDGER_TABLES)[number];

/** Batch ceiling the client also enforces: one delta page never exceeds 500 records. */
export const DELTA_BATCH_LIMIT = 500;
/** Page size of the paged ledger download. */
export const SNAPSHOT_PAGE_LIMIT = 500;

/** Row ids in one delta table, validated as positive safe integers. */
export function rowIds(rows: unknown, label: string): number[] {
  if (!Array.isArray(rows)) throw new Error(`invalid_rows:${label}`);
  return rows.map((row) => {
    const id = Number((row as WireRow)?.id);
    if (!Number.isSafeInteger(id) || id < 1) throw new Error(`invalid_id:${label}`);
    return id;
  });
}

/** Deletion lists of a batch, validated. A missing `deleted` object means no deletions. */
export function deletedIds(deleted: unknown, table: LedgerTable): number[] {
  if (deleted === undefined || deleted === null) return [];
  if (typeof deleted !== "object" || Array.isArray(deleted)) throw new Error("invalid_deleted");
  const value = (deleted as Record<string, unknown>)[table];
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new Error(`invalid_deleted:${table}`);
  return value.map((entry) => {
    const id = Number(entry);
    if (!Number.isSafeInteger(id) || id < 1) throw new Error(`invalid_deleted_id:${table}`);
    return id;
  });
}

/** Total records a batch touches: upserts plus deletions. */
export function batchSize(delta: DeltaBatch): number {
  let count = 0;
  for (const table of LEDGER_TABLES) {
    const upserts = delta[table] ?? [];
    if (!Array.isArray(upserts)) throw new Error(`invalid_rows:${table}`);
    count += upserts.length + deletedIds(delta.deleted, table).length;
  }
  return count;
}

/**
 * Merge one delta batch into the current ledger.
 *
 * A deleted contact also removes its debts and those debts' payments, including rows that were
 * uploaded in an earlier batch — the same cascade the legacy server applies. A deletion in the
 * batch always wins over an upsert of the same id.
 *
 * Rows the batch does not mention are preserved untouched, which is what makes this safe to pass
 * to the existing full-snapshot commit path: "removed" then means genuinely deleted, not "absent
 * from this page".
 */
export function mergeDeltaIntoSnapshot(
  current: WireSnapshot,
  delta: DeltaBatch,
  deleted: Record<string, number[]>,
): WireSnapshot {
  const rows: Record<LedgerTable, Map<number, WireRow>> = {
    contacts: new Map(),
    debts: new Map(),
    payments: new Map(),
  };
  for (const table of LEDGER_TABLES) for (const row of current[table]) rows[table].set(Number(row.id), row);

  const removedContacts = new Set(deleted.contacts ?? []);
  const removedDebts = new Set(deleted.debts ?? []);
  const removedPayments = new Set(deleted.payments ?? []);

  // Cascade parents to children using the rows that actually exist on the server.
  for (const debt of current.debts) if (removedContacts.has(Number(debt.contactId))) removedDebts.add(Number(debt.id));
  for (const payment of current.payments) if (removedDebts.has(Number(payment.debtId))) removedPayments.add(Number(payment.id));

  for (const row of delta.contacts ?? []) rows.contacts.set(Number(row.id), row);
  for (const row of delta.debts ?? []) rows.debts.set(Number(row.id), row);
  for (const row of delta.payments ?? []) rows.payments.set(Number(row.id), row);

  for (const id of removedContacts) rows.contacts.delete(id);
  for (const id of removedDebts) rows.debts.delete(id);
  for (const id of removedPayments) rows.payments.delete(id);

  return {
    contacts: [...rows.contacts.values()],
    debts: [...rows.debts.values()],
    payments: [...rows.payments.values()],
  };
}

/**
 * One Postgres row -> the camelCase row the app stages into its local SQLite tables.
 * Mirrors the legacy server's mapping: drop the server-side columns, convert the money and
 * boolean columns explicitly, and camelCase the rest.
 */
export function ledgerRowToWire(record: WireRow): WireRow {
  const row: WireRow = {};
  for (const [key, value] of Object.entries(record)) {
    if (key === "store_id" || key === "server_received_at") continue;
    if (key === "amount_cents") {
      row.amount = Math.round(Number(value || 0)) / 100;
      continue;
    }
    if (key === "credit_limit_cents") {
      row.creditLimit = Math.round(Number(value || 0)) / 100;
      continue;
    }
    if (key === "whatsapp_opt_in") {
      row.whatsappOptIn = value === true;
      continue;
    }
    row[key.replace(/_([a-z])/g, (_match, letter: string) => letter.toUpperCase())] = value;
  }
  return row;
}
