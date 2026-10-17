import { assertEqual } from "../test_support.ts";
import {
  adminHarness,
  assertRefusal,
  dbTest,
  seedLedger,
} from "./test_admin.ts";
import refusals from "../../../../../contract/fixtures/admin/refusals.json" with {
  type: "json",
};

const h = await adminHarness();
const support = await h.jwtFor("support");
const store = await h.seedStore();

const sami = crypto.randomUUID();
const huda = crypto.randomUUID();
const flour = crypto.randomUUID();
const rent = crypto.randomUUID();
const kept = crypto.randomUUID();
const undone = crypto.randomUUID();

// Sami owes 100.00, corrected to 80.00, paid 30.00 (and 10.00 reversed): 50.00 left.
// The store owes Huda 25.00: payable.
await seedLedger(h.database, store.storeId, {
  contacts: [
    {
      id: sami,
      name: "سامي",
      phone: "+970599000001",
      at: "2026-10-01T10:00:00Z",
    },
    { id: huda, name: "هدى", at: "2026-10-02T10:00:00Z" },
  ],
  debts: [
    {
      id: flour,
      contactId: sami,
      direction: "receivable",
      cents: 10000,
      note: "طحين",
      at: "2026-10-03T10:00:00Z",
    },
    {
      id: rent,
      contactId: huda,
      direction: "payable",
      cents: 2500,
      note: "إيجار",
      at: "2026-10-04T10:00:00Z",
    },
  ],
  payments: [
    { id: kept, debtId: flour, cents: 3000, at: "2026-10-05T10:00:00Z" },
    {
      id: undone,
      debtId: flour,
      cents: 1000,
      reversed: true,
      at: "2026-10-06T10:00:00Z",
    },
  ],
  corrections: [{ debtId: flour, cents: 8000, previousCents: 10000 }],
});

const ledger = async (path: string) =>
  (await (await h.send(
    support.jwt,
    "GET",
    `/admin/stores/${store.storeId}/ledger/${path}`,
  )).json()).ledger;

dbTest(
  "contacts carry balances in cents after corrections and reversals",
  async () => {
    const page = await ledger("contacts");
    assertEqual(page.totals, {
      receivableCents: 5000,
      payableCents: 2500,
      netCents: 2500,
    });
    assertEqual(
      page.rows.map((row: Record<string, unknown>) => [
        row.name,
        row.receivableCents,
        row.payableCents,
      ]),
      [["هدى", 0, 2500], ["سامي", 5000, 0]],
    );
    assertEqual(page.nextCursor, null);
  },
);

dbTest("debts and payments", async () => {
  const debts = await ledger("debts");
  assertEqual(
    debts.rows.map((row: Record<string, unknown>) => [
      row.id,
      row.originalCents,
      row.amountCents,
      row.paidCents,
      row.remainingCents,
    ]),
    [[rent, 2500, 2500, 0, 2500], [flour, 10000, 8000, 3000, 5000]],
  );
  const payments = await ledger("payments");
  assertEqual(
    payments.rows.map((row: Record<string, unknown>) => [
      row.id,
      row.contactName,
      row.amountCents,
      row.reversed,
    ]),
    [[undone, "سامي", 1000, true], [kept, "سامي", 3000, false]],
  );
});

dbTest("pages by cursor and searches on the server", async () => {
  const first = await ledger("contacts?limit=1");
  assertEqual(first.rows.map((row: { id: string }) => row.id), [huda]);
  const second = await ledger(`contacts?limit=1&cursor=${first.nextCursor}`);
  assertEqual(second.rows.map((row: { id: string }) => row.id), [sami]);
  assertEqual(second.nextCursor, null);

  const byPhone = await ledger("contacts?q=0599000001");
  assertEqual(byPhone.rows.map((row: { id: string }) => row.id), [sami]);
  const byNote = await ledger("debts?q=%D8%B7%D8%AD%D9%8A%D9%86");
  assertEqual(byNote.rows.map((row: { id: string }) => row.id), [flour]);
});

dbTest("every read leaves an audit row with its reason", async () => {
  const before = await h.database.query<{ n: number }>(
    "select count(*)::int as n from sadid_private.admin_access_events where store_id = $1",
    [store.storeId],
  );
  await ledger("debts?reason=%D9%85%D8%B1%D8%A7%D8%AC%D8%B9%D8%A9");
  const rows = await h.database.query<
    { reason: string; record_counts: unknown; n: number }
  >(
    "select reason, record_counts, count(*) over ()::int as n from sadid_private.admin_access_events where store_id = $1 order by created_at desc limit 1",
    [store.storeId],
  );
  assertEqual(rows[0].n, before[0].n + 1);
  assertEqual([rows[0].reason, rows[0].record_counts], ["مراجعة", {
    debts: 2,
  }]);
});

dbTest(
  "a store without a ledger, and a blank reason, are refused",
  async () => {
    const empty = await h.seedStore();
    await assertRefusal(
      await h.send(
        support.jwt,
        "GET",
        `/admin/stores/${empty.storeId}/ledger/debts`,
      ),
      refusals.ledgerNotInitialized,
    );
    await assertRefusal(
      await h.send(
        support.jwt,
        "GET",
        `/admin/stores/${store.storeId}/ledger/debts?reason=%20`,
      ),
      refusals.invalidInput,
    );
  },
);

dbTest("events page latest first by sequence", async () => {
  for (
    const [sequence, kind] of [[1, "contact.create"], [2, "debt.create"], [
      3,
      "payment.create",
    ]] as const
  ) {
    const operation = crypto.randomUUID();
    await h.database.query(
      "insert into public.sadid_operations(store_id, operation_id, request, result, user_id, installation_id, generation) values ($1, $2, '{}', '{}', $3, gen_random_uuid(), 1)",
      [store.storeId, operation, store.userId],
    );
    await h.database.query(
      "insert into public.sadid_events(store_id, sequence, operation_id, kind, entity_id, user_id, installation_id, generation, command) values ($1, $2, $3, $4, gen_random_uuid(), $5, gen_random_uuid(), 1, '{}')",
      [store.storeId, sequence, operation, kind, store.userId],
    );
  }
  const first = await ledger("events?limit=2");
  assertEqual(first.rows.map((row: { sequence: number }) => row.sequence), [
    3,
    2,
  ]);
  const second = await ledger(`events?limit=2&cursor=${first.nextCursor}`);
  assertEqual(second.rows.map((row: { kind: string }) => row.kind), [
    "contact.create",
  ]);
  assertEqual(second.nextCursor, null);
  const debts = await ledger("events?q=debt");
  assertEqual(debts.rows.map((row: { sequence: number }) => row.sequence), [2]);
});
