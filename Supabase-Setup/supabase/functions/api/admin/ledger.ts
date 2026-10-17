// A store's ledger, one page at a time. Every read leaves an audit row with its reason.
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
  ReasonSchema,
  StoreIdParam,
} from "./common.ts";

const KINDS = ["contacts", "debts", "payments", "events"] as const;

const TotalsSchema = z.object({
  receivableCents: z.number().int(),
  payableCents: z.number().int(),
  netCents: z.number().int(),
});

const LedgerPageSchema = z
  .object({
    storeId: z.number().int(),
    kind: z.enum(KINDS),
    epoch: z.uuid(),
    latestSequence: z.number().int(),
    totals: TotalsSchema,
    rows: z.array(z.record(z.string(), z.unknown())).openapi({
      description:
        "contacts: id, name, phone, category, note, archived, receivableCents, payableCents, createdAt. debts: id, contactId, contactName, direction, originalCents, amountCents (after corrections), paidCents, remainingCents, note, dueDate, createdAt. payments: id, debtId, contactId, contactName, direction, amountCents, method, note, reversed, createdAt. events: sequence, operationId, kind, entityId, userId, installationId, generation, command, createdAt.",
    }),
    nextCursor: NextCursorSchema,
  })
  .openapi("AdminLedgerPage");

const ledgerRoute = createRoute({
  method: "get",
  path: "/admin/stores/{storeId}/ledger/{kind}",
  tags: ["admin"],
  security: [{ Bearer: [] }],
  description:
    "One page of contacts, debts or payments (newest first) or events (latest first), with the store's totals. Money is in cents. q matches part of the contact name, phone or note, or the event kind. Each read is audited with reason. admin and support.",
  middleware: [allow("admin", "support")] as const,
  request: {
    params: StoreIdParam.extend({ kind: z.enum(KINDS) }),
    query: z.object({
      ...pageQuery(200),
      reason: ReasonSchema.default("Store ledger in the admin dashboard"),
    }),
  },
  responses: {
    200: {
      description: "One page of the ledger",
      content: {
        "application/json": {
          schema: z.object({
            ok: z.literal(true),
            ledger: LedgerPageSchema,
          }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 500),
  },
});

export function registerLedgerRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps,
): void {
  app.openapi(ledgerRoute, async (c) => {
    const { storeId, kind } = c.req.valid("param");
    const { q, cursor, limit, reason } = c.req.valid("query");
    const page = await call<
      Omit<z.infer<typeof LedgerPageSchema>, "nextCursor"> & {
        nextCursor: unknown;
      }
    >(adminDb(c, deps), "sadid_admin_ledger", {
      p_store: storeId,
      p_kind: kind,
      p_reason: reason,
      p_query: q ?? null,
      p_after: decodeCursor(cursor),
      p_limit: limit,
    });
    return c.json({
      ok: true as const,
      ledger: { ...page, nextCursor: encodeCursor(page.nextCursor) },
    }, 200);
  });
}
