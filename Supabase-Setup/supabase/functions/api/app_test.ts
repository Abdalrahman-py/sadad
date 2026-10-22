import { buildOpenApiDocument, createApp } from "./app.ts";
import { requireAuth, requireRole } from "./auth.ts";
import { errorMessages, fromDatabaseError } from "./errors.ts";
import {
  assert,
  assertEqual,
  createTestKeyPair,
  makeSignedJwt,
} from "./test_support.ts";

Deno.test("GET /v1/health returns 200 without auth and enforces CORS allow-list", async () => {
  const app = createApp({
    allowedOrigins: ["https://abdalrahman-py.github.io"],
  });

  const res = await app.request("https://test.invalid/v1/health", {
    headers: { Origin: "https://abdalrahman-py.github.io" },
  });
  assertEqual(res.status, 200);
  assertEqual(await res.json(), { ok: true });
  assertEqual(
    res.headers.get("access-control-allow-origin"),
    "https://abdalrahman-py.github.io",
  );

  const disallowed = await app.request("https://test.invalid/v1/health", {
    headers: { Origin: "https://evil.example.com" },
  });
  assertEqual(disallowed.status, 200);
  assertEqual(disallowed.headers.get("access-control-allow-origin"), null);

  // Supabase routes requests to the function as /api/v1/...
  const deployed = await app.request("https://test.invalid/api/v1/health");
  assertEqual(deployed.status, 200);
});

Deno.test("missing Authorization returns 401 with contract error shape", async () => {
  const app = createApp();
  app.get("/protected", requireAuth(), (c) => c.json({ ok: true }));

  const res = await app.request("https://test.invalid/v1/protected");
  assertEqual(res.status, 401);
  assertEqual(await res.json(), {
    ok: false,
    code: "authorization_required",
    message: errorMessages.authorization_required,
  });
});

Deno.test("support JWT reaching an admin-only route returns 403 and storeId comes from RLS membership", async () => {
  const { pair, kid, verifyClaims } = await createTestKeyPair();
  const lookupCalls: string[] = [];

  const loggedErrors: Array<{ requestId: string; error: string }> = [];
  const app = createApp({
    verifyClaims,
    lookupStoreId: (jwt: string) => {
      lookupCalls.push(jwt);
      return Promise.resolve(42);
    },
    logError: (entry) => loggedErrors.push(entry),
  });

  app.get(
    "/admin-only",
    requireRole("admin"),
    (c) => c.json({ ok: true, auth: c.get("auth") }),
  );
  app.get("/store-context", requireAuth(), (c) => {
    const auth = c.get("auth");
    return c.json({
      ok: true,
      userId: auth.userId,
      role: auth.role,
      storeId: auth.storeId,
    });
  });
  app.get("/boom", () => {
    throw new Error("unexpected database crash");
  });

  const supportJwt = await makeSignedJwt(pair.privateKey, kid, {
    sub: "11111111-1111-4111-8111-111111111111",
    session_id: "22222222-2222-4222-8222-222222222222",
    app_metadata: { role: "support" },
  });

  const forbidden = await app.request("https://test.invalid/v1/admin-only", {
    headers: { Authorization: `Bearer ${supportJwt}` },
  });
  assertEqual(forbidden.status, 403);
  assertEqual(await forbidden.json(), {
    ok: false,
    code: "admin_authorization_required",
    message: errorMessages.admin_authorization_required,
  });

  const storeUserId = "33333333-3333-4333-8333-333333333333";
  const storeJwt = await makeSignedJwt(pair.privateKey, kid, {
    sub: storeUserId,
    session_id: "44444444-4444-4444-8444-444444444444",
    app_metadata: { role: "store" },
  });

  const storeRes = await app.request("https://test.invalid/v1/store-context", {
    headers: {
      Authorization: `Bearer ${storeJwt}`,
      "x-store-id": "999",
    },
  });
  assertEqual(storeRes.status, 200);
  assertEqual(await storeRes.json(), {
    ok: true,
    userId: storeUserId,
    role: "store",
    storeId: 42,
  });
  assertEqual(lookupCalls, [storeJwt]);

  const boomRes = await app.request("https://test.invalid/v1/boom", {
    headers: { "x-request-id": "req-test-500" },
  });
  assertEqual(boomRes.status, 500);
  const boomBody = await boomRes.json();
  assertEqual(boomBody, {
    ok: false,
    code: "internal_error",
    message: errorMessages.internal_error,
    requestId: "req-test-500",
  });
  assertEqual(loggedErrors, [
    { requestId: "req-test-500", error: "unexpected database crash" },
  ]);
});

Deno.test("store JWT without a membership is refused with 403", async () => {
  const { pair, kid, verifyClaims } = await createTestKeyPair();
  const app = createApp({
    verifyClaims,
    lookupStoreId: () => Promise.resolve(null),
  });
  app.get("/store-context", requireAuth(), (c) => c.json({ ok: true }));

  const jwt = await makeSignedJwt(pair.privateKey, kid, {
    sub: "55555555-5555-4555-8555-555555555555",
    session_id: "66666666-6666-4666-8666-666666666666",
  });
  const res = await app.request("https://test.invalid/v1/store-context", {
    headers: { Authorization: `Bearer ${jwt}` },
  });
  assertEqual(res.status, 403);
  assertEqual(await res.json(), {
    ok: false,
    code: "store_permission_denied",
    message: errorMessages.store_permission_denied,
  });
});

Deno.test("a unique violation is not mapped to a contract code", () => {
  assertEqual(
    fromDatabaseError({ code: "23505", message: "duplicate key" }),
    null,
  );
});

Deno.test("every code in contract/errors.json has an Arabic message and OpenAPI document builds", async () => {
  const rawErrors = JSON.parse(
    await Deno.readTextFile(
      new URL("../../../../contract/errors.json", import.meta.url),
    ),
  ) as Record<string, unknown>;
  // api/errors.json is a deployable copy; on failure copy contract/errors.json over it.
  assertEqual(errorMessages, rawErrors);
  const codes = Object.keys(rawErrors);
  assert(codes.length >= 30, "expected seeded contract error codes");

  const arabicPattern = /[\u0600-\u06FF]/;
  for (const [code, message] of Object.entries(rawErrors)) {
    assert(
      typeof message === "string" &&
        message.trim().length > 0 &&
        arabicPattern.test(message),
      `Error code "${code}" is missing an Arabic message`,
    );
  }

  const doc = buildOpenApiDocument();
  assertEqual(doc.openapi, "3.0.0");
  assert(
    Boolean(doc.paths?.["/v1/health"]?.get),
    "OpenAPI document must include GET /v1/health",
  );
  const savedDoc = JSON.parse(
    await Deno.readTextFile(
      new URL("../../../../contract/openapi.json", import.meta.url),
    ),
  );
  assertEqual(doc, savedDoc);
});
