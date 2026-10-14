import { randomBytes, scrypt, timingSafeEqual, createHash } from "node:crypto";
import { Buffer } from "node:buffer";
import { adminLedgerSnapshot, ledgerAuditEvents } from "./admin-ledger.ts";
type Json = Record<string, any>;
type Principal = { kind: "admin" | "store"; token: string; session: Json; user: Json; device: Json | null };

const SESSION_MS = 12 * 60 * 60 * 1000;
const LOGIN_WINDOW_MS = 15 * 60_000;
const LOGIN_BLOCK_MS = 15 * 60_000;
const ADMIN_PASSWORD_MIN = 12;
const PASSWORD_MAX = 128;
const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Max-Age": "86400",
};

class HttpError extends Error {
  status: number;
  extra: Json;
  constructor(status: number, message: string, extra: Json = {}) { super(message); this.status = status; this.extra = extra; }
}

function text(value: unknown, max = 250): string { return String(value ?? "").trim().slice(0, max); }
function parseJsonObject(value: unknown): Json {
  if (value && typeof value === "object" && !Array.isArray(value)) return value as Json;
  if (typeof value === "string") { try { return JSON.parse(value); } catch { return {}; } }
  return {};
}
function send(status: number, body: Json): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS_HEADERS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
function ok(data: Json = {}): Response { return send(200, { ok: true, ...data }); }
async function bodyJson(request: Request): Promise<Json> {
  const raw = await request.text();
  if (raw.length > 3_000_000) throw new HttpError(413, "الطلب أكبر من الحد المسموح.");
  if (!raw) return {};
  try { const value = JSON.parse(raw); if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(); return value; }
  catch { throw new HttpError(400, "صيغة البيانات غير صحيحة."); }
}
export function fail(error: unknown): Response {
  if (error instanceof HttpError) return send(error.status, { ok: false, message: error.message, ...error.extra });
  console.error("Sadad Edge Function error", error);
  return send(500, { ok: false, message: "حدث خطأ داخلي." });
}
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
function parseDeviceLimit(value: unknown, fallback = 1): number {
  if (value === undefined || value === null) return fallback;
  const digits = String(value).trim().replace(/[٠-٩]/g, digit => String("٠١٢٣٤٥٦٧٨٩".indexOf(digit))).replace(/[۰-۹]/g, digit => String("۰۱۲۳۴۵۶۷۸۹".indexOf(digit)));
  const limit = Number(digits);
  if (!digits || !Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw new HttpError(400, "عدد الأجهزة المسموح بها يجب أن يكون من 1 إلى 50.");
  return limit;
}
let dummyCredentialSalt = "";
function dummySalt(): string { if (!dummyCredentialSalt) dummyCredentialSalt = passwordSalt().salt; return dummyCredentialSalt; }
function safeUsername(value: string): boolean { return /^[\p{L}\p{N}._-]{3,80}$/u.test(value); }

type Dependencies = {
  db: any;
  now?: () => number;
};

const SENTINEL_ERRORS: Record<string, { status: number; message: string | ((context: Json) => string) }> = {
  store_not_found: { status: 404, message: "الحساب غير موجود." },
  device_limit: { status: 403, message: (context) => `وصل الحساب إلى حد الأجهزة المسموح (${context.allowedDevices}). اطلب من الأدمن زيادة العدد أو السماح بهذا الجهاز.` },
  device_revoked: { status: 403, message: "تم إلغاء هذا الجهاز من الأدمن. اطلب السماح به مجددًا." },
  device_staff_required: { status: 409, message: "أدخل اسم الشخص الذي سيستخدم هذا الجهاز ليظهر اسمه في السجلات." },
  store_inactive: { status: 423, message: "الحساب موقوف من الإدارة. تواصل مع الدعم." },
};

/** Return an RPC result's data, or map its P0001 sentinel through SENTINEL_ERRORS and throw. Anything
 * the table does not list falls through unchanged. */
async function rpcResult<T = any>(result: { data: T; error: any }, context: Json = {}): Promise<T> {
  if (!result.error) return result.data;
  if (result.error.code === "P0001") {
    const mapped = SENTINEL_ERRORS[result.error.message];
    if (mapped) {
      throw new HttpError(mapped.status, typeof mapped.message === "function" ? mapped.message(context) : mapped.message);
    }
  }
  throw result.error;
}

export function makeApi({ db, now = Date.now }: Dependencies) {
  function nowOr(value: unknown): number { const n = Number(value); return Number.isFinite(n) && n > 0 ? n : now(); }
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
  function subscriptionState(store: Json, at = now()): Json {
    if (store.subscription_mode === "permanent") return { mode: "permanent", active: true, expiresAt: null, remainingMs: null };
    if (store.subscription_mode === "paused") return { mode: "paused", active: false, expiresAt: store.subscription_expires_at || null, remainingMs: Math.max(0, Number(store.subscription_paused_remaining_ms) || 0) };
    const expiresAt = Number(store.subscription_expires_at) || 0;
    return { mode: "timed", active: expiresAt > at, expiresAt: expiresAt || null, remainingMs: Math.max(0, expiresAt - at) };
  }
  async function refreshStoreStatus(store: Json): Promise<Json> {
    const subscription = subscriptionState(store);
    if ((!subscription.active || subscription.mode === "paused") && store.status === "active") {
      dataOrThrow(await db.from("stores").update({ status: "suspended", suspend_until: null }).eq("id", store.id));
      dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", store.id));
      return dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
    }
    if (store.status === "suspended" && store.suspend_until && Number(store.suspend_until) <= now() && subscription.active) {
      dataOrThrow(await db.from("stores").update({ status: "active", suspend_until: null }).eq("id", store.id));
      return dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
    }
    return store;
  }
  async function requireSession(request: Request, kind: "admin" | "store"): Promise<Principal> {
    const token = bearer(request);
    if (!token) throw new HttpError(401, "سجّل الدخول للمتابعة.");
    const session = dataOrThrow(await db.from("sessions").select("*").eq("token_hash", hashToken(token)).eq("kind", kind).gt("expires_at", now()).maybeSingle());
    if (!session) throw new HttpError(401, "انتهت الجلسة. سجّل الدخول من جديد.");
    let user = kind === "admin"
      ? dataOrThrow(await db.from("admins").select("id,username,force_password_change").eq("id", session.principal_id).maybeSingle())
      : dataOrThrow(await db.from("stores").select("*").eq("id", session.principal_id).maybeSingle());
    if (!user) throw new HttpError(401, "الحساب غير موجود.");
    let device: Json | null = null;
    if (kind === "store") {
      user = await refreshStoreStatus(user);
      device = session.device_id
        ? dataOrThrow(await db.from("store_devices").select("*").eq("store_id", user.id).eq("device_id", session.device_id).is("revoked_at", null).maybeSingle())
        : null;
      if (!device) {
        await db.from("sessions").delete().eq("token_hash", hashToken(token));
        throw new HttpError(401, "تم إلغاء تسجيل هذا الجهاز من الإدارة. سجّل الدخول مجددًا.");
      }
      if (user.status !== "active") throw new HttpError(423, "الحساب موقوف من الإدارة. تواصل مع الدعم.");
    }
    return { kind, token, session, user, device };
  }
  async function accountForStore(store: Json): Promise<Json> {
    const devicesResult = await db.from("store_devices").select("device_id", { count: "exact", head: true }).eq("store_id", store.id).is("revoked_at", null);
    if (devicesResult.error) throw devicesResult.error;
    const state = subscriptionState(store);
    return {
      id: store.id, name: store.name, username: store.username, verified: !!store.verified,
      permissions: parseJsonObject(store.permissions), debtorLimit: Number(store.debtor_limit),
      subscriptionMode: state.mode, subscriptionExpiresAt: state.expiresAt, subscriptionRemainingMs: state.remainingMs,
      maxDevices: Number(store.allowed_devices || 1), boundDevices: devicesResult.count || 0,
    };
  }
  async function adminStoreList(auth: Principal, includeArchived = false): Promise<Json[]> {
    const rows: Json[] = dataOrThrow(await db.from("stores").select("*").order("created_at", { ascending: false }));
    const visible = rows.filter(row => includeArchived || row.status !== "deleted");
    for (let index=0;index<visible.length;index++) if (visible[index].status !== "deleted") visible[index]=await refreshStoreStatus(visible[index]);
    const results = await Promise.all([
      db.rpc("sadid_admin_summaries", { p_admin_hash: hashToken(auth.token), p_stores: visible.map(row => Number(row.id)) }),
      db.rpc("sadid_pending_installations", { p_admin_hash: hashToken(auth.token) }),
    ]);
    const [summaries, installations] = results.map(result => dataOrThrow<Json[]>(result));
    const summaryByStore = new Map(summaries.map(row => [Number(row.storeId), row]));
    const phonesByStore = new Map<number, { active: number; pending: number }>();
    for (const phone of installations) {
      const counts = phonesByStore.get(Number(phone.storeId)) || { active: 0, pending: 0 };
      if (phone.status === "active") counts.active++;
      if (phone.status === "pending") counts.pending++;
      phonesByStore.set(Number(phone.storeId), counts);
    }
    return visible.map(row => {
      const summary = summaryByStore.get(Number(row.id)) || {};
      const phones = phonesByStore.get(Number(row.id));
      return { ...publicStore(row), contacts: 0, debts: 0, debtTotal: 0,
        ...summary, archived: row.status === "deleted",
        boundDevices: phones?.active || 0, pendingDevices: phones?.pending || 0 };
    });
  }

  async function readAdminLedger(auth: Principal, store: Json, account: Json, initialized: boolean, reason: string): Promise<Json> {
    const raw = initialized ? dataOrThrow(await db.rpc("sadid_support_snapshot", {
      p_admin_hash: hashToken(auth.token), p_store: Number(store.id), p_reason: reason,
    })) : null;
    return { snapshot: adminLedgerSnapshot(raw || { contacts: [], debts: [], payments: [], events: [] }, account),
      auditEvents: raw ? ledgerAuditEvents(raw) : [] };
  }

  async function adminAccountAction(auth: Principal, storeId: number, action: string, reason: string): Promise<Json> {
    return dataOrThrow(await db.rpc("sadid_admin_account_action", { p_admin_hash: hashToken(auth.token), p_store: storeId, p_action: action, p_reason: reason }));
  }

  function publicStore(store: Json): Json {
    const state = subscriptionState(store);
    return { id: Number(store.id), name: store.name, username: store.username, status: store.status, suspendUntil: store.suspend_until,
      verified: !!store.verified, debtorLimit: Number(store.debtor_limit), permissions: parseJsonObject(store.permissions),
      revision: Number(store.revision), createdAt: Number(store.created_at),
      subscriptionMode: state.mode, subscriptionStartedAt: store.subscription_started_at || null,
      subscriptionExpiresAt: state.expiresAt, subscriptionRemainingMs: state.remainingMs, maxDevices: Number(store.allowed_devices || 1) };
  }
  async function listDevices(storeId: number): Promise<Json[]> {
    const rows = dataOrThrow(await db.from("store_devices").select("device_id,label,staff_name,permissions,first_seen_at,last_seen_at,revoked_at").eq("store_id", storeId).order("last_seen_at", { ascending: false }));
    return rows.map((row: Json) => ({ id: row.device_id, label: row.label, staffName: row.staff_name || "", permissions: { registerPayments: true, deleteRecords: true, deleteContacts: true, ...parseJsonObject(row.permissions) }, firstSeenAt: row.first_seen_at, lastSeenAt: row.last_seen_at, revokedAt: row.revoked_at }));
  }
  async function enforceDeviceCap(storeId: number, limit: number): Promise<void> {
    const devices = dataOrThrow(await db.from("store_devices").select("device_id").eq("store_id", storeId).is("revoked_at", null).order("last_seen_at", { ascending: false }).order("first_seen_at", { ascending: false }).order("device_id", { ascending: true }));
    for (const device of devices.slice(Math.max(1, limit))) {
      const at = now();
      dataOrThrow(await db.from("store_devices").update({ revoked_at: at }).eq("store_id", storeId).eq("device_id", device.device_id).is("revoked_at", null));
      dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId).eq("device_id", device.device_id));
    }
  }
  async function findAccount(table: "admins" | "stores", username: string): Promise<Json | null> {
    if (!safeUsername(username)) return null;
    const pattern = username.replace(/[\\%_]/g, "\\$&");
    return dataOrThrow(await db.from(table).select("*").ilike("username", pattern).maybeSingle());
  }
  async function countAdmins(): Promise<number> {
    const result = await db.from("admins").select("id", { count: "exact", head: true });
    if (result.error) throw result.error;
    return result.count || 0;
  }
  async function isLedgerInitialized(storeId: number): Promise<boolean> {
    return !!dataOrThrow(await db.rpc("sadid_is_v3_store", { p_store: storeId }));
  }
  async function route(request: Request): Promise<Response> {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS_HEADERS });
    const url = new URL(request.url);
    let pathname = url.pathname;
    const functionMarker = "/sadad-api";
    const markerAt = pathname.indexOf(functionMarker);
    if (markerAt >= 0) pathname = pathname.slice(markerAt + functionMarker.length) || "/";
    if (pathname.startsWith("/api/")) pathname = pathname.slice(4);
    if (pathname === "/api") pathname = "/";

    if (request.method === "GET" && pathname === "/health") return ok({ service: "sadad", storage: "supabase-postgres" });
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
    if (request.method === "POST" && pathname === "/mobile/login") {
      const body = await bodyJson(request), username = text(body.username, 80), identity = `store:${username}`;
      await checkLoginLimit(request, identity);
      let store = await findAccount("stores", username);
      const password = String(body.password || ""), withinLimit = password.length <= PASSWORD_MAX;
      const hashed = await passwordHash(withinLimit ? password : password.slice(0, PASSWORD_MAX), store?.salt || dummySalt());
      if (!store || !withinLimit || !safeEqual(hashed.hash, store.password_hash)) {
        await recordLoginFailure(request, identity);
        throw new HttpError(401, "اسم المستخدم أو كلمة المرور غير صحيحة.");
      }
      await clearLoginFailures(request, identity);
      store = await refreshStoreStatus(store);
      if (store.status !== "active") {
        const state = subscriptionState(store);
        if (state.mode === "paused") throw new HttpError(423, "الاشتراك موقوف. يفعّل الأدمن الاشتراك أو يجدده.");
        if (state.mode === "timed" && !state.active) throw new HttpError(423, "انتهت مدة الاشتراك. تواصل مع الأدمن لتجديده.");
        throw new HttpError(423, "الحساب مجمّد من الأدمن. تواصل معه لإعادة التفعيل.");
      }
      const deviceId = text(body.deviceId, 120), deviceLabel = text(body.deviceName, 120), requestedStaff = text(body.staffName, 80);
      if (!/^[A-Za-z0-9._:-]{16,120}$/.test(deviceId)) throw new HttpError(400, "تعذر التعرف على هذا الجهاز. حدّث التطبيق وحاول مجددًا.");
      const existing = dataOrThrow(await db.from("store_devices").select("*").eq("store_id", store.id).eq("device_id", deviceId).maybeSingle());
      if (existing?.revoked_at) throw new HttpError(403, "تم إلغاء هذا الجهاز من الأدمن. اطلب السماح به مجددًا.");
      const requiresStaffName = Number(store.allowed_devices || 1) > 1;
      if (requiresStaffName && (!existing || !existing.staff_name) && requestedStaff.length < 2) throw new HttpError(409, "أدخل اسم الشخص الذي سيستخدم هذا الجهاز ليظهر اسمه في السجلات. يُحفظ هذا الاسم مع جهاز تسجيل الدخول.");
      if (requiresStaffName && requestedStaff.length > 0 && (requestedStaff.length < 2 || /[\u0000-\u001f]/.test(requestedStaff))) throw new HttpError(400, "اكتب اسمًا من خانتين على الأقل بدون رموز تحكم.");
      const staffName = existing?.staff_name || (requiresStaffName ? requestedStaff : "");
      const at = now(), token = randomBytes(32).toString("base64url"), expiresAt = at + SESSION_MS;
      const createdSession = await db.rpc("sadad_create_store_session", {
        p_store_id: Number(store.id), p_token_hash: hashToken(token), p_expires_at: expiresAt, p_now: at,
        p_device_id: deviceId, p_device_label: deviceLabel, p_staff_name: staffName,
      });
      const deviceRecord = await rpcResult(createdSession, { allowedDevices: store.allowed_devices });
      const session = { token, expiresAt };
      store = dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
      if (!store) throw new HttpError(401, "جلسة المتجر غير صالحة.");
      const deviceInfo = { staffName: deviceRecord.staff_name || "صاحب المتجر", permissions: { registerPayments: true, deleteRecords: true, deleteContacts: true, ...parseJsonObject(deviceRecord.permissions) } };
      return ok({ ...session, ledger: "v3", forcePasswordChange: !!store.force_password_change,
        account: await accountForStore(store), device: deviceInfo, deviceStaffName: deviceInfo.staffName });
    }

    if (request.method === "POST" && pathname === "/admin/logout") {
      const auth = await requireSession(request, "admin");
      dataOrThrow(await db.from("sessions").delete().eq("token_hash", hashToken(auth.token)));
      return ok();
    }
    if (request.method === "POST" && pathname === "/mobile/logout") {
      const auth = await requireSession(request, "store");
      dataOrThrow(await db.from("sessions").delete().eq("token_hash", hashToken(auth.token)));
      return ok();
    }
    if (pathname.startsWith("/admin/")) {
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
      if (auth.user.force_password_change) throw new HttpError(403, "غيّر كلمة مرور الأدمن للمتابعة.", { forcePasswordChange: true });
      if (request.method === "GET" && pathname === "/admin/dashboard") {
        const stores = await adminStoreList(auth, url.searchParams.get("includeArchived") === "true");
        const available = stores.filter(store => !store.archived);
        const overview = { stores: available.length, active: available.filter(store => store.status === "active").length,
          trusted: available.filter(store => store.verified).length, debts: available.reduce((sum,store) => sum + Number(store.debtTotal), 0),
          pendingDevices: available.reduce((sum,store) => sum + Number(store.pendingDevices), 0) };
        return ok({ overview, stores, meta: { username: auth.user.username, backend: "supabase" } });
      }
      if (request.method === "GET" && pathname === "/admin/stores") return ok({ stores: await adminStoreList(auth, url.searchParams.get("includeArchived") === "true") });
      if (request.method === "POST" && pathname === "/admin/stores") {
        const body = await bodyJson(request), name = text(body.name, 120), username = text(body.username, 80), password = String(body.password || "");
        if (!name || !safeUsername(username) || password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, "أدخل اسم المتجر واسم مستخدم وكلمة مؤقتة بين 12 و128 محرفًا.");
        const mode = ["permanent", "timed", "paused"].includes(body.subscriptionMode) ? body.subscriptionMode : "timed";
        const days = Math.floor(Number(body.subscriptionDays) || 0), maxDevices = parseDeviceLimit(body.maxDevices, 1), at = now();
        if (mode === "timed" && (days < 1 || days > 36500)) throw new HttpError(400, "حدد مدة اشتراك بين يوم واحد و100 سنة، أو اختر فعال دائمًا.");
        const hashed = await passwordHash(password);
        const defaults = { contacts: true, debts: true, payments: true, reports: true, analytics: true, export: true, whatsapp: true, appLock: true };
        const permissions = { ...defaults, ...parseJsonObject(body.permissions) };
        const debtorLimit = body.debtorLimit === undefined ? 100 : Math.max(0, Math.min(1_000_000, Math.floor(Number(body.debtorLimit) || 0)));
        const inserted = await db.from("stores").insert({
          name, username, salt: hashed.salt, password_hash: hashed.hash, force_password_change: true,
          status: mode === "paused" ? "suspended" : "active", debtor_limit: debtorLimit, subscription_mode: mode,
          subscription_started_at: mode === "permanent" ? null : at,
          subscription_expires_at: mode === "timed" ? at + days * 86_400_000 : null,
          allowed_devices: maxDevices, permissions, created_at: at,
        }).select("id,username").single();
        if (inserted.error?.code === "23505") throw new HttpError(409, "اسم المستخدم مستخدم بالفعل.");
        const created = dataOrThrow(inserted);
        return send(201, { ok: true, id: Number(created.id), username: created.username, password, forcePasswordChange: true });
      }

      const storeMatch = pathname.match(/^\/admin\/stores\/(\d+)(?:\/(.*))?$/);
      if (storeMatch) {
        const storeId = Number(storeMatch[1]), action = storeMatch[2] || "";
        let store = dataOrThrow(await db.from("stores").select("*").eq("id", storeId).maybeSingle());
        if (!store) throw new HttpError(404, "المتجر غير موجود.");
        if (store.status === "deleted" && !(request.method === "GET" && action === "") && action !== "unarchive") throw new HttpError(409, "الحساب مؤرشف. أعده من الأرشيف أولًا.");
        store = await refreshStoreStatus(store);
        const ledgerInitialized = await isLedgerInitialized(storeId);
        if (request.method === "POST" && ["archive", "unarchive", "end-sessions", "revoke-all"].includes(action)) {
          const body = await bodyJson(request), reason = text(body.reason, 2000);
          if (!reason) throw new HttpError(400, "اكتب سبب الإجراء.");
          return ok({ result: await adminAccountAction(auth, storeId, action, reason) });
        }

        const account: Json = { ...publicStore(store), archived: store.status === "deleted" };
        if (request.method === "GET" && action === "ledger") {
          const reason = text(url.searchParams.get("reason") || "Store ledger in admin dashboard", 2000);
          return ok(await readAdminLedger(auth, store, account, ledgerInitialized, reason));
        }
        if (request.method === "GET" && action === "") {
          const reason = text(url.searchParams.get("reason") || "Store management in admin dashboard", 2000);
          const [devices, controls, ledger] = await Promise.all([
            listDevices(storeId),
            db.rpc("sadid_admin_controls", { p_admin_hash: hashToken(auth.token), p_store: storeId }),
            url.searchParams.get("includeLedger") === "false" ? null : readAdminLedger(auth, store, account, ledgerInitialized, reason),
          ]);
          const control = dataOrThrow(controls);
          account.canDelete = !ledgerInitialized && !control.hasDeviceHistory;
          return ok({ store: account, devices, ...ledger, ...control });
        }
        if (request.method === "GET" && action === "devices") return ok({ devices: await listDevices(storeId) });

        const allowDevice = action.match(/^devices\/([A-Za-z0-9._:%-]+)\/allow$/);
        if (request.method === "POST" && allowDevice) {
          const deviceId = decodeURIComponent(allowDevice[1]);
          const device = dataOrThrow(await db.from("store_devices").select("*").eq("store_id", storeId).eq("device_id", deviceId).maybeSingle());
          if (!device) throw new HttpError(404, "الجهاز غير معروف لهذا الحساب.");
          const active = await db.from("store_devices").select("device_id", { count: "exact", head: true }).eq("store_id", storeId).is("revoked_at", null);
          if (active.error) throw active.error;
          if (device.revoked_at && (active.count || 0) >= Number(store.allowed_devices || 1)) throw new HttpError(409, "وصلت إلى حد الأجهزة المسموح. ارفع الحد أو ألغِ جهازًا آخر.");
          dataOrThrow(await db.from("store_devices").update({ revoked_at: null }).eq("store_id", storeId).eq("device_id", deviceId));
          return ok({ allowed: true });
        }
        if (request.method === "POST" && action === "update") {
          const body = await bodyJson(request), nextStatus = ["active", "suspended"].includes(body.status) ? body.status : store.status;
          const suspendUntil = nextStatus === "suspended" ? (Number(body.suspendUntil) || null) : null;
          if (nextStatus === "active" && !subscriptionState(store).active) throw new HttpError(409, "الاشتراك موقوف أو منتهٍ. فعّل الاشتراك أو جدده أولاً.");
          const permissionNames = ["contacts", "debts", "payments", "reports", "analytics", "export", "whatsapp", "appLock"];
          const permissions = parseJsonObject(store.permissions), requested = parseJsonObject(body.permissions);
          for (const key of permissionNames) if (typeof requested[key] === "boolean") permissions[key] = requested[key];
          const name = body.name === undefined ? store.name : text(body.name, 120), username = body.username === undefined ? store.username : text(body.username, 80);
          if (!name || !safeUsername(username)) throw new HttpError(400, "أدخل اسم متجر واسم مستخدم صالحين.");
          const maxDevices = parseDeviceLimit(body.maxDevices ?? body.allowedDevices ?? body.allowed_devices ?? body.maxUsers ?? body.max_users, Number(store.allowed_devices || 1));
          const debtorLimit = Math.max(0, Math.min(1_000_000, body.debtorLimit === undefined ? Number(store.debtor_limit) : Number(body.debtorLimit)));
          const update = await db.from("stores").update({
            name, username, status: nextStatus, suspend_until: suspendUntil,
            verified: body.verified === undefined ? store.verified : !!body.verified,
            debtor_limit: debtorLimit, permissions,
            allowed_devices: maxDevices, revision: Number(store.revision) + 1,
          }).eq("id", storeId);
          if (update.error?.code === "23505") throw new HttpError(409, "اسم المستخدم مستخدم بالفعل.");
          if (update.error) throw update.error;
          await enforceDeviceCap(storeId, maxDevices);
          if (nextStatus !== "active") dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
          const updated = dataOrThrow(await db.from("stores").select("id,name,username,status,suspend_until,verified,debtor_limit,permissions,allowed_devices").eq("id", storeId).single());
          return ok({ store: { ...updated, maxDevices, allowedDevices: maxDevices, permissions: parseJsonObject(updated.permissions) } });
        }
        if (request.method === "POST" && action === "subscription") {
          const body = await bodyJson(request), mode = body.mode;
          if (!["permanent", "timed", "paused"].includes(mode)) throw new HttpError(400, "اختر مدة اشتراك صحيحة.");
          const at = now(); let started = store.subscription_started_at, expires = store.subscription_expires_at, remaining = store.subscription_paused_remaining_ms;
          let status = store.status, suspendUntil = store.suspend_until;
          if (mode === "permanent") { started = null; expires = null; remaining = null; status = "active"; suspendUntil = null; }
          else if (mode === "paused") { if (store.subscription_mode === "timed") remaining = subscriptionState(store, at).remainingMs; status = "suspended"; suspendUntil = null; }
          else {
            const days = Math.floor(Number(body.days) || 0);
            if (days < 1 || days > 36500) throw new HttpError(400, "حدد عدد أيام بين 1 و36500.");
            const base = body.extend ? (store.subscription_mode === "paused" ? at + Math.max(0, Number(remaining) || 0) : Math.max(at, Number(expires) || 0)) : at;
            expires = base + days * 86_400_000; started = started || at; remaining = null; status = "active"; suspendUntil = null;
          }
          dataOrThrow(await db.from("stores").update({ subscription_mode: mode, subscription_started_at: started, subscription_expires_at: expires, subscription_paused_remaining_ms: remaining, status, suspend_until: suspendUntil, revision: Number(store.revision) + 1 }).eq("id", storeId));
          if (status !== "active") dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
          const updated = dataOrThrow(await db.from("stores").select("*").eq("id", storeId).single());
          const state = subscriptionState(updated);
          return ok({ store: { subscriptionMode: state.mode, subscriptionStartedAt: updated.subscription_started_at, subscriptionExpiresAt: state.expiresAt, subscriptionRemainingMs: state.remainingMs, status: updated.status } });
        }
        if (request.method === "POST" && action === "reset-password") {
          const body = await bodyJson(request), password = String(body.password || "");
          if (password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, "كلمة المرور المؤقتة يجب أن تكون بين 12 و128 محرفًا.");
          const hashed = await passwordHash(password);
          dataOrThrow(await db.from("stores").update({ salt: hashed.salt, password_hash: hashed.hash, force_password_change: true }).eq("id", storeId));
          dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
          await adminAccountAction(auth, storeId, "end-sessions", "Store password reset by admin");
          return ok({ username: store.username, password, forcePasswordChange: true });
        }
        if (request.method === "DELETE" && action === "permanent") {
          const control = dataOrThrow(await db.rpc("sadid_admin_controls", { p_admin_hash: hashToken(auth.token), p_store: storeId }));
          if (ledgerInitialized || control.hasDeviceHistory) throw new HttpError(409, "هذا الحساب له سجل محفوظ. استخدم أرشفة الحساب بدل الحذف النهائي.");
          if (url.searchParams.get("confirm") !== store.username) throw new HttpError(400, "أرسل اسم المستخدم لتأكيد الحذف النهائي.");
          dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
          dataOrThrow(await db.from("stores").delete().eq("id", storeId));
          return ok({ deleted: true });
        }
        throw new HttpError(404, "المسار الإداري غير موجود.");
      }
      throw new HttpError(404, "المسار الإداري غير موجود.");
    }
    if (pathname.startsWith("/mobile/")) {
      const auth = await requireSession(request, "store");
      const store = auth.user;
      if (request.method === "GET" && pathname === "/mobile/session") return ok({ account: await accountForStore(store), forcePasswordChange: !!store.force_password_change, revision: Number(store.revision) });
      if (request.method === "POST" && pathname === "/mobile/change-password") {
        const body = await bodyJson(request), currentPassword = String(body.currentPassword || ""), password = String(body.password || "");
        if (password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `كلمة المرور الجديدة يجب أن تكون بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} محرفًا.`);
        if (currentPassword.length > PASSWORD_MAX) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
        const current = await passwordHash(currentPassword, store.salt);
        if (!safeEqual(current.hash, store.password_hash)) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
        const next = await passwordHash(password);
        dataOrThrow(await db.from("stores").update({ salt: next.salt, password_hash: next.hash, force_password_change: false }).eq("id", store.id));
        dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", store.id).neq("token_hash", hashToken(auth.token)));
        return ok({ forcePasswordChange: false });
      }
      throw new HttpError(404, "مسار التطبيق غير موجود.");
    }
    throw new HttpError(404, "المسار غير موجود.");
  }
  return { route };
}
