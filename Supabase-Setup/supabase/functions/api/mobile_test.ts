import { createApp } from "./app.ts";
import { errorMessages } from "./errors.ts";
import { type LeaseSigner } from "./mobile.ts";
import { TestDatabase } from "./test_db.ts";
import {
  assert,
  assertEqual,
  base64Url,
  createTestKeyPair,
  makeSignedJwt,
  signProof,
} from "./test_support.ts";
import changesFixture from "../../../../contract/fixtures/mobile/changes.json" with {
  type: "json",
};
import enrollFixture from "../../../../contract/fixtures/mobile/enroll.json" with {
  type: "json",
};
import operationsFixture from "../../../../contract/fixtures/mobile/operations.json" with {
  type: "json",
};
import refusals from "../../../../contract/fixtures/mobile/refusals.json" with {
  type: "json",
};

const database = await TestDatabase.create();
const auth = await createTestKeyPair();
const lease = await createLeaseSigner();
const app = createApp({
  verifyClaims: auth.verifyClaims,
  mobile: {
    serviceDb: database.serviceClient(),
    userDb: (jwt, capability) => database.userClient(jwt, capability),
    lease,
  },
});

// pglite keeps its worker and timers for the whole file.
const dbTest = (name: string, fn: () => Promise<void>) =>
  Deno.test({ name, fn, sanitizeOps: false, sanitizeResources: false });

async function createLeaseSigner(): Promise<LeaseSigner> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const { kty, crv, x, y } = await crypto.subtle.exportKey(
    "jwk",
    pair.publicKey,
  );
  return {
    projectRef: "testref",
    signingKey: pair.privateKey,
    publicKey: { kty, crv, x, y },
  };
}

interface Device {
  installationId: string;
  privateKey: CryptoKey;
  publicKey: JsonWebKey;
}

async function newDevice(): Promise<Device> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const { kty, crv, x, y } = await crypto.subtle.exportKey(
    "jwk",
    pair.publicKey,
  );
  return {
    installationId: crypto.randomUUID(),
    privateKey: pair.privateKey,
    publicKey: { kty, crv, x, y },
  };
}

let storeCount = 0;

async function newStore(allowedDevices = 1) {
  storeCount += 1;
  const store = await database.createStore(
    `store-${storeCount}`,
    allowedDevices,
  );
  const jwt = await makeSignedJwt(auth.pair.privateKey, auth.kid, {
    sub: store.userId,
    session_id: store.sessionId,
    app_metadata: { role: "store" },
  });
  return { ...store, jwt };
}

interface SendOptions {
  body?: unknown;
  headers?: Record<string, string>;
  sign?: boolean;
  nonce?: string;
  signWith?: CryptoKey;
}

async function send(
  jwt: string,
  device: Device,
  method: "GET" | "POST",
  route: string,
  options: SendOptions = {},
): Promise<Response> {
  const body = options.body === undefined ? "" : JSON.stringify(options.body);
  const proof = options.sign === false ? {} : await signProof(
    options.signWith ?? device.privateKey,
    {
      method,
      route,
      body,
      installationId: device.installationId,
      nonce: options.nonce,
    },
  );
  return await app.request(`https://test.invalid/functions/v1/api${route}`, {
    method,
    headers: {
      Authorization: `Bearer ${jwt}`,
      "content-type": "application/json",
      ...proof,
      ...options.headers,
    },
    ...(method === "POST" ? { body } : {}),
  });
}

function enrollBody(device: Device) {
  return { ...enrollFixture.request, publicKey: device.publicKey };
}

/** A store with one enrolled, admin-approved installation. */
async function activeStore(allowedDevices = 1) {
  const store = await newStore(allowedDevices);
  const device = await newDevice();
  const res = await send(store.jwt, device, "POST", "/v1/mobile/enroll", {
    body: enrollBody(device),
  });
  assertEqual(res.status, 200);
  await database.asAdmin("sadid_approve_installation", [
    device.installationId,
    "اعتماد في الاختبار",
  ]);
  const [{ epoch }] = await database.query<{ epoch: string }>(
    "select epoch::text from public.sadid_ledger_state where store_id = $1",
    [store.storeId],
  );
  return { store, device, epoch };
}

function fill<T>(value: T, vars: Record<string, string>): T {
  let text = JSON.stringify(value);
  for (const [name, replacement] of Object.entries(vars)) {
    text = text.replaceAll(`{${name}}`, replacement);
  }
  return JSON.parse(text);
}

async function assertRefusal(
  res: Response,
  expected: { status: number; code: string },
) {
  const body = await res.json();
  assertEqual({ status: res.status, body }, {
    status: expected.status,
    body: {
      ok: false,
      code: expected.code,
      message: errorMessages[expected.code as keyof typeof errorMessages],
    },
  });
}

async function operationCount(storeId: number): Promise<number> {
  const [{ count }] = await database.query<{ count: number }>(
    "select count(*)::int as count from public.sadid_operations where store_id = $1",
    [storeId],
  );
  return count;
}

dbTest(
  "enroll with a valid proof registers a pending installation; the same proof again is refused",
  async () => {
    const store = await newStore();
    const device = await newDevice();
    const nonce = crypto.randomUUID();

    const res = await send(store.jwt, device, "POST", "/v1/mobile/enroll", {
      body: enrollBody(device),
      nonce,
    });
    assertEqual(res.status, 200);
    assertEqual(
      await res.json(),
      fill(enrollFixture.response, { installationId: device.installationId }),
    );

    const replay = await send(store.jwt, device, "POST", "/v1/mobile/enroll", {
      body: enrollBody(device),
      nonce,
    });
    await assertRefusal(replay, refusals.enrollReplayed);
  },
);

dbTest(
  "enroll proven by a key other than the one sent is refused",
  async () => {
    const store = await newStore();
    const device = await newDevice();
    const other = await newDevice();
    const res = await send(store.jwt, device, "POST", "/v1/mobile/enroll", {
      body: enrollBody(device),
      signWith: other.privateKey,
    });
    await assertRefusal(res, refusals.wrongSignature);
  },
);

dbTest(
  "enroll beyond allowed_devices returns the device-cap code",
  async () => {
    const { store } = await activeStore(1);
    const second = await newDevice();
    const res = await send(store.jwt, second, "POST", "/v1/mobile/enroll", {
      body: enrollBody(second),
    });
    await assertRefusal(res, refusals.enrollOverDeviceCap);
  },
);

dbTest(
  "a batch of 3 operations returns 3 receipts; resending it returns the same receipts and writes no rows",
  async () => {
    const { store, device, epoch } = await activeStore();
    const body = fill(operationsFixture.request, {
      installationId: device.installationId,
      epoch,
    });

    const first = await send(
      store.jwt,
      device,
      "POST",
      "/v1/mobile/operations",
      {
        body,
      },
    );
    assertEqual(first.status, 200);
    const firstBody = await first.json();
    assertEqual(firstBody.ok, true);
    assertEqual(
      firstBody.receipts.map((r: Record<string, unknown>) => ({
        operationId: r.operationId,
        entityId: r.entityId,
        status: r.status,
        code: r.code,
        sequence: r.sequence,
      })),
      operationsFixture.receipts,
    );
    for (const receipt of firstBody.receipts) {
      assertEqual(receipt.epoch, epoch);
    }
    assertEqual(await operationCount(store.storeId), 3);

    const again = await send(
      store.jwt,
      device,
      "POST",
      "/v1/mobile/operations",
      {
        body,
      },
    );
    assertEqual(again.status, 200);
    assertEqual(await again.json(), firstBody);
    assertEqual(await operationCount(store.storeId), 3);
  },
);

dbTest(
  "a reused operationId with other content refuses the whole batch",
  async () => {
    const { store, device, epoch } = await activeStore();
    const body = fill(operationsFixture.request, {
      installationId: device.installationId,
      epoch,
    });
    const first = await send(
      store.jwt,
      device,
      "POST",
      "/v1/mobile/operations",
      {
        body: { operations: body.operations.slice(0, 1) },
      },
    );
    assertEqual(first.status, 200);

    const changed = structuredClone(body.operations[0]);
    changed.payload.name = "اسم آخر";
    const res = await send(store.jwt, device, "POST", "/v1/mobile/operations", {
      body: { operations: [changed, body.operations[1]] },
    });
    await assertRefusal(res, refusals.operationIdReused);
    assertEqual(await operationCount(store.storeId), 1);
  },
);

dbTest("a batch of 101 operations is refused with 400", async () => {
  const { store, device, epoch } = await activeStore();
  const template = fill(operationsFixture.request.operations[0], {
    installationId: device.installationId,
    epoch,
  });
  const operations = Array.from({ length: 101 }, () => ({
    ...template,
    operationId: crypto.randomUUID(),
    entityId: crypto.randomUUID(),
    payload: { name: "شخص" },
  }));
  const res = await send(store.jwt, device, "POST", "/v1/mobile/operations", {
    body: { operations },
  });
  await assertRefusal(res, refusals.batchOver100);
  assertEqual(await operationCount(store.storeId), 0);
});

dbTest(
  "changes?after=N returns only later events and the next cursor",
  async () => {
    const { store, device, epoch } = await activeStore();
    const body = fill(operationsFixture.request, {
      installationId: device.installationId,
      epoch,
    });
    const pushed = await send(
      store.jwt,
      device,
      "POST",
      "/v1/mobile/operations",
      { body },
    );
    assertEqual(pushed.status, 200);

    const res = await send(store.jwt, device, "GET", changesFixture.route);
    assertEqual(res.status, 200);
    const { ok, changes } = await res.json();
    assertEqual(ok, true);
    assertEqual(
      {
        cursor: changes.cursor,
        nextCursor: changes.nextCursor,
        hasMore: changes.hasMore,
        currency: changes.currency,
        events: changes.events.map((e: Record<string, unknown>) => ({
          sequence: e.sequence,
          operationId: e.operationId,
          kind: e.kind,
        })),
      },
      changesFixture.response,
    );
    assertEqual(changes.epoch, epoch);
    assertEqual(changes.storeId, store.storeId);
    assertEqual(changes.events[0].command, body.operations[1]);
    assertEqual(changes.events[0].result.status, "accepted");

    const paged = await send(
      store.jwt,
      device,
      "GET",
      changesFixture.paged.route,
    );
    const page = (await paged.json()).changes;
    assertEqual(
      { nextCursor: page.nextCursor, hasMore: page.hasMore },
      {
        nextCursor: changesFixture.paged.nextCursor,
        hasMore: changesFixture.paged.hasMore,
      },
    );
    assertEqual(page.events.length, 2);
  },
);

dbTest(
  "the changes route passes installations through",
  async () => {
    const { store, device } = await activeStore(2);
    const second = await newDevice();
    const enrolled = await send(
      store.jwt,
      second,
      "POST",
      "/v1/mobile/enroll",
      {
        body: {
          ...enrollBody(second),
          label: "جهاز الكاشير",
          staffName: "سامر",
        },
      },
    );
    assertEqual(enrolled.status, 200);
    await database.asAdmin("sadid_approve_installation", [
      second.installationId,
      "اعتماد في الاختبار",
    ]);
    await database.asAdmin("sadid_revoke_installation", [
      second.installationId,
      "خُرج من المتجر",
    ]);

    const res = await send(store.jwt, device, "GET", changesFixture.route);
    assertEqual(res.status, 200);
    const { ok, changes } = await res.json();
    assertEqual(ok, true);
    const byId = new Map(
      changes.installations.map((i: Record<string, string>) => [i.id, i]),
    );
    assertEqual(byId.size, 2);
    assertEqual(byId.get(device.installationId), {
      id: device.installationId,
      label: "Redmi M2101K7BI",
      staffName: "سامي",
    });
    assertEqual(byId.get(second.installationId), {
      id: second.installationId,
      label: "جهاز الكاشير",
      staffName: "سامر",
    });
  },
);

dbTest(
  "changes signed for one cursor cannot be replayed for another",
  async () => {
    const { store, device } = await activeStore();
    const proof = await signProof(device.privateKey, {
      method: "GET",
      route: "/v1/mobile/changes?after=5",
      body: "",
      installationId: device.installationId,
    });
    const res = await app.request(
      "https://test.invalid/v1/mobile/changes?after=0",
      { headers: { Authorization: `Bearer ${store.jwt}`, ...proof } },
    );
    await assertRefusal(res, refusals.wrongSignature);
  },
);

dbTest(
  "an unsigned or wrongly signed mutation is refused with 401 and writes nothing",
  async () => {
    const { store, device, epoch } = await activeStore();
    const body = fill(operationsFixture.request, {
      installationId: device.installationId,
      epoch,
    });

    const unsigned = await send(
      store.jwt,
      device,
      "POST",
      "/v1/mobile/operations",
      { body, sign: false },
    );
    await assertRefusal(unsigned, refusals.unsigned);

    const stranger = await newDevice();
    const wrong = await send(
      store.jwt,
      device,
      "POST",
      "/v1/mobile/operations",
      { body, signWith: stranger.privateKey },
    );
    await assertRefusal(wrong, refusals.wrongSignature);

    // Signed for a different body than the one sent.
    const proof = await signProof(device.privateKey, {
      method: "POST",
      route: "/v1/mobile/operations",
      body: JSON.stringify({ operations: body.operations.slice(0, 1) }),
      installationId: device.installationId,
    });
    const swapped = await app.request(
      "https://test.invalid/v1/mobile/operations",
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${store.jwt}`,
          "content-type": "application/json",
          ...proof,
        },
        body: JSON.stringify(body),
      },
    );
    await assertRefusal(swapped, refusals.wrongSignature);
    assertEqual(await operationCount(store.storeId), 0);
  },
);

dbTest("a pending installation cannot push operations", async () => {
  const store = await newStore();
  const device = await newDevice();
  const enrolled = await send(store.jwt, device, "POST", "/v1/mobile/enroll", {
    body: enrollBody(device),
  });
  assertEqual(enrolled.status, 200);
  const res = await send(store.jwt, device, "POST", "/v1/mobile/operations", {
    body: { operations: [{ schemaVersion: 1 }] },
  });
  await assertRefusal(res, refusals.installationNotActive);
});

dbTest("an admin token cannot use the mobile routes", async () => {
  const device = await newDevice();
  const jwt = await makeSignedJwt(auth.pair.privateKey, auth.kid, {
    sub: crypto.randomUUID(),
    session_id: crypto.randomUUID(),
    app_metadata: { role: "admin" },
  });
  const res = await send(jwt, device, "POST", "/v1/mobile/lease");
  await assertRefusal(res, refusals.notAStore);
});

dbTest(
  "lease returns a server-signed seven-day payload for this installation",
  async () => {
    const { store, device } = await activeStore();
    const before = Date.now();
    const res = await send(store.jwt, device, "POST", "/v1/mobile/lease");
    assertEqual(res.status, 200);
    const { ok, lease: issued } = await res.json();
    assertEqual(ok, true);
    assertEqual(issued.publicKey, lease.publicKey);

    const key = await crypto.subtle.importKey(
      "jwk",
      issued.publicKey,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    const payloadBytes = Uint8Array.from(
      atob(issued.payload.replace(/-/g, "+").replace(/_/g, "/")),
      (c) => c.charCodeAt(0),
    );
    const signature = Uint8Array.from(
      atob(issued.signature.replace(/-/g, "+").replace(/_/g, "/")),
      (c) => c.charCodeAt(0),
    );
    assert(
      await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" },
        key,
        signature,
        payloadBytes,
      ),
      "lease signature must verify with the returned key",
    );
    assertEqual(base64Url(payloadBytes), issued.payload);

    const payload = JSON.parse(new TextDecoder().decode(payloadBytes));
    assertEqual(Object.keys(payload), [
      "schemaVersion",
      "projectRef",
      "storeId",
      "userId",
      "installationId",
      "generation",
      "issuedAt",
      "expiresAt",
      "allowed",
    ]);
    assertEqual(payload.schemaVersion, 1);
    assertEqual(payload.projectRef, "testref");
    assertEqual(payload.storeId, store.storeId);
    assertEqual(payload.userId, store.userId);
    assertEqual(payload.installationId, device.installationId);
    assertEqual(payload.generation, 1);
    assert(payload.issuedAt >= before, "issuedAt is now");
    assertEqual(payload.expiresAt - payload.issuedAt, 7 * 86_400_000);
    assertEqual(payload.allowed, [
      "contact.create",
      "debt.create",
      "debt.offset",
      "payment.create",
    ]);
  },
);

dbTest(
  "a replacement installation resends a revoked one's commands under a recovery permit",
  async () => {
    const { store, device: lost, epoch } = await activeStore(1);
    const pending = fill(operationsFixture.request, {
      installationId: lost.installationId,
      epoch,
    });
    await database.asAdmin("sadid_revoke_installation", [
      lost.installationId,
      "ضاع الهاتف",
    ]);

    const replacement = await newDevice();
    const enrolled = await send(
      store.jwt,
      replacement,
      "POST",
      "/v1/mobile/enroll",
      { body: enrollBody(replacement) },
    );
    assertEqual(enrolled.status, 200);
    await database.asAdmin("sadid_approve_installation", [
      replacement.installationId,
      "هاتف بديل",
    ]);

    const refused = await send(
      store.jwt,
      replacement,
      "POST",
      "/v1/mobile/operations",
      { body: pending },
    );
    await assertRefusal(refused, { status: 403, code: "installation_revoked" });

    const permit = await database.asAdmin<{ permitId: string }>(
      "sadid_approve_recovery",
      [
        lost.installationId,
        replacement.installationId,
        pending.operations,
        "استرداد",
      ],
    );
    const recovered = await send(
      store.jwt,
      replacement,
      "POST",
      "/v1/mobile/operations",
      { body: pending, headers: { "x-sadad-recovery": permit.permitId } },
    );
    assertEqual(recovered.status, 200);
    const { receipts } = await recovered.json();
    assertEqual(
      receipts.map((r: Record<string, unknown>) => r.status),
      ["accepted", "accepted", "accepted"],
    );

    const outside = fill(operationsFixture.request, {
      installationId: lost.installationId,
      epoch,
    }).operations[0];
    outside.operationId = crypto.randomUUID();
    const notListed = await send(
      store.jwt,
      replacement,
      "POST",
      "/v1/mobile/operations",
      {
        body: { operations: [outside] },
        headers: { "x-sadad-recovery": permit.permitId },
      },
    );
    await assertRefusal(notListed, {
      status: 403,
      code: "recovery_not_authorized",
    });
  },
);

dbTest(
  "the proof covers the body bytes as sent, not a re-serialized copy",
  async () => {
    const { store, device, epoch } = await activeStore();
    const body = fill(operationsFixture.request, {
      installationId: device.installationId,
      epoch,
    });
    const pretty = JSON.stringify(body, null, 2);
    const proof = await signProof(device.privateKey, {
      method: "POST",
      route: "/v1/mobile/operations",
      body: pretty,
      installationId: device.installationId,
    });
    const res = await app.request("https://test.invalid/v1/mobile/operations", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${store.jwt}`,
        "content-type": "application/json",
        ...proof,
      },
      body: pretty,
    });
    assertEqual(res.status, 200);
    assertEqual((await res.json()).receipts.length, 3);
  },
);

dbTest(
  "a batch body over 512 KiB is refused with 413 before any database call",
  async () => {
    const { store, device } = await activeStore();
    const note = "x".repeat(6_000);
    const operations = Array.from({ length: 100 }, () => ({ note }));
    const res = await send(store.jwt, device, "POST", "/v1/mobile/operations", {
      body: { operations },
    });
    await assertRefusal(res, { status: 413, code: "payload_too_large" });
  },
);
