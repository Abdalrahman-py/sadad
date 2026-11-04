import type { Context } from "hono";
import { ApiError } from "./errors.ts";

export type AuthRole = "admin" | "support" | "store";

export type ClaimsResult = {
  data: { claims: Record<string, unknown> } | null;
  error: unknown;
};

export type VerifyClaims = (jwt: string) => Promise<ClaimsResult>;

export interface AuthDeps {
  verifyClaims: VerifyClaims;
}

export interface AppVariables {
  requestId: string;
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

/** True while the account holds a temporary password: every request is refused until it changes. */
export const mustChangePassword = (claims: Record<string, unknown>): boolean =>
  (claims.app_metadata as Record<string, unknown> | undefined)
    ?.force_password_change === true;

/**
 * The one gate both tiers use: a verified caller whose role is listed and who owes no password
 * change. A list holding "store" refuses with store_permission_denied, any other with
 * admin_authorization_required. `session` also demands the JWT's session_id claim (401), which
 * the mobile routes bind their installation to.
 */
export async function gateCaller(
  c: Context<AppEnv>,
  allowed: [AuthRole, ...AuthRole[]],
  options: { session?: boolean } = {},
): Promise<VerifiedCaller> {
  const caller = await verifyCaller(c);
  if (!allowed.includes(caller.role)) {
    throw new ApiError(
      allowed.includes("store")
        ? "store_permission_denied"
        : "admin_authorization_required",
      403,
    );
  }
  const sessionId = caller.claims.session_id;
  if (options.session && (typeof sessionId !== "string" || !sessionId)) {
    throw new ApiError("authorization_required", 401);
  }
  if (mustChangePassword(caller.claims)) {
    throw new ApiError("password_change_required", 403);
  }
  return caller;
}
