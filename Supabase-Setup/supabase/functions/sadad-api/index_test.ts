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

const DEVICE_LIMIT_MESSAGE =
  "وصل الحساب إلى حد الأجهزة المسموح (2). اطلب من الأدمن زيادة العدد أو السماح بهذا الجهاز.";
const NOT_FOUND_MESSAGE = "المسار غير موجود.";

// House fake for the supabase query builder (see sadad-auth/handler_test.ts): chainable and awaitable,
// plus the head-count shape the code reads for the device/account counts.
function query(cfg: { row?: Json | null; count?: number; list?: Json[] }): any {
  let countMode = false;
  const q: any = {};
  // Records [method, args] for each filter call so tests can assert on the exact pattern sent.
  q.calls = [] as Array<[string, any[]]>;
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
  ) q[method] = (...args: any[]) => {
    q.calls.push([method, args]);
    return q;
  };
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

function fixture(options: { v3?: boolean; admins?: number; sessionOk?: boolean; admin?: boolean; rejectLegacy?: boolean; deviceCount?: number; revokedDevice?: boolean; capture?: (table: string, builder: any) => void } = {}) {
  const tables: Record<
    string,
    { row?: Json | null; count?: number; list?: Json[] }
  > = {
    admins: { row: options.admin ? { ...STORE, force_password_change: false } : null, count: options.admins ?? 0, list: [] },
    stores: { row: STORE, list: [STORE] },
    sessions: { row: { ...SESSION, kind: options.admin ? "admin" : "store" }, list: [SESSION] },
    store_devices: { row: { ...DEVICE, revoked_at: options.revokedDevice ? CLOCK : null }, count: options.deviceCount ?? 0, list: [] },
    login_rate_limits: { list: [] },
  };
  const db: any = {
    from: (table: string) => {
      if (options.rejectLegacy && ["contacts", "debts", "payments", "store_backups", "contact_archives"].includes(table))
        throw new Error("Unexpected legacy financial read");
      const builder = query(tables[table] ?? { list: [] });
      if (options.capture) options.capture(table, builder);
      return builder;
    },
    rpc: (name: string) => {
      if (name === "sadad_admin_store_list" && options.rejectLegacy) throw new Error("Unexpected legacy financial summary");
      if (name === "sadid_admin_summaries") return Promise.resolve({ data: [{ storeId: 7, initialized: !!options.v3, contacts: 1, debts: 1, debtTotal: 120 }], error: null });
      if (name === "sadid_pending_installations") return Promise.resolve({ data: [], error: null });
      if (name === "sadid_admin_controls") return Promise.resolve({ data: { hasDeviceHistory: false }, error: null });
      if (name === "sadid_support_snapshot") return Promise.resolve({ data: { epoch: "epoch", cursor: 1, contacts: [{ id: "person", name: "Signed contact" }], debts: [], payments: [], events: [] }, error: null });
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

Deno.test("retired financial routes are unavailable even to an authenticated uninitialized store", async () => {
  const { api, send } = fixture({ v3: false });
  for (const [path, method] of [["/mobile/snapshot", "GET"], ["/mobile/snapshot-page", "GET"],
    ["/mobile/activity", "GET"], ["/mobile/sync", "POST"], ["/mobile/sync-delta", "POST"],
    ["/mobile/whatsapp", "GET"], ["/mobile/whatsapp", "PUT"], ["/mobile/whatsapp/send-report", "POST"]]) {
    const response = await dispatch(api, send(path, method, method === "GET" ? undefined : {}, true));
    assertEquals(response.status, 404, path);
  }
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

Deno.test("login escapes LIKE wildcards in the username", async () => {
  const builders: Record<string, any> = {};
  const { api, send } = fixture({ capture: (table: string, q: any) => {
    builders[table] = q;
  } });
  await dispatch(
    api,
    send("/mobile/login", "POST", {
      username: "a_c",
      password: PASSWORD,
      deviceId: DEVICE_ID,
      deviceName: "Phone",
      staffName: "Ali",
    }),
  );
  const ilike = builders.stores.calls.find(([method]: [string]) => method === "ilike");
  assertEquals(ilike?.[1][1], "a\\_c");
});

Deno.test("login keeps case-insensitive exact match", async () => {
  const builders: Record<string, any> = {};
  const { api, send } = fixture({ capture: (table: string, q: any) => {
    builders[table] = q;
  } });
  await dispatch(
    api,
    send("/mobile/login", "POST", {
      username: "Abc",
      password: PASSWORD,
      deviceId: DEVICE_ID,
      deviceName: "Phone",
      staffName: "Ali",
    }),
  );
  const ilike = builders.stores.calls.find(([method]: [string]) => method === "ilike");
  assertEquals(ilike?.[1][1], "Abc");
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

Deno.test("an uninitialized test store logs into the signed protocol without a legacy snapshot", async () => {
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
  assertEquals(body.ledger, "v3");
  assertEquals(Object.hasOwn(body, "snapshot"), false);
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

Deno.test("dashboard reads signed finances and empty uninitialized stores without legacy tables", async () => {
  for (const initialized of [false, true]) {
    const { api, send } = fixture({ admin: true, rejectLegacy: true, v3: initialized });
    const list = await dispatch(api, send("/admin/stores", "GET", undefined, true));
    assertEquals(list.status, 200);
    assertEquals((await list.json()).stores[0].name, "Sadad Store");
    const detail = await dispatch(api, send("/admin/stores/7", "GET", undefined, true));
    assertEquals(detail.status, 200);
    const body = await detail.json();
    assertEquals(body.snapshot.contacts.map((row: Json) => row.name), initialized ? ["Signed contact"] : []);
    assertEquals(body.snapshot.totals, { receivable: 0, payable: 0, net: 0 });
    for (const [path, method] of [["backups/1", "GET"], ["backups/1/restore", "POST"],
      ["archives", "GET"], ["archives/1/restore", "POST"], ["devices/device-id/permissions", "POST"]]) {
      const retired = await dispatch(api, send("/admin/stores/7/" + path, method, method === "GET" ? undefined : {}, true));
      assertEquals(retired.status, 404, path);
    }
  }
});

Deno.test("admin can restore login after revocation while respecting the device limit", async () => {
  for (const [count, expected] of [[0, 200], [2, 409]]) {
    const { api, send } = fixture({ admin: true, rejectLegacy: true, v3: true, revokedDevice: true, deviceCount: count });
    const response = await dispatch(api, send("/admin/stores/7/devices/dev-1234567890abcd/allow", "POST", {}, true));
    assertEquals(response.status, expected);
    if (expected === 200) assertEquals((await response.json()).allowed, true);
  }
});
