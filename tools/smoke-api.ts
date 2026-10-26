// Live smoke test of /v1/mobile/* against a Supabase project (ticket 27 runs it; CI does not).
//
//   SADAD_SMOKE_URL=https://<ref>.supabase.co \
//   SADAD_SMOKE_PUBLISHABLE_KEY=sb_publishable_... \
//   SADAD_SMOKE_USERNAME=<store username> SADAD_SMOKE_PASSWORD=... \
//   SADAD_SMOKE_NEW_PASSWORD=... \
//   deno run --allow-env --allow-net tools/smoke-api.ts
//
// It signs in as the store (changing a temporary password first when force_password_change is
// set), enrolls a fresh P-256 installation, waits until an admin approves it in the dashboard
// (SADAD_SMOKE_APPROVAL_SECONDS, default 600), takes a lease, pushes one batch (contact, debt,
// payment) and reads it back through changes. It writes test data.
import {
  authenticate,
  type Device,
  enroll,
  fail,
  newDevice,
  signed,
  waitForApproval,
} from "./api-client.ts";

function batch(device: Device, epoch: string, generation: number) {
  const contactOp = crypto.randomUUID();
  const contactId = crypto.randomUUID();
  const debtOp = crypto.randomUUID();
  const debtId = crypto.randomUUID();
  const base = {
    schemaVersion: 1,
    originInstallationId: device.installationId,
    originGeneration: generation,
    expectedEpoch: epoch,
    createdAt: new Date().toISOString(),
  };
  return [
    {
      ...base,
      operationId: contactOp,
      entityId: contactId,
      type: "contact.create",
      dependsOn: [],
      // apply_one requires kind on every contact; this smoke store is all customers.
      payload: {
        name: `اختبار دخان ${new Date().toISOString()}`,
        kind: "customer",
      },
    },
    {
      ...base,
      operationId: debtOp,
      entityId: debtId,
      type: "debt.create",
      dependsOn: [contactOp],
      payload: { contactId, direction: "receivable", amountCents: 1000 },
    },
    {
      ...base,
      operationId: crypto.randomUUID(),
      entityId: crypto.randomUUID(),
      type: "payment.create",
      dependsOn: [debtOp],
      payload: { debtId, amountCents: 400, method: "cash" },
    },
  ];
}

const jwt = await authenticate();

const device = await newDevice();
const installation = await enroll(jwt, device, "smoke-api");
console.log(
  `enrolled ${device.installationId} (generation ${installation.generation}, ${installation.status}); approve it in the dashboard`,
);

const { epoch, cursor } = await waitForApproval(jwt, device);
console.log(`approved; epoch ${epoch}, cursor ${cursor}`);

const lease = await signed(jwt, device, "POST", "/v1/mobile/lease");
if (lease.status !== 200) fail("lease", lease);
const { x, y } = lease.body.lease.publicKey;
console.log("lease issued");
console.log(`lease public key: ${JSON.stringify({ x, y })}`);

const operations = batch(device, epoch, installation.generation);
const pushed = await signed(jwt, device, "POST", "/v1/mobile/operations", {
  operations,
});
if (pushed.status !== 200) fail("operations", pushed);
const statuses = pushed.body.receipts.map((r: { status: string }) => r.status);
if (statuses.join() !== "accepted,accepted,accepted") {
  fail("receipts", pushed.body.receipts);
}
console.log(`pushed 3 operations: ${statuses.join(", ")}`);

const again = await signed(jwt, device, "POST", "/v1/mobile/operations", {
  operations,
});
if (
  again.status !== 200 ||
  JSON.stringify(again.body.receipts) !== JSON.stringify(pushed.body.receipts)
) {
  fail("resend returns the same receipts", again);
}
console.log("resend returned the same receipts");

const changes = await signed(
  jwt,
  device,
  "GET",
  `/v1/mobile/changes?after=${cursor}`,
);
if (changes.status !== 200) fail("changes", changes);
const seen = changes.body.changes.events.map((e: { operationId: string }) =>
  e.operationId
);
if (seen.join() !== operations.map((o) => o.operationId).join()) {
  fail("changes events", changes.body.changes);
}
console.log(
  `changes after ${cursor}: ${seen.length} events, nextCursor ${changes.body.changes.nextCursor}`,
);
console.log("OK");
