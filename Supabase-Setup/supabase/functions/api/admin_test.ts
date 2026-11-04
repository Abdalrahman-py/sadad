// Role checks on every /v1/admin/* route, enumerated from the OpenAPI document so a new route is covered.
import { buildOpenApiDocument } from "./app.ts";
import { adminHarness, assertRefusal, dbTest } from "./admin/test_admin.ts";
import { assert, assertEqual } from "./test_support.ts";
import refusals from "../../../../contract/fixtures/admin/refusals.json" with {
  type: "json",
};

const h = await adminHarness();
const store = await h.seedStore();
await h.database.query(
  "insert into public.sadid_ledger_state(store_id) values ($1)",
  [store.storeId],
);

const routes = Object.entries(
  buildOpenApiDocument(h.app).paths as Record<string, Record<string, unknown>>,
)
  .filter(([path]) => path.startsWith("/v1/admin/"))
  .flatMap(([path, methods]) =>
    Object.keys(methods).map((method) => ({
      method: method.toUpperCase(),
      path: path.replace(/^\/v1/, "")
        .replace("{storeId}", String(store.storeId))
        .replace("{installationId}", crypto.randomUUID())
        .replace("{action}", "end-sessions")
        .replace("{kind}", "contacts"),
    }))
  );

const bodyFor = (route: { method: string }) =>
  route.method === "GET" || route.method === "DELETE" ? undefined : {};

dbTest("the admin routes are in the OpenAPI document", () => {
  assertEqual(routes.length, 17);
  return Promise.resolve();
});

dbTest("no JWT is refused on every admin route", async () => {
  for (const route of routes) {
    const res = await h.send(null, route.method, route.path, bodyFor(route));
    await assertRefusal(res, refusals.noJwt);
  }
});

dbTest("a store user's JWT is refused on every admin route", async () => {
  const { jwt } = await h.jwtFor("store", { userId: store.userId });
  for (const route of routes) {
    const res = await h.send(jwt, route.method, route.path, bodyFor(route));
    assertEqual({ route, status: res.status }, { route, status: 403 });
    await assertRefusal(res, refusals.notAdmin);
  }
});

dbTest(
  "support reads every GET route and is refused on every write route",
  async () => {
    const { jwt } = await h.jwtFor("support");
    for (const route of routes) {
      const res = await h.send(jwt, route.method, route.path, bodyFor(route));
      // The seat is per account, not per role: support claims and releases its own like an admin.
      if (route.path === "/admin/session") {
        assertEqual({ route, status: res.status }, { route, status: 200 });
        await res.body?.cancel();
      } else if (route.method === "GET") {
        assertEqual({ route, status: res.status }, { route, status: 200 });
        await res.body?.cancel();
      } else {
        await assertRefusal(res, refusals.notAdmin);
      }
    }
  },
);

dbTest(
  "support: GET /v1/admin/stores 200, POST /v1/admin/stores 403",
  async () => {
    const { jwt } = await h.jwtFor("support");
    const list = await h.send(jwt, "GET", "/admin/stores");
    assertEqual(list.status, 200);
    assert((await list.json()).stores.length > 0, "support sees stores");

    const create = await h.send(jwt, "POST", "/admin/stores", {
      name: "متجر",
      username: "support-made",
      subscriptionMode: "permanent",
    });
    await assertRefusal(create, refusals.notAdmin);
    assertEqual(h.authAdmin.calls.length, 0);
  },
);

dbTest(
  "an admin with force_password_change is refused until the password changes",
  async () => {
    const { jwt } = await h.jwtFor("admin", { forcePasswordChange: true });
    await assertRefusal(
      await h.send(jwt, "GET", "/admin/stores"),
      refusals.passwordChange,
    );
  },
);

dbTest(
  "an admin JWT whose Auth session ended is refused by the routines",
  async () => {
    const { jwt, sessionId } = await h.jwtFor("admin");
    await h.database.query("delete from auth.sessions where id = $1", [
      sessionId,
    ]);
    const list = await h.send(jwt, "GET", "/admin/stores");
    await assertRefusal(list, refusals.notAdmin);
    await assertRefusal(
      await h.send(jwt, "POST", "/admin/stores", {
        name: "متجر",
        username: "ended-session",
        subscriptionMode: "permanent",
      }),
      refusals.notAdmin,
    );
    assertEqual(h.authAdmin.calls.length, 0);
  },
);

dbTest("a support JWT cannot call write routines directly either", async () => {
  // The Data API exposes the public routines to any authenticated JWT; SQL enforces the role.
  const { jwt } = await h.jwtFor("support");
  const db = h.database.userClient(jwt);
  for (
    const [fn, args] of [
      ["sadid_admin_update_store", {
        p_store: store.storeId,
        p_changes: { verified: true },
      }],
      ["sadid_admin_subscription", {
        p_store: store.storeId,
        p_mode: "paused",
      }],
      ["sadid_admin_reset_password", { p_store: store.storeId, p_reason: "x" }],
      ["sadid_admin_delete_store", {
        p_store: store.storeId,
        p_confirm: store.username,
      }],
    ] as const
  ) {
    const { error } = await db.rpc(fn, args);
    assertEqual({ fn, message: error?.message }, {
      fn,
      message: "admin_authorization_required",
    });
  }
});
