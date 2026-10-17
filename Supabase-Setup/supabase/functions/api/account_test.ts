import { assert, assertEqual } from "./test_support.ts";
import { adminHarness, assertRefusal, dbTest } from "./admin/test_admin.ts";

const CURRENT = "Temp-Pass-0001";
const NEW_PASSWORD = "New-Secure-Pass-2026";

const checked: { email: string; password: string }[] = [];
const h = await adminHarness({
  verifyPassword: (email, password) => {
    checked.push({ email, password });
    return Promise.resolve(password === CURRENT);
  },
});

const enrollBody = {
  publicKey: { kty: "EC", crv: "P-256", x: "A".repeat(43), y: "B".repeat(43) },
  label: "Redmi M2101K7BI",
  staffName: "سامي",
};

/** A store whose Auth user carries the temporary-password flag, as create and reset leave it. */
async function flaggedStore() {
  const store = await h.seedStore();
  await h.database.query(
    "update auth.users set raw_app_meta_data = $2 where id = $1",
    [
      store.userId,
      JSON.stringify({ role: "store", force_password_change: true }),
    ],
  );
  const token = await h.jwtFor("store", {
    userId: store.userId,
    forcePasswordChange: true,
  });
  return { ...store, ...token };
}

async function appMetadata(userId: string) {
  const [row] = await h.database.query<{ meta: Record<string, unknown> }>(
    "select raw_app_meta_data as meta from auth.users where id = $1",
    [userId],
  );
  return row.meta;
}

/** Auth calls and password checks made by fn. */
async function during(fn: () => Promise<void>) {
  const calls = h.authAdmin.calls.length;
  const checks = checked.length;
  await fn();
  return {
    calls: h.authAdmin.calls.slice(calls),
    checks: checked.slice(checks),
  };
}

const change = (jwt: string | null, body: unknown) =>
  h.send(jwt, "POST", "/me/password", body);

dbTest(
  "a flagged store proves its current password, sets a new one and the flag is cleared with role kept",
  async () => {
    const store = await flaggedStore();
    let res!: Response;
    const seen = await during(async () => {
      res = await change(store.jwt, {
        currentPassword: CURRENT,
        newPassword: NEW_PASSWORD,
      });
    });
    assertEqual(res.status, 200);
    assertEqual(await res.json(), { ok: true, forcePasswordChange: false });
    assertEqual(seen.checks, [{
      email: `store-${store.userId.slice(0, 8)}@sadad.invalid`,
      password: CURRENT,
    }]);
    assertEqual(seen.calls, [{
      method: "updateUserById",
      id: store.userId,
      attributes: {
        password: NEW_PASSWORD,
        app_metadata: { force_password_change: false },
      },
    }]);
    const meta = await appMetadata(store.userId);
    assertEqual(meta, { role: "store", force_password_change: false });

    // A refreshed JWT carries the updated metadata; the old one keeps the flag until it expires.
    const refreshed = await h.jwtFor("store", {
      userId: store.userId,
      forcePasswordChange: meta.force_password_change === true,
    });
    await assertRefusal(
      await h.send(refreshed.jwt, "POST", "/mobile/enroll", enrollBody),
      { status: 401, code: "invalid_proof" },
    );
    await assertRefusal(
      await h.send(store.jwt, "POST", "/mobile/enroll", enrollBody),
      { status: 403, code: "password_change_required" },
    );
  },
);

dbTest("a wrong current password is refused and nothing changes", async () => {
  const store = await flaggedStore();
  const seen = await during(async () => {
    await assertRefusal(
      await change(store.jwt, {
        currentPassword: "Not-The-Password",
        newPassword: NEW_PASSWORD,
      }),
      { status: 403, code: "current_password_incorrect" },
    );
  });
  assertEqual(seen.checks.length, 1);
  assertEqual(seen.calls, []);
  assertEqual((await appMetadata(store.userId)).force_password_change, true);
});

dbTest(
  "a new password of 11 or 129 characters, or equal to the current one, is refused before any check",
  async () => {
    const store = await flaggedStore();
    for (
      const newPassword of ["a".repeat(11), "a".repeat(129), CURRENT.repeat(2)]
    ) {
      const currentPassword = newPassword === CURRENT.repeat(2)
        ? newPassword
        : CURRENT;
      const seen = await during(async () => {
        await assertRefusal(
          await change(store.jwt, { currentPassword, newPassword }),
          { status: 400, code: "invalid_input" },
        );
      });
      assertEqual(seen, { calls: [], checks: [] });
    }
  },
);

dbTest("no JWT, or a JWT without an email claim, is refused", async () => {
  const body = { currentPassword: CURRENT, newPassword: NEW_PASSWORD };
  const store = await h.seedStore();
  const noEmail = await h.jwtFor("store", {
    userId: store.userId,
    forcePasswordChange: true,
    email: null,
  });
  const seen = await during(async () => {
    await assertRefusal(await change(null, body), {
      status: 401,
      code: "authorization_required",
    });
    await assertRefusal(await change(noEmail.jwt, body), {
      status: 401,
      code: "authorization_required",
    });
  });
  assertEqual(seen, { calls: [], checks: [] });
});

dbTest("an admin with the flag may change its password too", async () => {
  const admin = await h.jwtFor("admin", { forcePasswordChange: true });
  const res = await change(admin.jwt, {
    currentPassword: CURRENT,
    newPassword: NEW_PASSWORD,
  });
  assertEqual(res.status, 200);
  assertEqual(await res.json(), { ok: true, forcePasswordChange: false });
});

dbTest("an Auth failure is a 500 and the flag stays set", async () => {
  const store = await flaggedStore();
  // An Auth error that echoes the password must still not reach a log line.
  h.authAdmin.failNext = { message: `rejected ${NEW_PASSWORD}` };
  const res = await change(store.jwt, {
    currentPassword: CURRENT,
    newPassword: NEW_PASSWORD,
  });
  assertEqual(res.status, 500);
  assertEqual((await res.json()).code, "internal_error");
  assertEqual((await appMetadata(store.userId)).force_password_change, true);
});

dbTest("no request or error log line holds a password", () => {
  assert(h.logs.length > 0, "the routes above were logged");
  for (const line of h.logs) {
    assert(
      !line.includes(CURRENT) && !line.includes(NEW_PASSWORD),
      `a log line holds a password: ${line}`,
    );
  }
  assert(
    h.logs.some((line) => line.includes("[redacted]")),
    "the Auth error was logged with the password redacted",
  );
  return Promise.resolve();
});
