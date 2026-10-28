import { assertEqual } from "../test_support.ts";
import { adminHarness, assertRefusal, dbTest } from "./test_admin.ts";

const h = await adminHarness();
const claim = (jwt: string) => h.send(jwt, "POST", "/admin/session");
const release = (jwt: string) => h.send(jwt, "DELETE", "/admin/session");

dbTest(
  "a second session on the same account is refused on claim and on every admin read",
  async () => {
    const user = crypto.randomUUID();
    const first = await h.jwtFor("admin", { userId: user });
    const second = await h.jwtFor("admin", { userId: user });

    assertEqual((await claim(first.jwt)).status, 200);
    assertEqual((await h.send(first.jwt, "GET", "/admin/stores")).status, 200);
    assertEqual((await claim(first.jwt)).status, 200);

    await assertRefusal(await claim(second.jwt), {
      status: 409,
      code: "session_in_use",
    });
    // The refusals hold for reads and writes alike: a session that lost the account does nothing.
    await assertRefusal(await h.send(second.jwt, "GET", "/admin/stores"), {
      status: 409,
      code: "session_in_use",
    });
    await assertRefusal(await h.send(second.jwt, "GET", "/admin/overview"), {
      status: 409,
      code: "session_in_use",
    });

    // Signing out frees the account at once, and the second session takes it.
    assertEqual((await release(first.jwt)).status, 200);
    assertEqual((await claim(second.jwt)).status, 200);
    assertEqual((await h.send(second.jwt, "GET", "/admin/stores")).status, 200);
    await assertRefusal(await claim(first.jwt), {
      status: 409,
      code: "session_in_use",
    });
  },
);

dbTest(
  "a lease left silent for ten minutes is taken over, and support is its own account",
  async () => {
    const user = crypto.randomUUID();
    const first = await h.jwtFor("admin", { userId: user });
    const second = await h.jwtFor("admin", { userId: user });
    assertEqual((await claim(first.jwt)).status, 200);

    await h.database.query(
      "update sadid_private.admin_session_lease set seen_at = now() - interval '11 minutes' where user_id = $1",
      [user],
    );
    assertEqual((await claim(second.jwt)).status, 200);
    await assertRefusal(await claim(first.jwt), {
      status: 409,
      code: "session_in_use",
    });

    // A session that does not hold the seat releases nothing, and support has its own seat.
    const outside = await h.jwtFor("admin", { userId: user });
    assertEqual((await release(outside.jwt)).status, 200);
    assertEqual((await h.send(second.jwt, "GET", "/admin/stores")).status, 200);

    const support = await h.jwtFor("support");
    assertEqual((await claim(support.jwt)).status, 200);
  },
);

dbTest(
  "the lease answers last: a store token and a forced password change keep their own refusal",
  async () => {
    const store = await h.jwtFor("store");
    await assertRefusal(await claim(store.jwt), {
      status: 403,
      code: "admin_authorization_required",
    });

    const forced = await h.jwtFor("admin", { forcePasswordChange: true });
    await assertRefusal(await claim(forced.jwt), {
      status: 403,
      code: "password_change_required",
    });
    // A forced change has claimed no seat, and its sign-out is answered by the same refusal.
    await assertRefusal(await release(forced.jwt), {
      status: 403,
      code: "password_change_required",
    });
  },
);
