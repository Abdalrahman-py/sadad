// The baseline schema in pglite behind a minimal PostgREST stand-in, so the api's real
// supabase-js clients call the real routines. Only POST /rest/v1/rpc/<fn> is served.
import { PGlite, type Transaction } from "@electric-sql/pglite";
import { createClient } from "@supabase/supabase-js";

export const TEST_URL = "https://db.test.invalid";
export const SERVICE_KEY = "test-service-role-key";
export const PUBLISHABLE_KEY = "sb_publishable_test";

const AUTH_STUB = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create function public.rls_auto_enable() returns event_trigger language plpgsql as $$ begin end $$;
  create schema auth;
  create table auth.users(id uuid primary key, email text, raw_app_meta_data jsonb default '{}'::jsonb);
  create table auth.sessions(id uuid primary key, user_id uuid, not_after timestamptz);
  alter table auth.sessions enable row level security;
  create function auth.uid() returns uuid language sql stable as $$
    select (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')::uuid
  $$;
  create function auth.jwt() returns jsonb language sql stable as $$
    select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb)
  $$;
  grant usage on schema auth to anon, authenticated, service_role;
  grant execute on function auth.uid(), auth.jwt() to anon, authenticated, service_role;
  grant usage on schema public to authenticated, service_role;
`;

interface RpcSignature {
  names: string[];
  types: string[];
}

function decodeJwtPayload(jwt: string): Record<string, unknown> {
  const part = jwt.split(".")[1] ?? "";
  const json = atob(part.replace(/-/g, "+").replace(/_/g, "/"));
  return JSON.parse(new TextDecoder().decode(
    Uint8Array.from(json, (ch) => ch.charCodeAt(0)),
  ));
}

function toParam(value: unknown, type = ""): unknown {
  if (value === null || value === undefined) return null;
  if (Array.isArray(value) && type.endsWith("[]")) {
    return `{${value.join(",")}}`;
  }
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export class TestDatabase {
  readonly db: PGlite;
  private readonly signatures = new Map<string, RpcSignature>();

  private constructor(db: PGlite) {
    this.db = db;
  }

  static async create(): Promise<TestDatabase> {
    const db = new PGlite();
    const dir = new URL("../../migrations/", import.meta.url);
    // Every migration in order, as production is built: the frozen baseline first, then each
    // later file. Skipping them would let a route call a routine the deployed schema has.
    const files: string[] = [];
    for await (const entry of Deno.readDir(dir)) {
      if (entry.name.endsWith(".sql")) files.push(entry.name);
    }
    files.sort();
    let sql = AUTH_STUB;
    for (const file of files) {
      sql += await Deno.readTextFile(new URL(file, dir));
    }
    await db.exec(sql);
    return new TestDatabase(db);
  }

  /** Superuser query for seeding and assertions. */
  async query<T>(sql: string, params: unknown[] = []): Promise<T[]> {
    return (await this.db.query<T>(sql, params)).rows;
  }

  /** The fetch supabase-js uses: PostgREST's role, claims and headers, one transaction per call. */
  readonly fetch = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const request = new Request(input, init);
    const match = new URL(request.url).pathname.match(
      /^\/rest\/v1\/rpc\/([a-z_]+)$/,
    );
    if (request.method !== "POST" || !match) {
      return Response.json({ message: "not served" }, { status: 404 });
    }
    const fn = match[1];
    const signature = await this.signature(fn);
    if (!signature) {
      return Response.json({ code: "PGRST202", message: "no function" }, {
        status: 404,
      });
    }
    const raw = await request.text();
    const args = raw ? JSON.parse(raw) as Record<string, unknown> : {};

    const bearer = (request.headers.get("authorization") ?? "").replace(
      /^Bearer\s+/i,
      "",
    );
    const isService = request.headers.get("apikey") === SERVICE_KEY &&
      bearer === SERVICE_KEY;
    const role = isService ? "service_role" : "authenticated";
    const claims = isService ? { role } : decodeJwtPayload(bearer);
    const headers: Record<string, string> = {};
    request.headers.forEach((value, key) => {
      headers[key] = value;
    });

    const named: string[] = [];
    const params: unknown[] = [];
    for (const [name, value] of Object.entries(args)) {
      const index = signature.names.indexOf(name);
      if (index < 0) {
        return Response.json({ code: "PGRST202", message: `no arg ${name}` }, {
          status: 404,
        });
      }
      params.push(toParam(value, signature.types[index]));
      named.push(`${name} => $${params.length}::${signature.types[index]}`);
    }

    try {
      const result = await this.db.transaction(async (tx: Transaction) => {
        await tx.exec(`set local role ${role}`);
        await tx.query(
          "select set_config('request.jwt.claims', $1, true), set_config('request.headers', $2, true)",
          [JSON.stringify(claims), JSON.stringify(headers)],
        );
        const rows = await tx.query<{ result: unknown }>(
          `select public.${fn}(${named.join(", ")}) as result`,
          params,
        );
        return rows.rows[0]?.result ?? null;
      });
      return Response.json(
        typeof result === "bigint" ? Number(result) : result,
      );
    } catch (error) {
      const e = error as { code?: string; message?: string };
      return Response.json(
        { code: e.code, message: e.message, details: null, hint: null },
        { status: 400 },
      );
    }
  };

  private async signature(fn: string): Promise<RpcSignature | null> {
    const known = this.signatures.get(fn);
    if (known) return known;
    const rows = await this.query<{ names: string[]; types: string[] }>(
      `select coalesce(p.proargnames, '{}') as names,
              array(select format_type(t, null) from unnest(p.proargtypes) t) as types
       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname = $1`,
      [fn],
    );
    if (rows.length !== 1) return null;
    const signature = rows[0];
    this.signatures.set(fn, signature);
    return signature;
  }

  /** The service-role client the api uses for installation keys and capabilities. */
  serviceClient() {
    return createClient(TEST_URL, SERVICE_KEY, {
      global: { fetch: this.fetch },
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }

  /** A client acting as one JWT, as index.ts builds it. */
  userClient(jwt: string, capability?: string) {
    return createClient(TEST_URL, PUBLISHABLE_KEY, {
      global: {
        fetch: this.fetch,
        headers: {
          Authorization: `Bearer ${jwt}`,
          ...(capability ? { "x-sadad-capability": capability } : {}),
        },
      },
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }

  /** An Auth user with a live session, linked to a new store. */
  async createStore(
    username: string,
    allowedDevices = 1,
  ): Promise<{ userId: string; sessionId: string; storeId: number }> {
    const userId = crypto.randomUUID();
    const sessionId = crypto.randomUUID();
    await this.query("insert into auth.users(id, email) values ($1, $2)", [
      userId,
      `${username}@stores.sadad.invalid`,
    ]);
    await this.query("insert into auth.sessions(id, user_id) values ($1, $2)", [
      sessionId,
      userId,
    ]);
    const [{ id }] = await this.query<{ id: number }>(
      "select public.sadid_create_store($1, $2, $3) as id",
      [userId, username, username],
    );
    await this.query(
      "update public.stores set allowed_devices = $1 where id = $2",
      [
        allowedDevices,
        id,
      ],
    );
    return { userId, sessionId, storeId: Number(id) };
  }

  /** An admin-only routine, called directly with an admin JWT's claims. */
  async asAdmin<T>(fn: string, args: unknown[]): Promise<T> {
    const adminId = crypto.randomUUID();
    const sessionId = crypto.randomUUID();
    await this.query("insert into auth.sessions(id, user_id) values ($1, $2)", [
      sessionId,
      adminId,
    ]);
    return await this.db.transaction(async (tx: Transaction) => {
      await tx.exec("set local role authenticated");
      await tx.query("select set_config('request.jwt.claims', $1, true)", [
        JSON.stringify({
          sub: adminId,
          session_id: sessionId,
          email: "admin@sadad.invalid",
          app_metadata: { role: "admin" },
        }),
      ]);
      const placeholders = args.map((_, i) => `$${i + 1}`).join(", ");
      const rows = await tx.query<{ result: T }>(
        `select public.${fn}(${placeholders}) as result`,
        args.map((arg) => toParam(arg)),
      );
      return rows.rows[0].result;
    });
  }
}
