import { buildOpenApiDocument, createApp } from "./app.ts";
import { gateCaller } from "./auth.ts";
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
  app.get("/protected", async (c) => {
    await gateCaller(c, ["admin"]);
    return c.json({ ok: true });
  });

  const res = await app.request("https://test.invalid/v1/protected");
  assertEqual(res.status, 401);
  assertEqual(await res.json(), {
    ok: false,
    code: "authorization_required",
    message: errorMessages.authorization_required,
  });
});

Deno.test("support JWT reaching an admin-only route returns 403, and an unexpected error is a logged 500", async () => {
  const { pair, kid, verifyClaims } = await createTestKeyPair();

  const loggedErrors: Array<{ requestId: string; error: string }> = [];
  const app = createApp({
    verifyClaims,
    logError: (entry) => loggedErrors.push(entry),
  });

  app.get("/admin-only", async (c) => {
    await gateCaller(c, ["admin"]);
    return c.json({ ok: true });
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

// A mobile route and an admin route both open with gateCaller. No database is wired in: every
// refusal below happens before the first routine call.
Deno.test("one gate: the mobile and the admin tier refuse a wrong role and a forced password change alike", async () => {
  const { pair, kid, verifyClaims } = await createTestKeyPair();
  const app = createApp({ verifyClaims });
  const jwt = (role: string, extra: Record<string, unknown> = {}) =>
    makeSignedJwt(pair.privateKey, kid, {
      sub: "11111111-1111-4111-8111-111111111111",
      session_id: "22222222-2222-4222-8222-222222222222",
      app_metadata: { role },
      ...extra,
    });
  const refusal = async (path: string, token: string) => {
    const res = await app.request(`https://test.invalid/v1${path}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const body = await res.json();
    return { status: res.status, code: body.code };
  };
  const mobile = "/mobile/changes";
  const admin = "/admin/stores";

  // The refusal names the tier the caller reached.
  assertEqual(await refusal(mobile, await jwt("admin")), {
    status: 403,
    code: "store_permission_denied",
  });
  assertEqual(await refusal(admin, await jwt("store")), {
    status: 403,
    code: "admin_authorization_required",
  });

  // A temporary password blocks both tiers with the same code.
  const locked = (role: string) =>
    jwt(role, { app_metadata: { role, force_password_change: true } });
  for (
    const [path, role] of [[mobile, "store"], [admin, "admin"]] as const
  ) {
    assertEqual(await refusal(path, await locked(role)), {
      status: 403,
      code: "password_change_required",
    });
  }

  // Only the mobile tier binds to the session claim.
  assertEqual(
    await refusal(mobile, await jwt("store", { session_id: undefined })),
    { status: 401, code: "authorization_required" },
  );
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
