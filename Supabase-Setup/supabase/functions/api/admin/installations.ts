// Devices: the pending list across stores, approve and revoke.
import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../auth.ts";
import { errorResponses } from "../errors.ts";
import { call } from "../rpc.ts";
import {
  adminDb,
  type AdminDeps,
  allow,
  decodeCursor,
  encodeCursor,
  InstallationSchema,
  NextCursorSchema,
  pageQuery,
  ReasonSchema,
} from "./common.ts";

const tags = ["admin"];
const security = [{ Bearer: [] }];

const PendingSchema = InstallationSchema.extend({ storeName: z.string() });

const ResultSchema = z.object({
  installationId: z.uuid(),
  generation: z.number().int().optional(),
  status: z.enum(["active", "revoked"]),
});

const pendingRoute = createRoute({
  method: "get",
  path: "/admin/installations",
  tags,
  security,
  description:
    "Devices waiting for approval, newest first. q matches part of the label, staff name or store name. admin and support.",
  middleware: [allow("admin", "support")] as const,
  request: { query: z.object(pageQuery(100)) },
  responses: {
    200: {
      description: "One page of pending devices",
      content: {
        "application/json": {
          schema: z.object({
            ok: z.literal(true),
            installations: z.array(PendingSchema),
            nextCursor: NextCursorSchema,
          }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 500),
  },
});

const decisionRoute = (decision: "approve" | "revoke") =>
  createRoute({
    method: "post",
    path: `/admin/installations/{installationId}/${decision}`,
    tags,
    security,
    description: decision === "approve"
      ? "Activates a pending device within the store's device cap (device_limit_reached otherwise). admin only."
      : "Revokes a device and its capabilities. admin only.",
    middleware: [allow("admin")] as const,
    request: {
      params: z.object({ installationId: z.uuid() }),
      body: {
        required: true,
        content: {
          "application/json": {
            schema: z.strictObject({ reason: ReasonSchema }),
          },
        },
      },
    },
    responses: {
      200: {
        description: "The device's new status",
        content: {
          "application/json": {
            schema: z.object({
              ok: z.literal(true),
              installation: ResultSchema,
            }),
          },
        },
      },
      ...errorResponses(400, 401, 403, 404, 409, 500),
    },
  });

export function registerInstallationRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps,
): void {
  app.openapi(pendingRoute, async (c) => {
    const { q, cursor, limit } = c.req.valid("query");
    const page = await call<
      { installations: z.infer<typeof PendingSchema>[]; nextCursor: unknown }
    >(adminDb(c, deps), "sadid_pending_installations", {
      p_query: q ?? null,
      p_after: decodeCursor(cursor),
      p_limit: limit,
    });
    return c.json({
      ok: true as const,
      installations: page.installations,
      nextCursor: encodeCursor(page.nextCursor),
    }, 200);
  });

  for (const decision of ["approve", "revoke"] as const) {
    app.openapi(decisionRoute(decision), async (c) => {
      const { installationId } = c.req.valid("param");
      const { reason } = c.req.valid("json");
      const installation = await call<z.infer<typeof ResultSchema>>(
        adminDb(c, deps),
        `sadid_${decision}_installation`,
        { p_installation: installationId, p_reason: reason },
      );
      return c.json({ ok: true as const, installation }, 200);
    });
  }
}
