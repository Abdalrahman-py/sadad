// Recovery: lets a replacement device resend listed commands of a revoked one.
import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import { bodyLimit } from "hono/body-limit";
import type { AppEnv } from "../auth.ts";
import { ApiError, errorResponses } from "../errors.ts";
import { call } from "../rpc.ts";
import { adminDb, type AdminDeps, allow, ReasonSchema } from "./common.ts";

// sadid_approve_recovery refuses a manifest above 4,000,000 bytes.
const MAX_BODY_BYTES = 4_194_304;

const PermitSchema = z
  .object({
    permitId: z.uuid(),
    expiresAt: z.string(),
    manifestHash: z.string(),
  })
  .openapi("RecoveryPermit");

const approveRoute = createRoute({
  method: "post",
  path: "/admin/recovery",
  tags: ["admin"],
  security: [{ Bearer: [] }],
  description:
    "Approves a 24-hour permit for newInstallationId (active) to resend commands that oldInstallationId (revoked, same store, older generation) created. commands are the exact contact.create, debt.create and payment.create commands the old device holds. The device sends the permitId as x-sadad-recovery. admin only.",
  middleware: [
    allow("admin"),
    bodyLimit({
      maxSize: MAX_BODY_BYTES,
      onError: () => {
        throw new ApiError("payload_too_large", 413);
      },
    }),
  ] as const,
  request: {
    body: {
      required: true,
      content: {
        "application/json": {
          schema: z.strictObject({
            oldInstallationId: z.uuid(),
            newInstallationId: z.uuid(),
            commands: z.array(z.record(z.string(), z.unknown())).min(1).max(
              1000,
            ),
            reason: ReasonSchema,
          }),
        },
      },
    },
  },
  responses: {
    200: {
      description: "The permit",
      content: {
        "application/json": {
          schema: z.object({ ok: z.literal(true), permit: PermitSchema }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 409, 413, 500),
  },
});

export function registerRecoveryRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps,
): void {
  app.openapi(approveRoute, async (c) => {
    const { oldInstallationId, newInstallationId, commands, reason } = c.req
      .valid("json");
    const permit = await call<z.infer<typeof PermitSchema>>(
      adminDb(c, deps),
      "sadid_approve_recovery",
      {
        p_old: oldInstallationId,
        p_new: newInstallationId,
        p_manifest: commands,
        p_reason: reason,
      },
    );
    return c.json({ ok: true as const, permit }, 200);
  });
}
