import { assert, assertEqual } from "../test_support.ts";
import { adminHarness, assertRefusal, dbTest } from "./test_admin.ts";
import createFixture from "../../../../../contract/fixtures/admin/create-store.json" with {
  type: "json",
};
import refusals from "../../../../../contract/fixtures/admin/refusals.json" with {
  type: "json",
};

const h = await adminHarness();
const admin = await h.jwtFor("admin");

async function storeRow(id: number) {
  const [row] = await h.database.query<Record<string, unknown>>(
    "select * from public.stores where id = $1",
    [id],
  );
  return row;
}

async function liveSessions(userId: string): Promise<number> {
  const [{ n }] = await h.database.query<{ n: number }>(
    "select count(*)::int as n from auth.sessions where user_id = $1",
    [userId],
  );
  return n;
}

async function addInstallation(storeId: number, status: string) {
  const id = crypto.randomUUID();
  await h.database.query(
    "insert into sadid_private.installations(id, store_id, generation, public_key, status) values ($1, $2, 1, '{}', $3)",
    [id, storeId, status],
  );
  return id;
}

dbTest(
  "create store: Auth user <username>@stores.sadad.invalid with force_password_change, a membership row, the password once",
  async () => {
    const res = await h.send(
      admin.jwt,
      "POST",
      "/admin/stores",
      createFixture.request,
    );
    assertEqual(res.status, 201);
    const body = await res.json();
    assertEqual(
      { ...body, storeId: "{storeId}", temporaryPassword: "{password}" },
      createFixture.response,
    );
    assert(
      /^[A-Za-z0-9_-]{24}$/.test(body.temporaryPassword),
      "a 24-character password",
    );

    const [createUser] = h.authAdmin.calls.filter((call) =>
      call.method === "createUser"
    );
    assertEqual(createUser.attributes, {
      email: "corner-shop@stores.sadad.invalid",
      password: body.temporaryPassword,
      email_confirm: true,
      app_metadata: { role: "store", force_password_change: true },
    });

    const [membership] = await h.database.query<{ user_id: string }>(
      "select m.user_id::text from sadid_private.store_memberships m join auth.users u on u.id = m.user_id where m.store_id = $1 and u.email = 'corner-shop@stores.sadad.invalid'",
      [body.storeId],
    );
    assert(membership, "the store is linked to the new Auth user");

    const row = await storeRow(body.storeId);
    assertEqual({
      name: row.name,
      username: row.username,
      status: row.status,
      subscription_mode: row.subscription_mode,
      allowed_devices: row.allowed_devices,
      debtor_limit: row.debtor_limit,
      permissions: row.permissions,
    }, {
      name: "بقالة الزاوية",
      username: "corner-shop",
      status: "active",
      subscription_mode: "timed",
      allowed_devices: 2,
      debtor_limit: 300,
      permissions: { export: false },
    });
    assert(
      Number(row.subscription_expires_at) > Date.now() + 29 * 86_400_000,
      "30 days from now",
    );

    assert(
      !h.logs.some((line) => line.includes(body.temporaryPassword)),
      "the password is never logged",
    );
  },
);

dbTest(
  "create store with a taken username is refused, and an Auth user made for it is removed",
  async () => {
    const res = await h.send(admin.jwt, "POST", "/admin/stores", {
      ...createFixture.request,
      username: "Corner-Shop",
    });
    await assertRefusal(res, refusals.usernameTaken);

    // A store row without an Auth user: Auth accepts the email, the routine refuses the name.
    await h.database.query(
      "insert into public.stores(name, username) values ('بلا مستخدم', 'orphan-name')",
    );
    const calls = h.authAdmin.calls.length;
    const orphan = await h.send(admin.jwt, "POST", "/admin/stores", {
      ...createFixture.request,
      username: "orphan-name",
    });
    await assertRefusal(orphan, refusals.usernameTaken);
    const made = h.authAdmin.calls.slice(calls);
    assertEqual(made.map((call) => call.method), ["createUser", "deleteUser"]);
    const [{ n }] = await h.database.query<{ n: number }>(
      "select count(*)::int as n from auth.users where email = 'orphan-name@stores.sadad.invalid'",
    );
    assertEqual(n, 0);
  },
);

dbTest("create store refuses bad input before calling Auth", async () => {
  const calls = h.authAdmin.calls.length;
  for (
    const body of [
      { ...createFixture.request, username: "has space" },
      { ...createFixture.request, username: "ab" },
      { ...createFixture.request, subscriptionDays: undefined },
      { ...createFixture.request, allowedDevices: 51 },
      { ...createFixture.request, permissions: { admin: true } },
      { ...createFixture.request, extra: 1 },
    ]
  ) {
    await assertRefusal(
      await h.send(admin.jwt, "POST", "/admin/stores", body),
      refusals.invalidInput,
    );
  }
  assertEqual(h.authAdmin.calls.length, calls);
});

dbTest(
  "reset password: a new temporary password with force_password_change, and the store's sessions end",
  async () => {
    const store = await h.seedStore();
    assertEqual(await liveSessions(store.userId), 1);

    const res = await h.send(
      admin.jwt,
      "POST",
      `/admin/stores/${store.storeId}/password`,
      {},
    );
    assertEqual(res.status, 200);
    const body = await res.json();
    assertEqual(body.username, store.username);

    const reset = h.authAdmin.calls.at(-1)!;
    assertEqual(reset, {
      method: "updateUserById",
      id: store.userId,
      attributes: {
        password: body.temporaryPassword,
        app_metadata: { force_password_change: true },
      },
    });
    assertEqual(await liveSessions(store.userId), 0);
    assert(
      !h.logs.some((line) => line.includes(body.temporaryPassword)),
      "the password is never logged",
    );
  },
);

dbTest(
  "reset password: when Auth refuses the update, the store row and its password stay as they were",
  async () => {
    const store = await h.seedStore();
    assertEqual(await liveSessions(store.userId), 1);
    const before = await storeRow(store.storeId);
    // The baseline's auth.users carries no password column; the metadata is what a refused
    // update must leave alone on top of the row.
    const [authBefore] = await h.database.query<{
      email: string;
      raw_app_meta_data: Record<string, unknown> | null;
    }>("select email, raw_app_meta_data from auth.users where id = $1", [
      store.userId,
    ]);

    h.authAdmin.failNext = { message: "auth is down" };
    const calls = h.authAdmin.calls.length;
    const res = await h.send(
      admin.jwt,
      "POST",
      `/admin/stores/${store.storeId}/password`,
      {},
    );
    assertEqual(res.status, 500);

    const after = await storeRow(store.storeId);
    assertEqual(
      {
        name: after.name,
        username: after.username,
        status: after.status,
        revision: after.revision,
      },
      {
        name: before.name,
        username: before.username,
        status: before.status,
        revision: before.revision,
      },
    );
    const [authAfter] = await h.database.query<typeof authBefore>(
      "select email, raw_app_meta_data from auth.users where id = $1",
      [store.userId],
    );
    assertEqual(authAfter, authBefore);
    // The routine's state stands: the sessions stay ended and the audit row is written once.
    assertEqual(await liveSessions(store.userId), 0);
    const [events] = await h.database.query<{ n: number }>(
      "select count(*)::int as n from sadid_private.control_events where store_id = $1 and kind = 'account.password-reset'",
      [store.storeId],
    );
    assertEqual(events.n, 1);
    assertEqual(
      h.authAdmin.calls.slice(calls).map((call) => call.method),
      ["updateUserById"],
    );
  },
);

dbTest(
  "an admin whose session is dead is refused before every Auth admin call",
  async () => {
    const store = await h.seedStore();
    const dead = await h.jwtFor("admin");
    await h.database.query("delete from auth.sessions where id = $1", [
      dead.sessionId,
    ]);

    const calls = h.authAdmin.calls.length;
    for (
      const [method, path, body] of [
        ["PATCH", "", { name: "بواسطة جلسة ميتة" }],
        ["POST", "/password", {}],
        ["DELETE", `?confirm=${store.username}`, undefined],
      ] as const
    ) {
      await assertRefusal(
        await h.send(
          dead.jwt,
          method,
          `/admin/stores/${store.storeId}${path}`,
          body,
        ),
        refusals.notAdmin,
      );
    }
    assertEqual(h.authAdmin.calls.length, calls);
  },
);

dbTest(
  "the request and error logs redact a temporary password even when Auth echoes it back",
  async () => {
    const store = await h.seedStore();
    const original = h.authAdmin.updateUserById.bind(h.authAdmin);
    let sent = "";
    h.authAdmin.updateUserById = (_id, attributes) => {
      sent = String(attributes.password);
      h.authAdmin.updateUserById = original;
      return Promise.resolve({
        data: { user: null },
        error: { message: `password ${sent} is too weak` },
      });
    };
    const before = h.logs.length;
    const res = await h.send(
      admin.jwt,
      "POST",
      `/admin/stores/${store.storeId}/password`,
      {},
    );
    assertEqual(res.status, 500);
    const lines = h.logs.slice(before);
    assert(sent.length === 24, "a password was sent to Auth");
    assert(lines.length === 2, "one error line and one request line");
    assert(!lines.some((line) => line.includes(sent)), "never logged");
    assert(
      lines.some((line) => line.includes("password [redacted] is too weak")),
      "redacted in place",
    );
  },
);

dbTest(
  "list stores pages by cursor and searches by name or username on the server",
  async () => {
    for (const name of ["مخبز", "صيدلية", "مخبز الحي"]) {
      const res = await h.send(admin.jwt, "POST", "/admin/stores", {
        name,
        username: `list-${crypto.randomUUID().slice(0, 8)}`,
        subscriptionMode: "permanent",
      });
      assertEqual(res.status, 201);
    }

    const first = await (await h.send(
      admin.jwt,
      "GET",
      "/admin/stores?q=%D9%85%D8%AE%D8%A8%D8%B2&limit=1",
    )).json();
    assertEqual(first.stores.map((s: { name: string }) => s.name), [
      "مخبز الحي",
    ]);
    assert(typeof first.nextCursor === "string", "a next cursor");
    assertEqual(first.stores[0].summary, {
      storeId: first.stores[0].id,
      initialized: false,
    });

    const second = await (await h.send(
      admin.jwt,
      "GET",
      `/admin/stores?q=%D9%85%D8%AE%D8%A8%D8%B2&limit=1&cursor=${first.nextCursor}`,
    )).json();
    assertEqual(second.stores.map((s: { name: string }) => s.name), ["مخبز"]);
    assertEqual(second.nextCursor, null);

    const byUsername = await (await h.send(
      admin.jwt,
      "GET",
      "/admin/stores?q=CORNER",
    )).json();
    assertEqual(
      byUsername.stores.map((s: { username: string }) => s.username),
      [
        "corner-shop",
      ],
    );

    // _ and % are plain characters, not wildcards.
    const wildcard = await (await h.send(
      admin.jwt,
      "GET",
      "/admin/stores?q=c_rner",
    )).json();
    assertEqual(wildcard.stores, []);

    await assertRefusal(
      await h.send(admin.jwt, "GET", "/admin/stores?cursor=not-a-cursor"),
      refusals.invalidInput,
    );
  },
);

dbTest("list stores carries each store's balances in cents", async () => {
  const store = await h.seedStore();
  const contact = crypto.randomUUID();
  const debt = crypto.randomUUID();
  await h.database.query(
    "insert into public.sadid_ledger_state(store_id) values ($1)",
    [store.storeId],
  );
  await h.database.query(
    "insert into public.sadid_contacts(store_id, id, name, operation_id, created_at) values ($1, $2, 'سامي', gen_random_uuid(), now())",
    [store.storeId, contact],
  );
  await h.database.query(
    "insert into public.sadid_debts(store_id, id, contact_id, direction, amount_cents, operation_id, created_at) values ($1, $2, $3, 'receivable', 1250, gen_random_uuid(), now())",
    [store.storeId, debt, contact],
  );
  const page = await (await h.send(
    admin.jwt,
    "GET",
    `/admin/stores?q=${store.username}`,
  )).json();
  const summary = page.stores[0].summary;
  assertEqual({
    initialized: summary.initialized,
    receivableCents: summary.receivableCents,
    debtTotalCents: summary.debtTotalCents,
  }, { initialized: true, receivableCents: 1250, debtTotalCents: 1250 });
});

dbTest(
  "update store settings; suspending ends the store's sessions",
  async () => {
    const store = await h.seedStore();
    const res = await h.send(
      admin.jwt,
      "PATCH",
      `/admin/stores/${store.storeId}`,
      {
        name: "اسم جديد",
        verified: true,
        debtorLimit: 50,
        permissions: { whatsapp: false },
      },
    );
    assertEqual(res.status, 200);
    const { store: updated } = await res.json();
    assertEqual({
      name: updated.name,
      verified: updated.verified,
      debtorLimit: updated.debtorLimit,
      permissions: updated.permissions,
      revision: updated.revision,
    }, {
      name: "اسم جديد",
      verified: true,
      debtorLimit: 50,
      permissions: { whatsapp: false },
      revision: 2,
    });

    assertEqual(await liveSessions(store.userId), 1);
    const frozen = await h.send(
      admin.jwt,
      "PATCH",
      `/admin/stores/${store.storeId}`,
      {
        status: "suspended",
      },
    );
    assertEqual((await frozen.json()).store.status, "suspended");
    assertEqual(await liveSessions(store.userId), 0);

    const back = await h.send(
      admin.jwt,
      "PATCH",
      `/admin/stores/${store.storeId}`,
      {
        status: "active",
      },
    );
    assertEqual((await back.json()).store.status, "active");
  },
);

dbTest(
  "update store refuses activating an expired subscription and a cap below the active devices",
  async () => {
    const store = await h.seedStore(2);
    await addInstallation(store.storeId, "active");
    await addInstallation(store.storeId, "active");
    await assertRefusal(
      await h.send(admin.jwt, "PATCH", `/admin/stores/${store.storeId}`, {
        allowedDevices: 1,
      }),
      refusals.belowActiveDevices,
    );

    await h.database.query(
      "update public.stores set status = 'suspended', subscription_mode = 'timed', subscription_expires_at = 1 where id = $1",
      [store.storeId],
    );
    await assertRefusal(
      await h.send(admin.jwt, "PATCH", `/admin/stores/${store.storeId}`, {
        status: "active",
      }),
      refusals.subscriptionInactive,
    );
    await assertRefusal(
      await h.send(admin.jwt, "PATCH", "/admin/stores/999999", {
        verified: true,
      }),
      refusals.storeNotFound,
    );
  },
);

dbTest(
  "a new username changes the Auth email; when Auth refuses, the old username comes back",
  async () => {
    const store = await h.seedStore();
    const res = await h.send(
      admin.jwt,
      "PATCH",
      `/admin/stores/${store.storeId}`,
      {
        username: "Renamed-Shop",
      },
    );
    assertEqual(res.status, 200);
    assertEqual(h.authAdmin.calls.at(-1), {
      method: "updateUserById",
      id: store.userId,
      attributes: {
        email: "renamed-shop@stores.sadad.invalid",
        email_confirm: true,
      },
    });

    h.authAdmin.failNext = { code: "email_exists", message: "email exists" };
    await assertRefusal(
      await h.send(admin.jwt, "PATCH", `/admin/stores/${store.storeId}`, {
        username: "someone-else",
      }),
      refusals.usernameTaken,
    );
    assertEqual((await storeRow(store.storeId)).username, "Renamed-Shop");
  },
);

dbTest(
  "subscription: timed activates for the days given, paused keeps the time left, extend adds to it",
  async () => {
    const store = await h.seedStore();
    const path = `/admin/stores/${store.storeId}/subscription`;
    const timed = (await (await h.send(admin.jwt, "POST", path, {
      mode: "timed",
      days: 10,
    })).json()).store;
    assertEqual([
      timed.status,
      timed.subscriptionMode,
      timed.subscriptionActive,
    ], [
      "active",
      "timed",
      true,
    ]);
    const tenDays = 10 * 86_400_000;
    assert(
      Math.abs(timed.subscriptionRemainingMs - tenDays) < 60_000,
      "about ten days left",
    );

    const paused = (await (await h.send(admin.jwt, "POST", path, {
      mode: "paused",
    })).json()).store;
    assertEqual([paused.status, paused.subscriptionActive], [
      "suspended",
      false,
    ]);
    assert(
      Math.abs(paused.subscriptionRemainingMs - tenDays) < 60_000,
      "the time left is kept",
    );
    assertEqual(await liveSessions(store.userId), 0);

    const extended = (await (await h.send(admin.jwt, "POST", path, {
      mode: "timed",
      days: 5,
      extend: true,
    })).json()).store;
    assert(
      Math.abs(extended.subscriptionRemainingMs - 15 * 86_400_000) < 60_000,
      "five days added to the ten kept",
    );

    const permanent = (await (await h.send(admin.jwt, "POST", path, {
      mode: "permanent",
    })).json()).store;
    assertEqual(
      [permanent.subscriptionMode, permanent.subscriptionExpiresAt],
      ["permanent", null],
    );

    await assertRefusal(
      await h.send(admin.jwt, "POST", path, { mode: "timed" }),
      refusals.invalidInput,
    );
  },
);

dbTest(
  "archive revokes devices; an archived store refuses changes until unarchived",
  async () => {
    const store = await h.seedStore();
    const device = await addInstallation(store.storeId, "active");
    const archive = await h.send(
      admin.jwt,
      "POST",
      `/admin/stores/${store.storeId}/actions/archive`,
      { reason: "أغلق المتجر" },
    );
    assertEqual((await archive.json()).result, {
      storeId: store.storeId,
      action: "archive",
    });
    const [{ status }] = await h.database.query<{ status: string }>(
      "select status from sadid_private.installations where id = $1",
      [device],
    );
    assertEqual(status, "revoked");

    for (
      const [method, path, body] of [
        ["PATCH", "", { verified: true }],
        ["POST", "/subscription", { mode: "permanent" }],
        ["POST", "/password", {}],
      ] as const
    ) {
      await assertRefusal(
        await h.send(
          admin.jwt,
          method,
          `/admin/stores/${store.storeId}${path}`,
          body,
        ),
        refusals.storeArchived,
      );
    }

    const listed = await (await h.send(
      admin.jwt,
      "GET",
      `/admin/stores?q=${store.username}`,
    )).json();
    assertEqual(listed.stores, []);
    const archived = await (await h.send(
      admin.jwt,
      "GET",
      `/admin/stores?q=${store.username}&includeArchived=true`,
    )).json();
    assertEqual(archived.stores[0].archived, true);

    await assertRefusal(
      await h.send(
        admin.jwt,
        "POST",
        `/admin/stores/${store.storeId}/actions/end-sessions`,
        {},
      ),
      refusals.invalidInput,
    );
    const unarchive = await h.send(
      admin.jwt,
      "POST",
      `/admin/stores/${store.storeId}/actions/unarchive`,
      { reason: "عاد المتجر" },
    );
    assertEqual(unarchive.status, 200);
    assertEqual((await storeRow(store.storeId)).status, "suspended");
  },
);

dbTest(
  "permanent delete needs the username and no history, and removes the Auth user",
  async () => {
    const store = await h.seedStore();
    await assertRefusal(
      await h.send(
        admin.jwt,
        "DELETE",
        `/admin/stores/${store.storeId}?confirm=wrong`,
      ),
      refusals.deleteNotConfirmed,
    );

    const used = await h.seedStore();
    await addInstallation(used.storeId, "pending");
    await assertRefusal(
      await h.send(
        admin.jwt,
        "DELETE",
        `/admin/stores/${used.storeId}?confirm=${used.username}`,
      ),
      refusals.deleteWithHistory,
    );

    const res = await h.send(
      admin.jwt,
      "DELETE",
      `/admin/stores/${store.storeId}?confirm=${store.username}`,
    );
    assertEqual(await res.json(), { ok: true, deleted: true });
    assertEqual(h.authAdmin.calls.at(-1), {
      method: "deleteUser",
      id: store.userId,
    });
    assertEqual(await storeRow(store.storeId), undefined);
    const [{ n }] = await h.database.query<{ n: number }>(
      "select count(*)::int as n from auth.users where id = $1",
      [store.userId],
    );
    assertEqual(n, 0);
  },
);

dbTest("store detail and overview", async () => {
  const store = await h.seedStore(3);
  await addInstallation(store.storeId, "pending");
  const detail = (await (await h.send(
    admin.jwt,
    "GET",
    `/admin/stores/${store.storeId}`,
  )).json()).detail;
  assertEqual({
    username: detail.store.username,
    allowedDevices: detail.store.allowedDevices,
    pendingDevices: detail.store.pendingDevices,
    canDelete: detail.canDelete,
    installations: detail.installations.length,
  }, {
    username: store.username,
    allowedDevices: 3,
    pendingDevices: 1,
    canDelete: false,
    installations: 1,
  });

  const { overview } = await (await h.send(admin.jwt, "GET", "/admin/overview"))
    .json();
  const [counts] = await h.database.query<Record<string, number>>(
    `select count(*) filter (where status <> 'deleted')::int as stores,
            count(*) filter (where status = 'active')::int as active
     from public.stores`,
  );
  assertEqual([overview.stores, overview.active], [
    counts.stores,
    counts.active,
  ]);
  assert(overview.pendingDevices >= 1, "pending devices are counted");
});
