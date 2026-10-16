// Live smoke test of /v1/mobile/* against a Supabase project (ticket 27 runs it; CI does not).
//
//   SADAD_SMOKE_URL=https://<ref>.supabase.co \
//   SADAD_SMOKE_PUBLISHABLE_KEY=sb_publishable_... \
//   SADAD_SMOKE_USERNAME=<store username> SADAD_SMOKE_PASSWORD=... \
//   deno run --allow-env --allow-net tools/smoke-api.ts
//
// It signs in as the store, enrolls a fresh P-256 installation, waits until an admin approves
// it in the dashboard (SADAD_SMOKE_APPROVAL_SECONDS, default 600), takes a lease, pushes one
// batch (contact, debt, payment) and reads it back through changes. It writes test data.
import {
  proofText,
  sha256,
} from "../Supabase-Setup/supabase/functions/api/proof.ts";

function env(name: string): string {
  const value = Deno.env.get(name);
  if (!value) {
    console.error(`Set ${name}.`);
    Deno.exit(2);
  }
  return value;
}

const projectUrl = env("SADAD_SMOKE_URL").replace(/\/+$/, "");
const publishableKey = env("SADAD_SMOKE_PUBLISHABLE_KEY");
const username = env("SADAD_SMOKE_USERNAME");
const password = env("SADAD_SMOKE_PASSWORD");
const approvalSeconds = Number(
  Deno.env.get("SADAD_SMOKE_APPROVAL_SECONDS") ?? "600",
);
const apiBase = `${projectUrl}/functions/v1/api`;
const encoder = new TextEncoder();

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function fail(step: string, detail: unknown): never {
  console.error(`FAIL ${step}: ${JSON.stringify(detail)}`);
  Deno.exit(1);
}

async function signIn(): Promise<string> {
  const res = await fetch(`${projectUrl}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: { apikey: publishableKey, "content-type": "application/json" },
    body: JSON.stringify({
      email: `${username}@stores.sadad.invalid`,
      password,
    }),
  });
  const body = await res.json();
  if (!res.ok || typeof body.access_token !== "string") {
    fail("sign in", { status: res.status, body });
  }
  return body.access_token;
}

interface Device {
  installationId: string;
  privateKey: CryptoKey;
  publicKey: { kty: string; crv: string; x: string; y: string };
}

async function newDevice(): Promise<Device> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const jwk = await crypto.subtle.exportKey("jwk", pair.publicKey);
  return {
    installationId: crypto.randomUUID(),
    privateKey: pair.privateKey,
    publicKey: { kty: "EC", crv: "P-256", x: jwk.x!, y: jwk.y! },
  };
}

async function signed(
  jwt: string,
  device: Device,
  method: "GET" | "POST",
  route: string,
  body?: unknown,
  // deno-lint-ignore no-explicit-any
): Promise<{ status: number; body: any }> {
  const raw = body === undefined ? "" : JSON.stringify(body);
  const time = String(Date.now());
  const nonce = crypto.randomUUID();
  const text = proofText(
    method,
    route,
    await sha256(raw),
    time,
    nonce,
    device.installationId,
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      device.privateKey,
      encoder.encode(text),
    ),
  );
  const res = await fetch(`${apiBase}${route}`, {
    method,
    headers: {
      apikey: publishableKey,
      Authorization: `Bearer ${jwt}`,
      "content-type": "application/json",
      "x-sadad-installation": device.installationId,
      "x-sadad-time": time,
      "x-sadad-nonce": nonce,
      "x-sadad-signature": base64Url(signature),
    },
    ...(method === "POST" ? { body: raw } : {}),
  });
  return { status: res.status, body: await res.json() };
}

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
      payload: { name: `اختبار دخان ${new Date().toISOString()}` },
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

const jwt = await signIn();
console.log("signed in");

const device = await newDevice();
const enrolled = await signed(jwt, device, "POST", "/v1/mobile/enroll", {
  publicKey: device.publicKey,
  label: "smoke-api",
});
if (enrolled.status !== 200) fail("enroll", enrolled);
const generation: number = enrolled.body.installation.generation;
console.log(
  `enrolled ${device.installationId} (generation ${generation}, ${enrolled.body.installation.status}); approve it in the dashboard`,
);

let first = await signed(jwt, device, "GET", "/v1/mobile/changes?after=0");
const deadline = Date.now() + approvalSeconds * 1000;
while (
  first.status === 401 && first.body.code === "installation_or_session_revoked"
) {
  if (Date.now() > deadline) fail("waiting for approval", first);
  await new Promise((resolve) => setTimeout(resolve, 5000));
  first = await signed(jwt, device, "GET", "/v1/mobile/changes?after=0");
}
if (first.status !== 200) fail("changes after=0", first);
const { epoch, cursor } = first.body.changes;
console.log(`approved; epoch ${epoch}, cursor ${cursor}`);

const lease = await signed(jwt, device, "POST", "/v1/mobile/lease");
if (lease.status !== 200) fail("lease", lease);
console.log("lease issued");

const operations = batch(device, epoch, generation);
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
