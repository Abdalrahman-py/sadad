// /v1/me/*: the signed-in Auth user's own account, for every role. Not a mobile route: a store
// with force_password_change has no enrolled installation yet, so there is no device proof.
import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import { type AuthAdmin, keepSecret } from "./admin/common.ts";
import { type AppEnv, verifyCaller } from "./auth.ts";
import { ApiError, errorResponses } from "./errors.ts";

export interface AccountDeps {
  /** Service role: clears force_password_change, which the user's own updateUser cannot touch. */
  authAdmin: AuthAdmin;
  /** True when email and password sign in to Supabase Auth; the session it opens is ended at once.
   * requestId is only for the one log line when ending that session fails. */
  verifyPassword(
    email: string,
    password: string,
    requestId: string,
  ): Promise<boolean>;
}

const unavailable = () =>
  Promise.reject(new ApiError("temporary_service_failure"));

export const defaultAccountDeps: AccountDeps = {
  authAdmin: {
    createUser: unavailable,
    updateUserById: unavailable,
    deleteUser: unavailable,
  },
  verifyPassword: unavailable,
};

const json = <T extends z.ZodType>(schema: T) => ({
  content: { "application/json": { schema } },
});

/**
 * The contract refusal for an Auth sign-in error code. `null` means the code is not mapped here:
 * index.ts answers invalid_credentials as a wrong password, and rethrows anything else through.
 */
export function authSignInError(code: string): ApiError | null {
  if (code === "over_request_rate_limit") {
    return new ApiError("request_rate_limited", 429);
  }
  if (code === "user_banned") {
    return new ApiError("store_permission_denied", 403);
  }
  return null;
}

const passwordRoute = createRoute({
  method: "post",
  path: "/me/password",
  tags: ["account"],
  security: [{ Bearer: [] }],
  description:
    "Proves the current password, sets a new one (12 to 128 characters, not the current one) and clears app_metadata.force_password_change. Every role; the Auth JWT only, no device proof. The caller's JWT keeps its old claims until it expires: after a 200 the client must refresh its session, and the refreshed JWT carries the cleared flag.",
  request: {
    body: {
      required: true,
      ...json(
        z.strictObject({
          currentPassword: z.string().min(1).max(128),
          newPassword: z.string().min(12).max(128),
        }).refine((b) => b.currentPassword !== b.newPassword),
      ),
    },
  },
  responses: {
    200: {
      description: "Password changed; refresh the session",
      ...json(z.object({
        ok: z.literal(true),
        forcePasswordChange: z.literal(false),
      })),
    },
    ...errorResponses(400, 401, 403, 429, 500),
  },
});

export function registerAccountRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AccountDeps = defaultAccountDeps,
): void {
  app.openapi(passwordRoute, async (c) => {
    const { currentPassword, newPassword } = c.req.valid("json");
    keepSecret(c, currentPassword);
    keepSecret(c, newPassword);

    const caller = await verifyCaller(c);
    const email = caller.claims.email;
    if (typeof email !== "string" || !email) {
      throw new ApiError("authorization_required", 401);
    }
    if (
      !(await deps.verifyPassword(
        email,
        currentPassword,
        c.get("requestId"),
      ))
    ) {
      throw new ApiError("current_password_incorrect", 403);
    }

    // Auth merges app_metadata keys, so role stays.
    const updated = await deps.authAdmin.updateUserById(caller.userId, {
      password: newPassword,
      app_metadata: { force_password_change: false },
    });
    if (updated.error) {
      const record = updated.error as { code?: unknown; message?: unknown };
      if (record.code === "weak_password") {
        throw new ApiError("weak_password", 400);
      }
      throw new Error(
        `auth admin: ${String(record.message ?? updated.error)}`,
      );
    }
    return c.json(
      { ok: true as const, forcePasswordChange: false as const },
      200,
    );
  });
}
