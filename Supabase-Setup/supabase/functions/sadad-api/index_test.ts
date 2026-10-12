import { fail, makeApi } from "./handler.ts";
import { scryptSync } from "node:crypto";

type Json = Record<string, any>;

function assertEquals(
  actual: unknown,
  expected: unknown,
  message = "values differ",
): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `${message}: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`,
    );
  }
}

const CLOCK = 1_700_000_000_000;
const PASSWORD = "secret123456";
const SALT_HEX = "00112233445566778899aabbccddeeff";
const SALT = `scrypt$1024$${SALT_HEX}`;
const DEVICE_ID = "abcdefghij0123456789";

// The function's own raw scrypt parameters, so a verified login is reproduced without a database.
const PASSWORD_HASH = scryptSync(PASSWORD, SALT_HEX, 64, {
  N: 1024,
  r: 8,
  p: 1,
  maxmem: 64 * 1024 * 1024,
}).toString("hex");

const STORE: Json = {
  id: 7,
  name: "Sadad Store",
  username: "shop",
  status: "active",
  subscription_mode: "permanent",
  allowed_devices: 2,
  force_password_change: false,
  permissions: {},
  verified: true,
  debtor_limit: 100,
  whatsapp_enabled: false,
  revision: 3,
  salt: SALT,
  password_hash: PASSWORD_HASH,
};
const SESSION: Json = {
  kind: "store",
  principal_id: 7,
  device_id: "dev-1234567890abcd",
  expires_at: CLOCK + 3_600_000,
};
const DEVICE: Json = {
  device_id: "dev-1234567890abcd",
  staff_name: "Ali",
  permissions: {},
};

const LEGACY_MESSAGE =
  "هذا المتجر يستخدم السجل المعتمد. حدّث التطبيق إلى الإصدار الثالث؛ احتفظ بأي إدخالات قديمة دون حذفها.";
const DEVICE_LIMIT_MESSAGE =
  "وصل الحساب إلى حد الأجهزة المسموح (2). اطلب من الأدمن زيادة العدد أو السماح بهذا الجهاز.";
const NOT_FOUND_MESSAGE = "المسار غير موجود.";

// House fake for the supabase query builder (see sadad-auth/handler_test.ts): chainable and awaitable,
// plus the head-count shape the code reads for the device/account counts.
function query(cfg: { row?: Json | null; count?: number; list?: Json[] }): any {
  let countMode = false;
  const q: any = {};
  q.select = (_columns?: unknown, options?: { head?: boolean }) => {
    if (options?.head) countMode = true;
    return q;
  };
  for (
    const method of [
      "eq",
      "neq",
      "gt",
      "lt",
      "gte",
      "lte",
      "in",
      "is",
      "or",
      "ilike",
      "order",
      "limit",
      "update",
      "upsert",
      "delete",
      "insert",
    ]
  ) q[method] = () => q;
  q.single = q.maybeSingle = () =>
    Promise.resolve({ data: cfg.row ?? null, error: null });
  q.then = (resolved: (value: any) => any, rejected?: (reason: any) => any) =>
    Promise.resolve(
      countMode
        ? { data: cfg.list ?? null, error: null, count: cfg.count ?? 0 }
        : { data: cfg.list ?? null, error: null },
    ).then(resolved, rejected);
  return q;
}

function fixture(options: { v3?: boolean; admins?: number; sessionOk?: boolean } = {}) {
  const tables: Record<
    string,
    { row?: Json | null; count?: number; list?: Json[] }
  > = {
    admins: { count: options.admins ?? 0, list: [] },
    stores: { row: STORE, list: [STORE] },
    sessions: { row: SESSION, list: [SESSION] },
    store_devices: { row: DEVICE, count: 0, list: [] },
    login_rate_limits: { list: [] },
  };
  const db: any = {
    from: (table: string) => query(tables[table] ?? { list: [] }),
    rpc: (name: string) => {
      if (name === "sadid_is_v3_store") {
        return Promise.resolve({ data: !!options.v3, error: null });
      }
      if (name === "sadad_create_store_session") {
        return Promise.resolve(
          options.sessionOk
            ? { data: { staff_name: "Ali", permissions: {} }, error: null }
            : { data: null, error: { code: "P0001", message: "device_limit" } },
        );
      }
      return Promise.resolve({ data: null, error: null });
    },
  };
  const api = makeApi({
    db,
    now: () => CLOCK,
    fetcher: (() => {
      throw new Error("unexpected network call");
    }) as unknown as typeof fetch,
  });
  const send = (
    path: string,
    method = "GET",
    body?: Json,
    withSession = false,
  ) =>
    new Request(`https://project.test/functions/v1/sadad-api${path}`, {
      method,
      headers: {
        ...(withSession ? { Authorization: "Bearer test-token" } : {}),
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  return { api, send };
}

// Exactly the serve shell: the dispatcher throws, fail() turns that into the HTTP response.
async function dispatch(
  api: { route: (request: Request) => Promise<Response> },
  request: Request,
): Promise<Response> {
  try {
    return await api.route(request);
  } catch (error) {
    return fail(error);
  }
}

Deno.test("GET /health returns the service envelope", async () => {
  const { api, send } = fixture();
  const response = await dispatch(api, send("/health"));
  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    ok: true,
    service: "sadad",
    storage: "supabase-postgres",
  });
});

Deno.test("GET /setup/status reports the bootstrap state from the admin count", async () => {
  const { api, send } = fixture({ admins: 0 });
  const response = await dispatch(api, send("/setup/status"));
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { ok: true, bootstrapRequired: true });
});

Deno.test("an unknown route answers the existing 404 message", async () => {
  const { api, send } = fixture();
  const response = await dispatch(api, send("/nope"));
  assertEquals(response.status, 404);
  assertEquals((await response.json()).message, NOT_FOUND_MESSAGE);
});

Deno.test("a version-3 store is read-only on a non-exempt mobile route", async () => {
  const { api, send } = fixture({ v3: true });
  const response = await dispatch(
    api,
    send("/mobile/snapshot", "GET", undefined, true),
  );
  const body = await response.json();
  assertEquals(response.status, 409);
  assertEquals(body.code, "legacy_ledger_read_only");
  assertEquals(body.message, LEGACY_MESSAGE);
});

Deno.test("mobile session stays reachable on a version-3 store", async () => {
  const { api, send } = fixture({ v3: true });
  const response = await dispatch(
    api,
    send("/mobile/session", "GET", undefined, true),
  );
  const body = await response.json();
  assertEquals(response.status, 200);
  assertEquals(body.account.id, 7);
});

Deno.test("login states the protocol: a signed-ledger store", async () => {
  const { api, send } = fixture({ v3: true, sessionOk: true });
  const response = await dispatch(
    api,
    send("/mobile/login", "POST", {
      username: "shop",
      password: PASSWORD,
      deviceId: DEVICE_ID,
      deviceName: "Phone",
      staffName: "Ali",
    }),
  );
  const body = await response.json();
  assertEquals(response.status, 200);
  assertEquals(body.ledger, "v3");
});

Deno.test("login states the protocol: a store on the older protocol", async () => {
  const { api, send } = fixture({ v3: false, sessionOk: true });
  const response = await dispatch(
    api,
    send("/mobile/login", "POST", {
      username: "shop",
      password: PASSWORD,
      deviceId: DEVICE_ID,
      deviceName: "Phone",
      staffName: "Ali",
    }),
  );
  const body = await response.json();
  assertEquals(response.status, 200);
  assertEquals(body.ledger, "legacy");
});

Deno.test("a P0001 sentinel maps through the table to its exact status and message", async () => {
  const { api, send } = fixture();
  const response = await dispatch(
    api,
    send("/mobile/login", "POST", {
      username: "shop",
      password: PASSWORD,
      deviceId: DEVICE_ID,
      deviceName: "Phone",
      staffName: "Ali",
    }),
  );
  const body = await response.json();
  assertEquals(response.status, 403);
  assertEquals(body.message, DEVICE_LIMIT_MESSAGE);
});
