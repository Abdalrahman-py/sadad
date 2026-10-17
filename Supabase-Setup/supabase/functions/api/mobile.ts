// /v1/mobile/*: signed requests from an installation. Each request carries the store's
// Auth JWT and a P-256 proof over method, route, body digest, time and nonce.
import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { Context, MiddlewareHandler } from "hono";
import { bodyLimit } from "hono/body-limit";
import { type AppEnv, verifyCaller } from "./auth.ts";
import { ApiError, errorResponses } from "./errors.ts";
import { call, type RpcClient, unavailable } from "./rpc.ts";
import { readProofHeaders, verifyProof } from "./proof.ts";

/** The server key that signs offline leases; the app verifies leases with publicKey. */
export interface LeaseSigner {
  projectRef: string;
  signingKey: CryptoKey;
  publicKey: JsonWebKey;
}

export interface MobileDeps {
  /** Service role: reads installation keys and issues 60-second capabilities. */
  serviceDb: RpcClient;
  /** Acts as the caller's JWT; with a capability for ledger reads and writes. */
  userDb: (jwt: string, capability?: string) => RpcClient;
  lease?: LeaseSigner;
}

const MAX_BODY_BYTES = 65_536;
// sadid_issue_capability refuses a write request above 512 KiB.
const MAX_BATCH_BYTES = 524_288;
const MAX_BATCH = 100;
const LEASE_DAYS = 7;
const LEASE_ALLOWED = ["contact.create", "debt.create", "payment.create"];

/** The path after the function prefix: /api/v1/x and /functions/v1/api/v1/x are both /v1/x. */
export const routePath = (req: Request): string =>
  new URL(req.url).pathname.replace(/^(?:\/functions\/v1)?\/api(?=\/|$)/, "") ||
  "/";

/** The route text a device signs: the path from /v1 plus the query string. */
const signedRoute = (req: Request): string =>
  routePath(req) + new URL(req.url).search;

const defaultDeps: MobileDeps = {
  serviceDb: unavailable,
  userDb: () => unavailable,
};

interface StoreCaller {
  jwt: string;
  userId: string;
  sessionId: string;
}

async function storeCaller(c: Context<AppEnv>): Promise<StoreCaller> {
  const { jwt, userId, role, claims } = await verifyCaller(c);
  if (role !== "store") {
    throw new ApiError("store_permission_denied", 403);
  }
  const sessionId = claims.session_id;
  if (typeof sessionId !== "string" || !sessionId) {
    throw new ApiError("authorization_required", 401);
  }
  const appMeta = claims.app_metadata as Record<string, unknown> | undefined;
  if (appMeta?.force_password_change === true) {
    throw new ApiError("password_change_required", 403);
  }
  return { jwt, userId, sessionId };
}

interface InstallationKey {
  publicKey: JsonWebKey;
  storeId: number;
  generation: number;
}

type Scope = "read" | "write" | "lease";

interface SignedRequest {
  caller: StoreCaller;
  installation: InstallationKey;
  installationId: string;
  capability: string;
}

/** Checks the proof against the active installation's key, then issues a capability for one scope. */
async function signedRequest(
  c: Context<AppEnv>,
  deps: MobileDeps,
  scope: Scope,
  writeRequest?: unknown[],
): Promise<SignedRequest> {
  const raw = c.get("rawBody") ?? "";
  const caller = await storeCaller(c);
  const headers = readProofHeaders(c.req.raw);
  if (!headers) throw new ApiError("invalid_proof", 401);

  const installation = await call<InstallationKey | null>(
    deps.serviceDb,
    "sadid_installation_key",
    {
      p_user: caller.userId,
      p_session: caller.sessionId,
      p_installation: headers.installationId,
    },
  );
  if (!installation) {
    throw new ApiError("installation_or_session_revoked", 401);
  }

  const proof = await verifyProof(
    c.req.raw,
    signedRoute(c.req.raw),
    raw,
    installation.publicKey,
  );
  if (!proof) throw new ApiError("invalid_proof", 401);

  const recovery = c.req.header("x-sadad-recovery");
  if (recovery !== undefined && !z.uuid().safeParse(recovery).success) {
    throw new ApiError("invalid_input", 400);
  }
  const identity = {
    p_user: caller.userId,
    p_session: caller.sessionId,
    p_installation: proof.installationId,
    p_nonce: proof.nonce,
  };
  const ticket = scope === "write" && recovery
    ? await call<{ token: string }>(
      deps.serviceDb,
      "sadid_issue_recovery_capability",
      { ...identity, p_request: writeRequest, p_permit: recovery },
    )
    : await call<{ token: string }>(deps.serviceDb, "sadid_issue_capability", {
      ...identity,
      p_scope: scope,
      p_request: scope === "write" ? writeRequest : null,
    });

  return {
    caller,
    installation,
    installationId: proof.installationId,
    capability: ticket.token,
  };
}

const limitBody = (maxSize: number): MiddlewareHandler<AppEnv> =>
  bodyLimit({
    maxSize,
    onError: () => {
      throw new ApiError("payload_too_large", 413);
    },
  });

// The proof covers these exact bytes. Read them before the validator parses the JSON, rather
// than relying on Hono returning the original text from its body cache afterwards.
const keepRawBody: MiddlewareHandler<AppEnv> = async (c, next) => {
  c.set("rawBody", await c.req.text());
  await next();
};

const signedDescription =
  "Signed: send x-sadad-installation, x-sadad-time (13-digit epoch ms), x-sadad-nonce (UUID) and x-sadad-signature (raw r||s, base64url) over contract/fixtures/mobile/proof.json's text. The signed route is the path from /v1 plus the query string.";

const PublicKeySchema = z
  .strictObject({
    kty: z.literal("EC"),
    crv: z.literal("P-256"),
    x: z.string().length(43),
    y: z.string().length(43),
  })
  .openapi("InstallationPublicKey");

const InstallationSchema = z
  .object({
    installationId: z.uuid(),
    generation: z.number().int(),
    status: z.enum(["pending", "active", "revoked"]),
    label: z.string(),
    staffName: z.string(),
    permissions: z.record(z.string(), z.boolean()),
  })
  .openapi("Installation");

const ReceiptSchema = z
  .object({
    operationId: z.uuid(),
    entityId: z.uuid(),
    status: z.enum(["accepted", "rejected"]),
    code: z.string().nullable(),
    sequence: z.number().int(),
    epoch: z.uuid(),
    serverTime: z.string(),
  })
  .openapi("Receipt");

const LedgerCommandSchema = z
  .record(z.string(), z.unknown())
  .openapi("LedgerCommand", {
    description:
      "One ledger command (schemaVersion 1); validated by sadid_private.apply_one.",
  });

const ChangeEventSchema = z
  .object({
    sequence: z.number().int(),
    operationId: z.uuid(),
    kind: z.string(),
    entityId: z.uuid(),
    userId: z.uuid(),
    installationId: z.uuid(),
    generation: z.number().int(),
    command: LedgerCommandSchema,
    result: ReceiptSchema,
    createdAt: z.string(),
  })
  .openapi("ChangeEvent");

const ChangesSchema = z
  .object({
    schemaVersion: z.number().int(),
    storeId: z.number().int(),
    epoch: z.uuid(),
    cursor: z.number().int(),
    nextCursor: z.number().int(),
    hasMore: z.boolean(),
    currency: z.string(),
    events: z.array(ChangeEventSchema),
  })
  .openapi("Changes");

const LeaseSchema = z
  .object({
    payload: z.string().openapi({
      description:
        "base64url of the JSON lease: schemaVersion, projectRef, storeId, userId, installationId, generation, issuedAt, expiresAt, allowed",
    }),
    signature: z.string().openapi({
      description: "ECDSA P-256 SHA-256 over the payload bytes, raw r||s",
    }),
    publicKey: z.record(z.string(), z.string()),
  })
  .openapi("Lease");

const enrollRoute = createRoute({
  method: "post",
  path: "/mobile/enroll",
  tags: ["mobile"],
  security: [{ Bearer: [] }],
  description:
    `${signedDescription} Registers this installation as pending, signed by the key it registers. Call it only while the installation is not active.`,
  middleware: [limitBody(MAX_BODY_BYTES), keepRawBody] as const,
  request: {
    body: {
      required: true,
      content: {
        "application/json": {
          schema: z.strictObject({
            publicKey: PublicKeySchema,
            label: z.string().max(120).optional(),
            staffName: z.string().max(80).optional(),
          }),
        },
      },
    },
  },
  responses: {
    200: {
      description: "The installation, pending until an admin approves it",
      content: {
        "application/json": {
          schema: z.object({
            ok: z.literal(true),
            installation: InstallationSchema,
          }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 409, 413, 429, 500),
  },
});

const leaseRoute = createRoute({
  method: "post",
  path: "/mobile/lease",
  tags: ["mobile"],
  security: [{ Bearer: [] }],
  description:
    `${signedDescription} A ${LEASE_DAYS}-day server-signed offline lease. Renew only when under 24 hours remain. The body is empty.`,
  middleware: [limitBody(MAX_BODY_BYTES), keepRawBody] as const,
  responses: {
    200: {
      description: "The signed lease",
      content: {
        "application/json": {
          schema: z.object({ ok: z.literal(true), lease: LeaseSchema }),
        },
      },
    },
    ...errorResponses(401, 403, 409, 429, 500),
  },
});

const changesRoute = createRoute({
  method: "get",
  path: "/mobile/changes",
  tags: ["mobile"],
  security: [{ Bearer: [] }],
  description:
    `${signedDescription} Ledger events after the cursor, oldest first. Start from after=0 on first sync or after an epoch change; then pass nextCursor while hasMore.`,
  request: {
    query: z.object({
      after: z.coerce.number().int().min(0).default(0),
      limit: z.coerce.number().int().min(1).max(500).default(200),
    }),
  },
  responses: {
    200: {
      description: "One page of events",
      content: {
        "application/json": {
          schema: z.object({ ok: z.literal(true), changes: ChangesSchema }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 409, 429, 500),
  },
});

const operationsRoute = createRoute({
  method: "post",
  path: "/mobile/operations",
  tags: ["mobile"],
  security: [{ Bearer: [] }],
  description:
    `${signedDescription} Applies up to ${MAX_BATCH} commands in one transaction and returns one receipt each, in order. Resending a command returns its first receipt. A replacement installation resending a revoked one's commands sends x-sadad-recovery with the admin-approved permit id.`,
  middleware: [limitBody(MAX_BATCH_BYTES), keepRawBody] as const,
  request: {
    body: {
      required: true,
      content: {
        "application/json": {
          schema: z.strictObject({
            operations: z.array(LedgerCommandSchema).min(1).max(MAX_BATCH),
          }),
        },
      },
    },
  },
  responses: {
    200: {
      description: "One receipt per command",
      content: {
        "application/json": {
          schema: z.object({
            ok: z.literal(true),
            receipts: z.array(ReceiptSchema),
          }),
        },
      },
    },
    ...errorResponses(400, 401, 403, 409, 413, 429, 500),
  },
});

const encoder = new TextEncoder();

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export function registerMobileRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: MobileDeps = defaultDeps,
): void {
  app.openapi(enrollRoute, async (c) => {
    const raw = c.get("rawBody") ?? "";
    const body = c.req.valid("json");
    const caller = await storeCaller(c);
    const proof = await verifyProof(
      c.req.raw,
      signedRoute(c.req.raw),
      raw,
      body.publicKey,
    );
    if (!proof) throw new ApiError("invalid_proof", 401);

    const installation = await call<z.infer<typeof InstallationSchema>>(
      deps.userDb(caller.jwt),
      "sadid_enroll",
      {
        p_installation: proof.installationId,
        p_key: body.publicKey,
        p_nonce: proof.nonce,
        p_label: body.label ?? "",
        p_staff_name: body.staffName ?? "",
      },
    );
    return c.json({ ok: true as const, installation }, 200);
  });

  app.openapi(leaseRoute, async (c) => {
    if (!deps.lease) throw new ApiError("temporary_service_failure");
    const signed = await signedRequest(c, deps, "lease");
    const issuedAt = Date.now();
    const payload = encoder.encode(JSON.stringify({
      schemaVersion: 1,
      projectRef: deps.lease.projectRef,
      storeId: signed.installation.storeId,
      userId: signed.caller.userId,
      installationId: signed.installationId,
      generation: signed.installation.generation,
      issuedAt,
      expiresAt: issuedAt + LEASE_DAYS * 86_400_000,
      allowed: LEASE_ALLOWED,
    }));
    const signature = new Uint8Array(
      await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        deps.lease.signingKey,
        payload,
      ),
    );
    const { kty, crv, x, y } = deps.lease.publicKey;
    return c.json({
      ok: true as const,
      lease: {
        payload: base64Url(payload),
        signature: base64Url(signature),
        publicKey: { kty: kty!, crv: crv!, x: x!, y: y! },
      },
    }, 200);
  });

  app.openapi(changesRoute, async (c) => {
    const { after, limit } = c.req.valid("query");
    const signed = await signedRequest(c, deps, "read");
    const changes = await call<z.infer<typeof ChangesSchema>>(
      deps.userDb(signed.caller.jwt, signed.capability),
      "sadid_changes",
      { p_after: after, p_limit: limit },
    );
    return c.json({ ok: true as const, changes }, 200);
  });

  app.openapi(operationsRoute, async (c) => {
    const { operations } = c.req.valid("json");
    const signed = await signedRequest(c, deps, "write", operations);
    const receipts = await call<z.infer<typeof ReceiptSchema>[]>(
      deps.userDb(signed.caller.jwt, signed.capability),
      "sadid_apply_batch",
      { p_commands: operations },
    );
    return c.json({ ok: true as const, receipts }, 200);
  });
}
