import { assertEqual } from "../test_support.ts";
import { adminHarness, dbTest } from "./test_admin.ts";

const h = await adminHarness();
const admin = await h.jwtFor("admin");
const support = await h.jwtFor("support");

dbTest(
  "admin actions page newest first, by store and by search; ledger reads are their own source",
  async () => {
    const store = await h.seedStore();
    const other = await h.seedStore();
    for (const reason of ["أول", "ثاني", "ثالث"]) {
      const res = await h.send(
        admin.jwt,
        "POST",
        `/admin/stores/${store.storeId}/actions/end-sessions`,
        { reason },
      );
      assertEqual(res.status, 200);
    }
    await h.send(admin.jwt, "PATCH", `/admin/stores/${other.storeId}`, {
      verified: true,
    });

    const first = await (await h.send(
      support.jwt,
      "GET",
      `/admin/audit?storeId=${store.storeId}&limit=2`,
    )).json();
    assertEqual(
      first.events.map((e: { reason: string }) => e.reason),
      ["ثالث", "ثاني"],
    );
    assertEqual(first.events[0].kind, "account.end-sessions");
    const second = await (await h.send(
      support.jwt,
      "GET",
      `/admin/audit?storeId=${store.storeId}&limit=2&cursor=${first.nextCursor}`,
    )).json();
    assertEqual(second.events.map((e: { reason: string }) => e.reason), [
      "أول",
    ]);
    assertEqual(second.nextCursor, null);

    const updates = await (await h.send(
      support.jwt,
      "GET",
      "/admin/audit?q=store.updated",
    )).json();
    assertEqual(
      updates.events.map((e: { storeId: number; details: unknown }) => [
        e.storeId,
        e.details,
      ]),
      [[other.storeId, { verified: true }]],
    );

    await h.database.query(
      "insert into public.sadid_ledger_state(store_id) values ($1)",
      [store.storeId],
    );
    await h.send(
      support.jwt,
      "GET",
      `/admin/stores/${store.storeId}/ledger/contacts?reason=%D8%AA%D8%AF%D9%82%D9%8A%D9%82`,
    );
    const reads = await (await h.send(
      admin.jwt,
      "GET",
      `/admin/audit?source=access&storeId=${store.storeId}`,
    )).json();
    assertEqual(
      reads.events.map((e: { reason: string; recordCounts: unknown }) => [
        e.reason,
        e.recordCounts,
      ]),
      [["تدقيق", { contacts: 0 }]],
    );
  },
);
