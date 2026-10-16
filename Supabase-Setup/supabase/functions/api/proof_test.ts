import { proofText, sha256, verifyProof } from "./proof.ts";
import { assert, assertEqual, signProof } from "./test_support.ts";
import fixture from "../../../../contract/fixtures/mobile/proof.json" with {
  type: "json",
};

function fixtureRequest(overrides: Record<string, string> = {}): Request {
  return new Request(`https://test.invalid${fixture.route}`, {
    method: fixture.method,
    body: fixture.body,
    headers: {
      "x-sadad-installation": fixture.installationId,
      "x-sadad-time": fixture.time,
      "x-sadad-nonce": fixture.nonce,
      "x-sadad-signature": fixture.signature,
      ...overrides,
    },
  });
}

Deno.test("proof text matches the shared fixture (same text as InstallationKey.proofText)", async () => {
  assertEqual(await sha256(fixture.body), fixture.bodySha256);
  assertEqual(
    proofText(
      fixture.method.toLowerCase(),
      fixture.route,
      fixture.bodySha256,
      fixture.time,
      fixture.nonce,
      fixture.installationId,
    ),
    fixture.proofText,
  );
});

Deno.test("the fixture signature verifies, and any change to the signed parts does not", async () => {
  const now = Number(fixture.time);
  assertEqual(
    await verifyProof(
      fixtureRequest(),
      fixture.route,
      fixture.body,
      fixture.publicKey,
      now,
    ),
    { installationId: fixture.installationId, nonce: fixture.nonce },
  );
  assertEqual(
    await verifyProof(
      fixtureRequest(),
      fixture.route,
      `${fixture.body} `,
      fixture.publicKey,
      now,
    ),
    null,
  );
  assertEqual(
    await verifyProof(
      fixtureRequest(),
      "/v1/mobile/enroll",
      fixture.body,
      fixture.publicKey,
      now,
    ),
    null,
  );
  assertEqual(
    await verifyProof(
      fixtureRequest({ "x-sadad-nonce": crypto.randomUUID() }),
      fixture.route,
      fixture.body,
      fixture.publicKey,
      now,
    ),
    null,
  );
});

Deno.test("a proof older or newer than five minutes is refused", async () => {
  const now = Number(fixture.time);
  for (const skew of [300_001, -300_001]) {
    assertEqual(
      await verifyProof(
        fixtureRequest(),
        fixture.route,
        fixture.body,
        fixture.publicKey,
        now + skew,
      ),
      null,
    );
  }
});

Deno.test("a key carrying a private part or another curve is refused", async () => {
  const now = Number(fixture.time);
  assertEqual(
    await verifyProof(
      fixtureRequest(),
      fixture.route,
      fixture.body,
      { ...fixture.publicKey, d: "AAAA" },
      now,
    ),
    null,
  );
  assertEqual(
    await verifyProof(
      fixtureRequest(),
      fixture.route,
      fixture.body,
      { ...fixture.publicKey, crv: "P-384" },
      now,
    ),
    null,
  );
});

Deno.test("a proof made by a freshly generated key verifies", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const { kty, crv, x, y } = await crypto.subtle.exportKey(
    "jwk",
    pair.publicKey,
  );
  const installationId = crypto.randomUUID();
  const body = JSON.stringify({ hello: "سدد" });
  const headers = await signProof(pair.privateKey, {
    method: "POST",
    route: "/v1/mobile/lease",
    body,
    installationId,
  });
  const request = new Request("https://test.invalid/v1/mobile/lease", {
    method: "POST",
    body,
    headers,
  });
  const result = await verifyProof(request, "/v1/mobile/lease", body, {
    kty,
    crv,
    x,
    y,
  });
  assert(result, "a fresh proof must verify");
  assertEqual(result.installationId, installationId);
});
