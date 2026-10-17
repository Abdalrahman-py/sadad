// Shared by the /v1/admin/* route files.
import { z } from "@hono/zod-openapi";
import type { Context, MiddlewareHandler } from "hono";
import { createMiddleware } from "hono/factory";
import { type AppEnv, verifyCaller } from "../auth.ts";
import { ApiError } from "../errors.ts";
import { type RpcClient, unavailable } from "../rpc.ts";

export type AuthUserResult = {
  data: { user: { id: string } | null };
  error: unknown;
};

/** The part of supabase-js `auth.admin` the admin routes use; it runs with the service key. */
export interface AuthAdmin {
  createUser(attributes: {
    email: string;
    password: string;
    email_confirm: boolean;
    app_metadata: Record<string, unknown>;
  }): PromiseLike<AuthUserResult>;
  updateUserById(
    id: string,
    attributes: {
      email?: string;
      email_confirm?: boolean;
      password?: string;
      app_metadata?: Record<string, unknown>;
    },
  ): PromiseLike<AuthUserResult>;
  deleteUser(id: string): PromiseLike<{ error: unknown }>;
}

export interface AdminDeps {
  /** Acts as the admin's JWT; the admin routines check role and session themselves. */
  userDb: (jwt: string) => RpcClient;
  /** Service role: only sadid_create_store, after sadid_admin_role confirmed an admin. */
  serviceDb: RpcClient;
  authAdmin: AuthAdmin;
}

const authUnavailable: AuthAdmin = {
  createUser: () => Promise.reject(new ApiError("temporary_service_failure")),
  updateUserById: () =>
    Promise.reject(new ApiError("temporary_service_failure")),
  deleteUser: () => Promise.reject(new ApiError("temporary_service_failure")),
};

export const defaultDeps: AdminDeps = {
  userDb: () => unavailable,
  serviceDb: unavailable,
  authAdmin: authUnavailable,
};

export type AdminRole = "admin" | "support";

/** Refuses every caller whose JWT role is not listed, before the body is read. */
export function allow(
  ...roles: [AdminRole, ...AdminRole[]]
): MiddlewareHandler<AppEnv> {
  return createMiddleware<AppEnv>(async (c, next) => {
    const caller = await verifyCaller(c);
    if (caller.role === "store" || !roles.includes(caller.role)) {
      throw new ApiError("admin_authorization_required", 403);
    }
    const appMeta = caller.claims.app_metadata as
      | Record<string, unknown>
      | undefined;
    if (appMeta?.force_password_change === true) {
      throw new ApiError("password_change_required", 403);
    }
    c.set("admin", caller);
    await next();
  });
}

/** The admin's routine client; only valid after allow(). */
export function adminDb(c: Context<AppEnv>, deps: AdminDeps): RpcClient {
  const caller = c.get("admin");
  if (!caller) throw new ApiError("admin_authorization_required", 403);
  return deps.userDb(caller.jwt);
}

/** Keeps a value out of every log line of this request. */
export function keepSecret(c: Context<AppEnv>, value: string): void {
  c.set("secrets", [...(c.get("secrets") ?? []), value]);
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/** The routine's nextCursor as an opaque string, or null on the last page. */
export function encodeCursor(next: unknown): string | null {
  return next === null || next === undefined
    ? null
    : base64Url(encoder.encode(JSON.stringify(next)));
}

export function decodeCursor(
  cursor: string | undefined,
): Record<string, unknown> | null {
  if (!cursor) return null;
  try {
    const binary = atob(cursor.replace(/-/g, "+").replace(/_/g, "/"));
    const value = JSON.parse(
      decoder.decode(Uint8Array.from(binary, (ch) => ch.charCodeAt(0))),
    );
    if (value && typeof value === "object" && !Array.isArray(value)) {
      return value;
    }
  } catch {
    // Falls through to the refusal below.
  }
  throw new ApiError("invalid_input", 400);
}

export const pageQuery = (maxLimit: number) => ({
  q: z.string().max(120).optional().openapi({
    description: "Part of a name to search for",
  }),
  cursor: z.string().max(512).optional().openapi({
    description: "nextCursor from the previous page",
  }),
  limit: z.coerce.number().int().min(1).max(maxLimit).default(50),
});

export const NextCursorSchema = z.string().nullable().openapi({
  description: "Pass as cursor for the next page; null on the last page",
});

export const ReasonSchema = z.string().trim().min(1).max(2000);

export const StoreIdParam = z.object({
  storeId: z.coerce.number().int().positive(),
});

export const InstallationSchema = z
  .object({
    installationId: z.uuid(),
    storeId: z.number().int(),
    generation: z.number().int(),
    label: z.string(),
    staffName: z.string(),
    permissions: z.record(z.string(), z.boolean()),
    status: z.enum(["pending", "active", "revoked"]),
    createdAt: z.string(),
  })
  .openapi("AdminInstallation");
