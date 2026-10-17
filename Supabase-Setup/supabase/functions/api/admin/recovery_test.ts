import { assert, assertEqual } from "../test_support.ts";
import { adminHarness, assertRefusal, dbTest } from "./test_admin.ts";
import refusals from "../../../../../contract/fixtures/admin/refusals.json" with {
  type: "json",
};

const h = await adminHarness();
const admin = await h.jwtFor("admin");

async function device(storeId: number, generation: number, status: string) {
  const id = crypto.randomUUID();
  await h.database.query(
    "insert into sadid_private.installations(id, store_id, generation, public_key, status) values ($1, $2, $3, '{}', $4)",
    [id, storeId, generation, status],
  );
  return id;
}

function command(oldId: string) {
  return {
    schemaVersion: 1,
    operationId: crypto.randomUUID(),
    entityId: crypto.randomUUID(),
    type: "contact.create",
    originInstallationId: oldId,
    originGeneration: 1,
    createdAt: "2026-10-10T10:00:00Z",
    dependsOn: [],
    payload: { name: "سامي" },
  };
}

dbTest(
  "approve a recovery permit for a replacement device; the audit trail records it",
  async () => {
    const store = await h.seedStore();
    await h.database.query(
      "insert into public.sadid_ledger_state(store_id) values ($1)",
      [store.storeId],
    );
    const oldId = await device(store.storeId, 1, "revoked");
    const newId = await device(store.storeId, 2, "active");

    const res = await h.send(admin.jwt, "POST", "/admin/recovery", {
      oldInstallationId: oldId,
      newInstallationId: newId,
      commands: [command(oldId), command(oldId)],
      reason: "هاتف بديل",
    });
    assertEqual(res.status, 200);
    const { permit } = await res.json();
    assert(
      /^[0-9a-f]{64}$/.test(permit.manifestHash),
      "a sha-256 manifest hash",
    );

    const [row] = await h.database.query<{ new_installation_id: string }>(
      "select new_installation_id::text from sadid_private.recovery_permits where id = $1",
      [permit.permitId],
    );
    assertEqual(row.new_installation_id, newId);

    const detail = (await (await h.send(
      admin.jwt,
      "GET",
      `/admin/stores/${store.storeId}`,
    )).json()).detail;
    assertEqual(detail.recoveryPermits[0].permitId, permit.permitId);
  },
);

dbTest("recovery between unrelated devices is refused", async () => {
  const store = await h.seedStore();
  const other = await h.seedStore();
  const oldId = await device(store.storeId, 1, "revoked");
  const newId = await device(other.storeId, 2, "active");
  await assertRefusal(
    await h.send(admin.jwt, "POST", "/admin/recovery", {
      oldInstallationId: oldId,
      newInstallationId: newId,
      commands: [command(oldId)],
      reason: "خطأ",
    }),
    refusals.invalidTransfer,
  );
});
