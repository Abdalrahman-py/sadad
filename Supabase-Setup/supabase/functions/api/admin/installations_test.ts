import { assert, assertEqual } from "../test_support.ts";
import { adminHarness, assertRefusal, dbTest } from "./test_admin.ts";
import refusals from "../../../../../contract/fixtures/admin/refusals.json" with {
  type: "json",
};

const h = await adminHarness();
const admin = await h.jwtFor("admin");
const support = await h.jwtFor("support");

async function pendingDevice(
  storeId: number,
  label: string,
  createdAt: string,
  generation = 1,
) {
  const id = crypto.randomUUID();
  await h.database.query(
    "insert into sadid_private.installations(id, store_id, generation, public_key, label, staff_name, status, created_at) values ($1, $2, $3, '{}', $4, 'سامي', 'pending', $5)",
    [id, storeId, generation, label, createdAt],
  );
  return id;
}

async function status(id: string): Promise<string> {
  const [row] = await h.database.query<{ status: string }>(
    "select status from sadid_private.installations where id = $1",
    [id],
  );
  return row.status;
}

dbTest(
  "pending devices page newest first and search by label or store name",
  async () => {
    const store = await h.seedStore(5);
    const oldest = await pendingDevice(
      store.storeId,
      "Redmi",
      "2026-10-01T10:00:00Z",
    );
    const middle = await pendingDevice(
      store.storeId,
      "Galaxy",
      "2026-10-02T10:00:00Z",
      2,
    );
    const newest = await pendingDevice(
      store.storeId,
      "Redmi Note",
      "2026-10-03T10:00:00Z",
      3,
    );

    const first = await (await h.send(
      support.jwt,
      "GET",
      "/admin/installations?limit=2",
    )).json();
    assertEqual(
      first.installations.map((i: { installationId: string }) =>
        i.installationId
      ),
      [newest, middle],
    );
    assertEqual(first.installations[0].storeName, store.username);
    const second = await (await h.send(
      support.jwt,
      "GET",
      `/admin/installations?limit=2&cursor=${first.nextCursor}`,
    )).json();
    assertEqual(
      second.installations.map((i: { installationId: string }) =>
        i.installationId
      ),
      [oldest],
    );
    assertEqual(second.nextCursor, null);

    const redmi = await (await h.send(
      support.jwt,
      "GET",
      "/admin/installations?q=redmi",
    )).json();
    assertEqual(redmi.installations.length, 2);
    const byStore = await (await h.send(
      support.jwt,
      "GET",
      `/admin/installations?q=${store.username}`,
    )).json();
    assertEqual(byStore.installations.length, 3);
  },
);

dbTest("approve then revoke an installation", async () => {
  const store = await h.seedStore();
  const device = await pendingDevice(
    store.storeId,
    "Redmi",
    "2026-10-05T10:00:00Z",
  );

  await assertRefusal(
    await h.send(
      support.jwt,
      "POST",
      `/admin/installations/${device}/approve`,
      {
        reason: "دعم",
      },
    ),
    refusals.notAdmin,
  );
  await assertRefusal(
    await h.send(
      admin.jwt,
      "POST",
      `/admin/installations/${device}/approve`,
      {},
    ),
    refusals.invalidInput,
  );

  const approved = await h.send(
    admin.jwt,
    "POST",
    `/admin/installations/${device}/approve`,
    { reason: "هاتف المحل" },
  );
  assertEqual(await approved.json(), {
    ok: true,
    installation: { installationId: device, generation: 1, status: "active" },
  });
  assertEqual(await status(device), "active");
  const [ledger] = await h.database.query(
    "select 1 from public.sadid_ledger_state where store_id = $1",
    [store.storeId],
  );
  assert(ledger, "approval initializes the store's ledger");

  const revoked = await h.send(
    admin.jwt,
    "POST",
    `/admin/installations/${device}/revoke`,
    { reason: "ضاع الهاتف" },
  );
  assertEqual(await revoked.json(), {
    ok: true,
    installation: { installationId: device, status: "revoked" },
  });
  assertEqual(await status(device), "revoked");
});

dbTest("approval within the device cap only", async () => {
  const store = await h.seedStore(1);
  const first = await pendingDevice(store.storeId, "A", "2026-10-06T10:00:00Z");
  const second = await pendingDevice(
    store.storeId,
    "B",
    "2026-10-06T11:00:00Z",
    2,
  );
  assertEqual(
    (await h.send(admin.jwt, "POST", `/admin/installations/${first}/approve`, {
      reason: "الأول",
    })).status,
    200,
  );
  await assertRefusal(
    await h.send(admin.jwt, "POST", `/admin/installations/${second}/approve`, {
      reason: "الثاني",
    }),
    refusals.belowActiveDevices,
  );
});
