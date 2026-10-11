import { randomBytes, scrypt, timingSafeEqual, createHash } from "node:crypto";
import { Buffer } from "node:buffer";

type Json = Record<string, any>;
type Principal = { kind: "admin" | "store"; token: string; session: Json; user: Json; device: Json | null };

const SESSION_MS = 12 * 60 * 60 * 1000;
const LOGIN_WINDOW_MS = 15 * 60_000;
const LOGIN_BLOCK_MS = 15 * 60_000;
const ADMIN_PASSWORD_MIN = 12;
const PASSWORD_MAX = 128;

class HttpError extends Error {
  status: number;
  extra: Json;
  constructor(status: number, message: string, extra: Json = {}) { super(message); this.status = status; this.extra = extra; }
}

function text(value: unknown, max = 250): string { return String(value ?? "").trim().slice(0, max); }
function dataOrThrow<T = any>(result: { data: T; error: any }): T {
  if (result.error) throw result.error;
  return result.data;
}
function hashToken(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function bearer(request: Request): string {
  const match = (request.headers.get("authorization") || "").match(/^Bearer\s+(.+)$/i);
  return match ? match[1] : "";
}
function passwordSalt(stored: string | null = null): { salt: string; rawSalt: string; N: number } {
  const encoded = String(stored || "").match(/^scrypt\$(\d{3,8})\$([0-9a-f]{32})$/i);
  if (encoded) return { salt: stored!, rawSalt: encoded[2], N: Number(encoded[1]) };
  const rawSalt = stored || randomBytes(16).toString("hex");
  const configured = Number(Deno.env.get("PASSWORD_SCRYPT_N")) || 32768;
  const N = stored ? 32768 : Math.max(1024, Math.min(32768, 2 ** Math.floor(Math.log2(configured))));
  return { salt: N === 32768 ? rawSalt : `scrypt$${N}$${rawSalt}`, rawSalt, N };
}
async function passwordHash(password: string, storedSalt: string | null = null): Promise<{ salt: string; hash: string }> {
  const params = passwordSalt(storedSalt);
  const key = await new Promise<Buffer>((resolve, reject) => {
    scrypt(String(password), params.rawSalt, 64, { N: params.N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }, (error, derived) => error ? reject(error) : resolve(derived));
  });
  return { salt: params.salt, hash: key.toString("hex") };
}
function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(String(a || ""), "hex");
  const right = Buffer.from(String(b || ""), "hex");
  return left.length === right.length && left.length > 0 && timingSafeEqual(left, right);
}
function loginBucketKeys(request: Request, identity: string): Array<{ key: string; limit: number }> {
  // Supabase's gateway supplies forwarded client IP metadata. The account bucket remains
  // the effective limit if a caller can alter an IP header.
  const forwarded = text(request.headers.get("x-forwarded-for")?.split(",")[0], 64) || "unknown";
  const normalized = text(identity, 100).normalize("NFKC").toLocaleLowerCase("en-US");
  return [
    { key: hashToken(`login-ip:${forwarded}`), limit: 60 },
    { key: hashToken(`login-account:${normalized}`), limit: 10 },
  ];
}
async function bodyJson(request: Request): Promise<Json> {
  const raw = await request.text();
  if (raw.length > 3_000_000) throw new HttpError(413, "الطلب أكبر من الحد المسموح.");
  if (!raw) return {};
  try { const value = JSON.parse(raw); if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(); return value; }
  catch { throw new HttpError(400, "صيغة البيانات غير صحيحة."); }
}

let dummyCredentialSalt = "";
function dummySalt(): string { if (!dummyCredentialSalt) dummyCredentialSalt = passwordSalt().salt; return dummyCredentialSalt; }
function safeUsername(value: string): boolean { return /^[\p{L}\p{N}._-]{3,80}$/u.test(value); }

/** The admin identity surface moved out of sadad-api: bootstrap, login, logout, and the session
 * reads the dashboard already calls. Success answers through the ledger's own `respond`; a failure
 * keeps sadad-api's `{ ok: false, message }` envelope, status and message text. */
export function makeAdmin({ db, respond, now = Date.now }: { db: any; respond: (status: number, data: unknown) => Response; now?: () => number }) {
  const ok = (data: Json = {}): Response => respond(200, { ok: true, ...data });
  function fail(error: unknown): Response {
    if (error instanceof HttpError) return respond(error.status, { ok: false, message: error.message, ...error.extra });
    console.error("Sadad Edge Function error", error);
    return respond(500, { ok: false, message: "حدث خطأ داخلي." });
  }
  async function checkLoginLimit(request: Request, identity: string): Promise<void> {
    const keys = loginBucketKeys(request, identity).map(item => item.key);
    const rows = dataOrThrow(await db.from("login_rate_limits").select("bucket_key,blocked_until").in("bucket_key", keys));
    if (rows.some((row: Json) => Number(row.blocked_until) > now())) throw new HttpError(429, "محاولات دخول كثيرة. انتظر قليلًا ثم حاول مجددًا.");
  }
  async function recordLoginFailure(request: Request, identity: string): Promise<void> {
    const at = now();
    for (const bucket of loginBucketKeys(request, identity)) {
      dataOrThrow(await db.rpc("sadad_record_login_failure", {
        p_bucket_key: bucket.key,
        p_failure_limit: bucket.limit,
        p_now: at,
        p_window_ms: LOGIN_WINDOW_MS,
        p_block_ms: LOGIN_BLOCK_MS,
      }));
    }
    await db.from("login_rate_limits").delete().lt("window_started_at", at - 24 * 60 * 60_000).lt("blocked_until", at);
  }
  async function clearLoginFailures(request: Request, identity: string): Promise<void> {
    const key = loginBucketKeys(request, identity)[1].key;
    dataOrThrow(await db.from("login_rate_limits").delete().eq("bucket_key", key));
  }
  async function makeSession(kind: "admin" | "store", principalId: number, deviceId: string | null = null): Promise<Json> {
    const token = randomBytes(32).toString("base64url");
    const expiresAt = now() + SESSION_MS;
    dataOrThrow(await db.from("sessions").insert({ token_hash: hashToken(token), kind, principal_id: principalId, device_id: deviceId, expires_at: expiresAt, created_at: now() }));
    return { token, expiresAt };
  }
  // Only the admin branch of sadad-api's requireSession moves here; the store branch stays with
  // the legacy mobile routes it belongs to.
  async function requireSession(request: Request, kind: "admin"): Promise<Principal> {
    const token = bearer(request);
    if (!token) throw new HttpError(401, "سجّل الدخول للمتابعة.");
    const session = dataOrThrow(await db.from("sessions").select("*").eq("token_hash", hashToken(token)).eq("kind", kind).gt("expires_at", now()).maybeSingle());
    if (!session) throw new HttpError(401, "انتهت الجلسة. سجّل الدخول من جديد.");
    const user = dataOrThrow(await db.from("admins").select("id,username,force_password_change").eq("id", session.principal_id).maybeSingle());
    if (!user) throw new HttpError(401, "الحساب غير موجود.");
    return { kind, token, session, user, device: null };
  }
  async function findAccount(table: "admins" | "stores", username: string): Promise<Json | null> {
    if (!safeUsername(username)) return null;
    return dataOrThrow(await db.from(table).select("*").ilike("username", username).maybeSingle());
  }
  async function countAdmins(): Promise<number> {
    const result = await db.from("admins").select("id", { count: "exact", head: true });
    if (result.error) throw result.error;
    return result.count || 0;
  }
  /** Serves only the six identity routes; any other path returns null so the ledger keeps owning
   * /admin/* and the signed /mobile/v3/* surface. A thrown HttpError is answered here with its own
   * status and message, so the ledger's catch-all never rewrites it as 500/503. */
  async function route(request: Request, pathname: string): Promise<Response | null> {
    try {
      if (request.method === "GET" && pathname === "/setup/status") return ok({ bootstrapRequired: (await countAdmins()) === 0 });
      if (request.method === "POST" && pathname === "/setup/admin") {
        await checkLoginLimit(request, "admin-setup");
        const body = await bodyJson(request);
        if (await countAdmins() > 0) throw new HttpError(409, "تم إنشاء مدير النظام مسبقًا.");
        const configuredKey = Deno.env.get("INITIAL_ADMIN_KEY") || "";
        const providedHash = hashToken(text(body.setupKey, 200));
        const configuredHash = hashToken(configuredKey);
        if (!configuredKey || !safeEqual(providedHash, configuredHash)) {
          await recordLoginFailure(request, "admin-setup");
          throw new HttpError(403, "مفتاح التأسيس غير صحيح.");
        }
        const username = text(body.username, 80), password = String(body.password || "");
        if (!safeUsername(username) || password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `اسم المستخدم يجب أن يكون صالحًا وكلمة مرور الأدمن بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} خانة.`);
        const hashed = await passwordHash(password);
        const admin = dataOrThrow(await db.from("admins").insert({ username, salt: hashed.salt, password_hash: hashed.hash, force_password_change: true, created_at: now() }).select("id,username,force_password_change").single());
        await clearLoginFailures(request, "admin-setup");
        return ok({ ...(await makeSession("admin", Number(admin.id))), admin: { username: admin.username }, forcePasswordChange: true });
      }
      if (request.method === "POST" && pathname === "/admin/login") {
        const body = await bodyJson(request), username = text(body.username, 80), identity = `admin:${username}`;
        await checkLoginLimit(request, identity);
        const admin = await findAccount("admins", username), password = String(body.password || ""), withinLimit = password.length <= PASSWORD_MAX;
        const hashed = await passwordHash(withinLimit ? password : password.slice(0, PASSWORD_MAX), admin?.salt || dummySalt());
        if (!admin || !withinLimit || !safeEqual(hashed.hash, admin.password_hash)) {
          await recordLoginFailure(request, identity);
          throw new HttpError(401, "اسم المستخدم أو كلمة المرور غير صحيحة.");
        }
        await clearLoginFailures(request, identity);
        return ok({ ...(await makeSession("admin", Number(admin.id))), admin: { username: admin.username }, forcePasswordChange: !!admin.force_password_change });
      }
      if (request.method === "POST" && pathname === "/admin/logout") {
        const auth = await requireSession(request, "admin");
        dataOrThrow(await db.from("sessions").delete().eq("token_hash", hashToken(auth.token)));
        return ok();
      }
      // Only these two paths come from sadad-api; the ledger's other /admin/* routes keep their
      // X-Sadad-Admin header, so this must not claim every /admin/ path.
      if (pathname === "/admin/session" || pathname === "/admin/change-password") {
        const auth = await requireSession(request, "admin");
        if (request.method === "GET" && pathname === "/admin/session") return ok({ admin: { username: auth.user.username }, forcePasswordChange: !!auth.user.force_password_change });
        if (request.method === "POST" && pathname === "/admin/change-password") {
          const body = await bodyJson(request), currentPassword = String(body.currentPassword || ""), password = String(body.password || "");
          if (password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `كلمة المرور الجديدة يجب أن تكون بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} محرفًا.`);
          if (currentPassword.length > PASSWORD_MAX) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
          const admin = dataOrThrow(await db.from("admins").select("*").eq("id", auth.user.id).single());
          const current = await passwordHash(currentPassword, admin.salt);
          if (!safeEqual(current.hash, admin.password_hash)) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
          const next = await passwordHash(password);
          dataOrThrow(await db.from("admins").update({ salt: next.salt, password_hash: next.hash, force_password_change: false }).eq("id", admin.id));
          dataOrThrow(await db.from("sessions").delete().eq("kind", "admin").eq("principal_id", admin.id).neq("token_hash", hashToken(auth.token)));
          return ok({ forcePasswordChange: false });
        }
      }
      return null;
    } catch (error) {
      return fail(error);
    }
  }
  return { route };
}
