import { assert, assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  batchSize,
  deletedIds,
  ledgerRowToWire,
  mergeDeltaIntoSnapshot,
  type WireSnapshot,
} from "./ledger-sync.ts";

function ledger(): WireSnapshot {
  return {
    contacts: [
      { id: 1, name: "أحمد", phone: "970599111111", category: "", note: "", whatsappOptIn: true, creditLimit: 0, createdAt: 100, createdBy: "" },
      { id: 2, name: "سالم", phone: "970599222222", category: "", note: "", whatsappOptIn: false, creditLimit: 0, createdAt: 200, createdBy: "" },
    ],
    debts: [
      { id: 10, contactId: 1, direction: "receivable", amount: 50, note: "", dueDate: "", createdAt: 300, createdBy: "" },
      { id: 11, contactId: 1, direction: "receivable", amount: 20, note: "", dueDate: "", createdAt: 400, createdBy: "" },
      { id: 12, contactId: 2, direction: "payable", amount: 70, note: "", dueDate: "", createdAt: 500, createdBy: "" },
    ],
    payments: [
      { id: 100, debtId: 10, amount: 10, method: "cash", note: "", createdAt: 600, createdBy: "" },
      { id: 101, debtId: 12, amount: 5, method: "bank", note: "", createdAt: 700, createdBy: "" },
    ],
  };
}

Deno.test("merge keeps rows the batch does not mention", () => {
  const merged = mergeDeltaIntoSnapshot(ledger(), { contacts: [], debts: [], payments: [] }, { contacts: [], debts: [], payments: [] });
  assertEquals(merged.contacts.length, 2);
  assertEquals(merged.debts.length, 3);
  assertEquals(merged.payments.length, 2);
});

Deno.test("merge upserts a new contact without disturbing the rest", () => {
  const merged = mergeDeltaIntoSnapshot(
    ledger(),
    { contacts: [{ id: 3, name: "هدى", phone: "970599333333", createdAt: 800 }], debts: [], payments: [] },
    { contacts: [], debts: [], payments: [] },
  );
  assertEquals(merged.contacts.length, 3);
  assertEquals(merged.contacts.find((c) => c.id === 3)?.name, "هدى");
  assertEquals(merged.debts.length, 3, "unrelated debts stay");
});

Deno.test("merge adds a debt and a payment for an existing contact", () => {
  const merged = mergeDeltaIntoSnapshot(
    ledger(),
    {
      contacts: [],
      debts: [{ id: 13, contactId: 2, direction: "receivable", amount: 35, createdAt: 900 }],
      payments: [{ id: 102, debtId: 13, amount: 5, method: "cash", createdAt: 950 }],
    },
    { contacts: [], debts: [], payments: [] },
  );
  assertEquals(merged.debts.length, 4);
  assertEquals(merged.payments.length, 3);
});

Deno.test("deleting a contact cascades to its debts and their payments", () => {
  const merged = mergeDeltaIntoSnapshot(ledger(), { contacts: [], debts: [], payments: [] }, { contacts: [1], debts: [], payments: [] });
  assertEquals(merged.contacts.map((c) => c.id), [2], "the contact is gone");
  assertEquals(merged.debts.map((d) => d.id), [12], "both of its debts are gone");
  assertEquals(merged.payments.map((p) => p.id), [101], "only the surviving debt's payment remains");
});

Deno.test("deleting a debt removes its payments but keeps the contact", () => {
  const merged = mergeDeltaIntoSnapshot(ledger(), { contacts: [], debts: [], payments: [] }, { contacts: [], debts: [10], payments: [] });
  assertEquals(merged.contacts.length, 2);
  assertEquals(merged.debts.map((d) => d.id), [11, 12]);
  assertEquals(merged.payments.map((p) => p.id), [101]);
});

Deno.test("an explicit deletion wins over an upsert of the same id in one batch", () => {
  const merged = mergeDeltaIntoSnapshot(
    ledger(),
    { contacts: [{ id: 2, name: "سالم المعدّل", phone: "970599222222", createdAt: 200 }], debts: [], payments: [] },
    { contacts: [2], debts: [], payments: [] },
  );
  assertEquals(merged.contacts.map((c) => c.id), [1]);
  assertEquals(merged.debts.map((d) => d.id), [10, 11], "the deleted contact's own debt 12 goes with it");
  assertEquals(merged.payments.map((p) => p.id), [100], "debt 12's payment 101 goes with it");
});

Deno.test("an update to an existing row replaces it in place", () => {
  const merged = mergeDeltaIntoSnapshot(
    ledger(),
    { contacts: [{ id: 1, name: "أحمد الجديد", phone: "970599111111", note: "محدّث", createdAt: 100 }], debts: [], payments: [] },
    { contacts: [], debts: [], payments: [] },
  );
  assertEquals(merged.contacts.find((c) => c.id === 1)?.name, "أحمد الجديد");
  assertEquals(merged.contacts.length, 2);
});

Deno.test("batch size counts upserts and deletions across tables", () => {
  assertEquals(batchSize({ contacts: [{}], debts: [{}], payments: [], deleted: { contacts: [1], payments: [2, 3] } }), 5);
  assertEquals(batchSize({ contacts: [], debts: [], payments: [] }), 0);
});

Deno.test("malformed batches are rejected instead of silently applied", () => {
  assertThrows(() => batchSize({ contacts: "nope" as unknown as [] }), Error, "invalid_rows");
  assertThrows(() => deletedIds({ contacts: [0] }, "contacts"), Error, "invalid_deleted_id");
  assertThrows(() => deletedIds({ contacts: "nope" }, "contacts"), Error, "invalid_deleted:contacts");
  assertThrows(() => deletedIds("nope", "contacts"), Error, "invalid_deleted");
  assertThrows(() => deletedIds([1, 2], "contacts"), Error, "invalid_deleted");
});

Deno.test("a missing deleted list is treated as empty", () => {
  assertEquals(deletedIds(undefined, "contacts"), []);
  assertEquals(batchSize({ contacts: [{}], debts: [], payments: [] }), 1);
});

Deno.test("paged rows use the camelCase shape the app stages into SQLite", () => {
  const wire = ledgerRowToWire({
    id: 7,
    store_id: 3,
    name: "ليلى",
    phone: "970599777777",
    credit_limit_cents: 12345,
    whatsapp_opt_in: true,
    created_at: 555,
    created_by: "orca",
    server_received_at: 999,
  });
  assertEquals(wire, {
    id: 7,
    name: "ليلى",
    phone: "970599777777",
    creditLimit: 123.45,
    whatsappOptIn: true,
    createdAt: 555,
    createdBy: "orca",
  });
  assert(!("store_id" in wire), "store_id must not be sent to the device");
  assert(!("server_received_at" in wire), "server_received_at must not be sent to the device");
});

Deno.test("a payment row maps debt_id, amount cents and method", () => {
  const wire = ledgerRowToWire({ id: 5, store_id: 1, debt_id: 10, amount_cents: 2550, method: "wallet", note: "جزئي", created_at: 888, server_received_at: 0 });
  assertEquals(wire, { id: 5, debtId: 10, amount: 25.5, method: "wallet", note: "جزئي", createdAt: 888 });
});
