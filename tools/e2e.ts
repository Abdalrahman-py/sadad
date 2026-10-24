// Live end-to-end check of a cut-over project (ticket 27). As the admin it creates a fresh store,
// runs smoke-api.ts against it, approves the smoke installation the way the dashboard does, then
// reads the store back through the admin routes and checks the lease key the app pins.
//
//   SADAD_SMOKE_URL=https://<ref>.supabase.co \
//   SADAD_SMOKE_PUBLISHABLE_KEY=sb_publishable_... \
//   SADAD_ADMIN_EMAIL=... SADAD_ADMIN_PASSWORD=... \
//   deno run --allow-env --allow-net --allow-run --allow-read tools/e2e.ts
//
// It writes test data: one store named "E2E <time>" with username e2e-<epoch ms>. Never prints a
// password or token.
import {
  apiBase,
  env,
  fail,
  projectUrl,
  publishableKey,
} from "./api-client.ts";

async function admin(
  jwt: string,
  method: "GET" | "POST",
  path: string,
  body?: unknown,
  // deno-lint-ignore no-explicit-any
): Promise<{ status: number; body: any }> {
  const res = await fetch(`${apiBase()}${path}`, {
    method,
    headers: {
      apikey: publishableKey(),
      Authorization: `Bearer ${jwt}`,
      ...(body === undefined ? {} : { "content-type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, body: await res.json() };
}

const signedIn = await fetch(
  `${projectUrl()}/auth/v1/token?grant_type=password`,
  {
    method: "POST",
    headers: { apikey: publishableKey(), "content-type": "application/json" },
    body: JSON.stringify({
      email: env("SADAD_ADMIN_EMAIL"),
      password: env("SADAD_ADMIN_PASSWORD"),
    }),
  },
);
const session = await signedIn.json();
if (!signedIn.ok) {
  fail("admin sign in", { status: signedIn.status, body: session });
}
const jwt: string = session.access_token;
console.log("admin signed in");

const name = `E2E ${new Date().toISOString()}`;
const username = `e2e-${Date.now()}`;
const created = await admin(jwt, "POST", "/v1/admin/stores", {
  name,
  username,
  subscriptionMode: "permanent",
});
if (created.status !== 201) fail("create store", created);
const { storeId, temporaryPassword } = created.body;
console.log(`created store ${storeId} (${username})`);

const newPassword = btoa(
  String.fromCharCode(...crypto.getRandomValues(new Uint8Array(18))),
);
const smoke = new Deno.Command(Deno.execPath(), {
  args: [
    "run",
    "--allow-env",
    "--allow-net",
    new URL("./smoke-api.ts", import.meta.url).pathname,
  ],
  env: {
    SADAD_SMOKE_USERNAME: username,
    SADAD_SMOKE_PASSWORD: temporaryPassword,
    SADAD_SMOKE_NEW_PASSWORD: newPassword,
  },
  stdout: "piped",
  stderr: "piped",
}).spawn();
const output = smoke.output();

// The smoke run waits on changes?after=0 until its installation is approved.
let finished = false;
output.finally(() => finished = true);
let approved = false;
while (!approved && !finished) {
  await new Promise((resolve) => setTimeout(resolve, 3000));
  const pending = await admin(
    jwt,
    "GET",
    `/v1/admin/installations?q=${encodeURIComponent(name)}`,
  );
  if (pending.status !== 200) fail("pending installations", pending);
  const mine = pending.body.installations.find((
    i: { storeName: string; label: string },
  ) => i.storeName === name && i.label === "smoke-api");
  if (!mine) continue;
  const decided = await admin(
    jwt,
    "POST",
    `/v1/admin/installations/${mine.installationId}/approve`,
    { reason: "ticket 27 e2e" },
  );
  if (decided.status !== 200) fail("approve", decided);
  approved = true;
  console.log(`approved installation ${mine.installationId}`);
}

const { code, stdout, stderr } = await output;
const log = new TextDecoder().decode(stdout);
console.log(log.replace(/^/gm, "  smoke | ").trimEnd());
if (code !== 0) fail("smoke", new TextDecoder().decode(stderr));

const gradle = await Deno.readTextFile(
  new URL("../app/build.gradle.kts", import.meta.url),
);
const pinned = ["X", "Y"].map((axis) =>
  gradle.match(
    new RegExp(`"SADAD_LEASE_KEY_${axis}"\\)\\s*\\.getOrElse\\("([^"]+)"\\)`),
  )?.[1]
);
const leased = log.match(/lease public key: (\{.*\})/)?.[1];
if (!leased || leased !== JSON.stringify({ x: pinned[0], y: pinned[1] })) {
  fail("lease key matches app/build.gradle.kts", { leased, pinned });
}
console.log("lease key matches the key the app pins");

const debts = await admin(
  jwt,
  "GET",
  `/v1/admin/stores/${storeId}/ledger/debts?reason=${
    encodeURIComponent("ticket 27 e2e")
  }`,
);
if (debts.status !== 200) fail("admin ledger", debts);
const debt = debts.body.rows[0];
if (
  debts.body.rows.length !== 1 || debt.amountCents !== 1000 ||
  debt.paidCents !== 400 || debt.remainingCents !== 600
) {
  fail(
    "admin sees the smoke debt (1000 cents, 400 paid, 600 left)",
    debts.body,
  );
}
console.log("admin ledger shows the debt: 1000 cents, 400 paid, 600 left");
console.log(`e2e green: store ${storeId} (${username})`);
