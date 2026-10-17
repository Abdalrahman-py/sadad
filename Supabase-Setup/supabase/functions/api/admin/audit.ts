// The admin audit trail: admin actions (control) and audited ledger reads (access).
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
  NextCursorSchema,
  pageQuery,
} from "./common.ts";

const AuditEventSchema = z
  .object({
    id: z.union([z.number().int(), z.uuid()]),
    storeId: z.number().int(),
    actor: z.string(),
    kind: z.string().optional(),
    reason: z.string(),
    details: z.record(z.string(), z.unknown()).optional(),
    outcome: z.string().optional(),
    recordCounts: z.record(z.string(), z.number()).optional(),
    createdAt: z.string(),
  })
  .openapi("AdminAuditEvent");

const auditRoute = createRoute({
  method: "get",
  path: "/admin/audit",
  tags: ["admin"],
  security: [{ Bearer: [] }],
  description:
    "Newest first. source=control: admin actions (kind, reason, details). source=access: audited ledger reads (reason, recordCounts). storeId narrows to one store; q matches part of the actor, kind or reason. admin and support.",
  middleware: [allow("admin", "support")] as const,
  request: {
    query: z.object({
      source: z.enum(["control", "access"]).default("control"),
      storeId: z.coerce.number().int().positive().optional(),
      ...pageQuery(200),
    }),
  },
  responses: {
    200: {
      description: "One page of audit events",
      content: {
        "application/json": {
          schema: z.object({
            ok: z.literal(true),
            events: z.array(AuditEventSchema),
            nextCursor: NextCursorSchema,
          }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 500),
  },
});

export function registerAuditRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps,
): void {
  app.openapi(auditRoute, async (c) => {
    const { source, storeId, q, cursor, limit } = c.req.valid("query");
    const page = await call<
      { events: z.infer<typeof AuditEventSchema>[]; nextCursor: unknown }
    >(adminDb(c, deps), "sadid_admin_audit", {
      p_source: source,
      p_store: storeId ?? null,
      p_query: q ?? null,
      p_after: decodeCursor(cursor),
      p_limit: limit,
    });
    return c.json({
      ok: true as const,
      events: page.events,
      nextCursor: encodeCursor(page.nextCursor),
    }, 200);
  });
}
