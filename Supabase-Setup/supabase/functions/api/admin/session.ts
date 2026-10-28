// One signed-in admin or support account at a time: the seat lives in
// schemas/42_admin_session_lease.sql and is claimed by the first request of an Auth session.
// Every other /v1/admin/* route is behind the lease; these two are registered before it so that
// signing out (release) and the board's idle heartbeat still work when the seat was taken.
import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { MiddlewareHandler } from "hono";
import { createMiddleware } from "hono/factory";
import type { AppEnv } from "../auth.ts";
import { verifyCaller } from "../auth.ts";
import { ApiError, errorResponses } from "../errors.ts";
import { call } from "../rpc.ts";
import { adminDb, type AdminDeps, allow } from "./common.ts";

const tags = ["admin"];
const security = [{ Bearer: [] }];
const okSchema = (key: "claimed" | "released") =>
  z.object({ ok: z.literal(true), [key]: z.literal(true) });

/**
 * Claims or renews the caller's seat on every admin request. Runs before the route, so a session
 * that lost the account reads nothing. A store token or a forced password change is left to allow(),
 * which answers with its own refusal.
 */
export function sessionLease(deps: AdminDeps): MiddlewareHandler<AppEnv> {
  return createMiddleware<AppEnv>(async (c, next) => {
    const caller = await verifyCaller(c);
    const appMeta = caller.claims.app_metadata as
      | Record<string, unknown>
      | undefined;
    const admin = caller.role === "admin" || caller.role === "support";
    if (admin && appMeta?.force_password_change !== true) {
      const held = await call<boolean>(
        deps.userDb(caller.jwt),
        "sadid_admin_session_claim",
      );
      if (!held) throw new ApiError("session_in_use", 409);
    }
    await next();
  });
}

const claimRoute = createRoute({
  method: "post",
  path: "/admin/session",
  tags,
  security,
  description:
    "Claims the account for the caller's Auth session, or renews it. 409 session_in_use while another session holds it (the dashboard calls this right after sign-in, and every 60 seconds while it is open).",
  middleware: [allow("admin", "support")] as const,
  responses: {
    200: {
      description: "This session holds the account",
      content: {
        "application/json": {
          schema: okSchema("claimed"),
        },
      },
    },
    ...errorResponses(401, 403, 409, 500),
  },
});

const releaseRoute = createRoute({
  method: "delete",
  path: "/admin/session",
  tags,
  security,
  description:
    "Frees the account at sign-out, so another browser can sign in at once. Releasing a seat this session does not hold is a no-op. admin and support.",
  middleware: [allow("admin", "support")] as const,
  responses: {
    200: {
      description: "Released, or nothing to release",
      content: {
        "application/json": {
          schema: okSchema("released"),
        },
      },
    },
    ...errorResponses(401, 403, 500),
  },
});

export function registerSessionRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps,
): void {
  app.openapi(claimRoute, async (c) => {
    const held = await call<boolean>(
      adminDb(c, deps),
      "sadid_admin_session_claim",
    );
    if (!held) throw new ApiError("session_in_use", 409);
    return c.json({ ok: true as const, claimed: true as const }, 200);
  });
  app.openapi(releaseRoute, async (c) => {
    await call<null>(adminDb(c, deps), "sadid_admin_session_release");
    return c.json({ ok: true as const, released: true as const }, 200);
  });
}
