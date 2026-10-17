// One app on the real baseline for an admin test file, with a recording Auth admin stand-in.
import { createApp } from "../app.ts";
import { errorMessages } from "../errors.ts";
import { TestDatabase } from "../test_db.ts";
import {
  assertEqual,
  createTestKeyPair,
  makeSignedJwt,
} from "../test_support.ts";
import type { AuthAdmin, AuthUserResult } from "./common.ts";

// pglite keeps its worker and timers for the whole file.
export const dbTest = (name: string, fn: () => Promise<void>) =>
  Deno.test({ name, fn, sanitizeOps: false, sanitizeResources: false });

export interface AuthCall {
  method: "createUser" | "updateUserById" | "deleteUser";
  id?: string;
  attributes?: Record<string, unknown>;
}

/** Auth admin API over the stubbed auth.users table; failNext makes the next call return an Auth error. */
export class FakeAuthAdmin implements AuthAdmin {
  readonly calls: AuthCall[] = [];
  failNext: { code?: string; message: string } | null = null;

  constructor(private readonly database: TestDatabase) {}

  private takeFailure(): AuthUserResult | null {
    const failure = this.failNext;
    this.failNext = null;
    return failure ? { data: { user: null }, error: failure } : null;
  }

  async createUser(attributes: {
    email: string;
    password: string;
    email_confirm: boolean;
    app_metadata: Record<string, unknown>;
  }): Promise<AuthUserResult> {
    this.calls.push({ method: "createUser", attributes });
    const failure = this.takeFailure();
    if (failure) return failure;
    const taken = await this.database.query(
      "select 1 from auth.users where lower(email) = lower($1)",
      [attributes.email],
    );
    if (taken.length) {
      return {
        data: { user: null },
        error: { code: "email_exists", message: "email exists" },
      };
    }
    const id = crypto.randomUUID();
    await this.database.query(
      "insert into auth.users(id, email, raw_app_meta_data) values ($1, $2, $3)",
      [id, attributes.email, JSON.stringify(attributes.app_metadata)],
    );
    return { data: { user: { id } }, error: null };
  }

  async updateUserById(
    id: string,
    attributes: Record<string, unknown>,
  ): Promise<AuthUserResult> {
    this.calls.push({ method: "updateUserById", id, attributes });
    const failure = this.takeFailure();
    if (failure) return failure;
    if (typeof attributes.email === "string") {
      await this.database.query(
        "update auth.users set email = $2 where id = $1",
        [
          id,
          attributes.email,
        ],
      );
    }
    if (attributes.app_metadata) {
      await this.database.query(
        "update auth.users set raw_app_meta_data = raw_app_meta_data || $2::jsonb where id = $1",
        [id, JSON.stringify(attributes.app_metadata)],
      );
    }
    return { data: { user: { id } }, error: null };
  }

  async deleteUser(id: string): Promise<{ error: unknown }> {
    this.calls.push({ method: "deleteUser", id });
    const failure = this.takeFailure();
    if (failure) return { error: failure };
    await this.database.query("delete from auth.users where id = $1", [id]);
    return { error: null };
  }
}

export async function adminHarness() {
  const database = await TestDatabase.create();
  const keys = await createTestKeyPair();
  const authAdmin = new FakeAuthAdmin(database);
  const logs: string[] = [];
  const app = createApp({
    verifyClaims: keys.verifyClaims,
    logRequest: (line) => logs.push(line),
    logError: (entry) => logs.push(JSON.stringify(entry)),
    mobile: {
      serviceDb: database.serviceClient(),
      userDb: (jwt, capability) => database.userClient(jwt, capability),
    },
    admin: {
      userDb: (jwt) => database.userClient(jwt),
      serviceDb: database.serviceClient(),
      authAdmin,
    },
  });

  /** A JWT for an Auth user with a live session; role admin, support or store. */
  async function jwtFor(
    role: "admin" | "support" | "store",
    options: { userId?: string; forcePasswordChange?: boolean } = {},
  ) {
    const userId = options.userId ?? crypto.randomUUID();
    const sessionId = crypto.randomUUID();
    await database.query(
      "insert into auth.sessions(id, user_id) values ($1, $2)",
      [sessionId, userId],
    );
    const jwt = await makeSignedJwt(keys.pair.privateKey, keys.kid, {
      sub: userId,
      session_id: sessionId,
      email: `${role}-${userId.slice(0, 8)}@sadad.invalid`,
      app_metadata: {
        role,
        ...(options.forcePasswordChange ? { force_password_change: true } : {}),
      },
    });
    return { jwt, userId, sessionId };
  }

  let storeCount = 0;
  /** A store linked to an Auth user with a live session, created straight in the database. */
  async function seedStore(allowedDevices = 1) {
    storeCount += 1;
    const store = await database.createStore(
      `shop-${storeCount}`,
      allowedDevices,
    );
    return { ...store, username: `shop-${storeCount}` };
  }

  async function send(
    jwt: string | null,
    method: string,
    path: string,
    body?: unknown,
  ): Promise<Response> {
    return await app.request(
      `https://test.invalid/functions/v1/api/v1${path}`,
      {
        method,
        headers: {
          ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
          "content-type": "application/json",
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      },
    );
  }

  return { database, app, authAdmin, logs, jwtFor, seedStore, send };
}

export async function assertRefusal(
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

/** Debt, payment and adjustment rows for one store, inserted as the superuser. */
export async function seedLedger(
  database: TestDatabase,
  storeId: number,
  rows: {
    contacts: { id: string; name: string; phone?: string; at: string }[];
    debts?: {
      id: string;
      contactId: string;
      direction: "receivable" | "payable";
      cents: number;
      note?: string;
      at: string;
    }[];
    payments?: {
      id: string;
      debtId: string;
      cents: number;
      reversed?: boolean;
      at: string;
    }[];
    corrections?: { debtId: string; cents: number; previousCents: number }[];
  },
) {
  const op = () => crypto.randomUUID();
  await database.query(
    "insert into public.sadid_ledger_state(store_id) values ($1) on conflict do nothing",
    [storeId],
  );
  for (const c of rows.contacts) {
    await database.query(
      "insert into public.sadid_contacts(store_id, id, name, phone, operation_id, created_at) values ($1, $2, $3, $4, $5, $6)",
      [storeId, c.id, c.name, c.phone ?? "", op(), c.at],
    );
  }
  for (const d of rows.debts ?? []) {
    await database.query(
      "insert into public.sadid_debts(store_id, id, contact_id, direction, amount_cents, note, operation_id, created_at) values ($1, $2, $3, $4, $5, $6, $7, $8)",
      [
        storeId,
        d.id,
        d.contactId,
        d.direction,
        d.cents,
        d.note ?? "",
        op(),
        d.at,
      ],
    );
  }
  for (const p of rows.payments ?? []) {
    await database.query(
      "insert into public.sadid_payments(store_id, id, debt_id, amount_cents, method, operation_id, created_at) values ($1, $2, $3, $4, 'cash', $5, $6)",
      [storeId, p.id, p.debtId, p.cents, op(), p.at],
    );
    if (p.reversed) {
      await database.query(
        "insert into public.sadid_reversals(store_id, id, payment_id, reason, operation_id) values ($1, $2, $3, 'خطأ', $4)",
        [storeId, crypto.randomUUID(), p.id, op()],
      );
    }
  }
  for (const [index, a] of (rows.corrections ?? []).entries()) {
    await database.query(
      "insert into public.sadid_adjustments(store_id, id, debt_id, amount_cents, previous_amount_cents, version, reason, operation_id) values ($1, $2, $3, $4, $5, $6, 'تصحيح', $7)",
      [
        storeId,
        crypto.randomUUID(),
        a.debtId,
        a.cents,
        a.previousCents,
        index + 2,
        op(),
      ],
    );
  }
}
