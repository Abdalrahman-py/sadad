import type { Context, MiddlewareHandler } from "hono";
import { createMiddleware } from "hono/factory";
import { ApiError, fromDatabaseError } from "./errors.ts";

export type AuthRole = "admin" | "support" | "store";

export interface AuthContext {
  userId: string;
  role: AuthRole;
  storeId?: number;
}

export type ClaimsResult = {
  data: { claims: Record<string, unknown> } | null;
  error: unknown;
};

export type VerifyClaims = (jwt: string) => Promise<ClaimsResult>;
export type LookupStoreId = (
  jwt: string,
) => Promise<number | null | undefined>;

export interface AuthDeps {
  verifyClaims: VerifyClaims;
  lookupStoreId: LookupStoreId;
}

export interface AppVariables {
  requestId: string;
  auth: AuthContext;
  authDeps: AuthDeps;
  /** The request body exactly as received, read before validation parses it. */
  rawBody?: string;
  /** Values no log line may contain, such as a temporary password. */
  secrets?: string[];
  /** The admin or support caller, set by the admin routes' role check. */
  admin?: VerifiedCaller;
}

export interface AppEnv {
  Variables: AppVariables;
}

function parseBearer(header: string | undefined): string {
  const match = header?.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

function resolveRole(claims: Record<string, unknown>): AuthRole {
  const appMeta = claims.app_metadata;
  if (appMeta && typeof appMeta === "object") {
    const role = (appMeta as Record<string, unknown>).role;
    if (role === "admin" || role === "support") {
      return role;
    }
  }
  return "store";
}

export interface VerifiedCaller {
  jwt: string;
  userId: string;
  role: AuthRole;
  claims: Record<string, unknown>;
}

/** The caller's verified JWT claims; no database round trip. */
export async function verifyCaller(
  c: Context<AppEnv>,
): Promise<VerifiedCaller> {
  const jwt = parseBearer(c.req.header("authorization"));
  if (!jwt) {
    throw new ApiError("authorization_required", 401);
  }

  const deps = c.get("authDeps");
  const verified = await deps.verifyClaims(jwt);
  const claims = verified.data?.claims;
  if (
    verified.error || !claims || typeof claims.sub !== "string" || !claims.sub
  ) {
    throw new ApiError("authorization_required", 401);
  }
  return { jwt, userId: claims.sub, role: resolveRole(claims), claims };
}

async function resolveAuth(c: Context<AppEnv>): Promise<AuthContext> {
  const existing = c.get("auth") as AuthContext | undefined;
  if (existing) {
    return existing;
  }

  const { jwt, userId, role } = await verifyCaller(c);
  const deps = c.get("authDeps");

  let storeId: number | undefined;
  if (role === "store") {
    const resolved = await deps.lookupStoreId(jwt);
    if (resolved === null || resolved === undefined) {
      throw new ApiError("store_permission_denied", 403);
    }
    storeId = Number(resolved);
  }

  const auth: AuthContext = {
    userId,
    role,
    ...(storeId !== undefined ? { storeId } : {}),
  };
  c.set("auth", auth);
  return auth;
}

export function requireAuth(): MiddlewareHandler<AppEnv> {
  return createMiddleware<AppEnv>(async (c, next) => {
    await resolveAuth(c);
    await next();
  });
}

export function requireRole(
  ...allowed: [AuthRole, ...AuthRole[]]
): MiddlewareHandler<AppEnv> {
  return createMiddleware<AppEnv>(async (c, next) => {
    const auth = await resolveAuth(c);
    if (!allowed.includes(auth.role)) {
      const code = allowed.includes("store")
        ? "store_permission_denied"
        : "admin_authorization_required";
      throw new ApiError(code, 403);
    }
    await next();
  });
}

export function makeSupabaseLookupStoreId(
  newUserClient: (jwt: string) => {
    rpc: (
      fn: string,
    ) => PromiseLike<{ data: number | null; error: unknown }>;
  },
): LookupStoreId {
  return async (jwt: string) => {
    const client = newUserClient(jwt);
    const { data, error } = await client.rpc("sadid_my_store");
    if (error) {
      throw fromDatabaseError(error) ?? error;
    }
    return data;
  };
}
