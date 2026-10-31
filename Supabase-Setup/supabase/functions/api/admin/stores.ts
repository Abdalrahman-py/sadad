// Store accounts: list and search, create, settings, subscription, password reset, account actions, delete.
import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { Context } from "hono";
import type { AppEnv } from "../auth.ts";
import { ApiError, errorResponses } from "../errors.ts";
import { call } from "../rpc.ts";
import {
  adminDb,
  type AdminDeps,
  adminRefusals,
  allow,
  callPage,
  InstallationSchema,
  keepSecret,
  NextCursorSchema,
  pageQuery,
  ReasonSchema,
  security,
  StoreIdParam,
  tags,
} from "./common.ts";

export const STORE_EMAIL_DOMAIN = "stores.sadad.invalid";

const storeEmail = (username: string) =>
  `${username.toLowerCase()}@${STORE_EMAIL_DOMAIN}`;

const PERMISSIONS = [
  "contacts",
  "debts",
  "payments",
  "reports",
  "analytics",
  "export",
  "whatsapp",
  "appLock",
] as const;

const SubscriptionMode = z.enum(["permanent", "timed", "paused"]);

const StoreSchema = z
  .object({
    id: z.number().int(),
    name: z.string(),
    username: z.string(),
    status: z.enum(["active", "suspended", "deleted"]),
    archived: z.boolean(),
    verified: z.boolean(),
    debtorLimit: z.number().int(),
    allowedDevices: z.number().int(),
    permissions: z.record(z.string(), z.boolean()),
    revision: z.number().int(),
    createdAt: z.number().int(),
    subscriptionMode: SubscriptionMode,
    subscriptionActive: z.boolean(),
    subscriptionStartedAt: z.number().int().nullable(),
    subscriptionExpiresAt: z.number().int().nullable(),
    subscriptionRemainingMs: z.number().int().nullable(),
    activeDevices: z.number().int(),
    pendingDevices: z.number().int(),
  })
  .openapi("AdminStore");

const SummarySchema = z
  .object({
    storeId: z.number().int(),
    initialized: z.boolean(),
    contacts: z.number().int().optional(),
    activeContacts: z.number().int().optional(),
    debts: z.number().int().optional(),
    openDebts: z.number().int().optional(),
    debtTotalCents: z.number().int().optional(),
    receivableCents: z.number().int().optional(),
    payableCents: z.number().int().optional(),
    payments: z.number().int().optional(),
  })
  .openapi("AdminStoreSummary");

const OverviewSchema = z
  .object({
    stores: z.number().int(),
    active: z.number().int(),
    suspended: z.number().int(),
    archived: z.number().int(),
    verified: z.number().int(),
    pendingDevices: z.number().int(),
  })
  .openapi("AdminOverview");

const RecoveryPermitSchema = z.object({
  permitId: z.uuid(),
  oldInstallationId: z.uuid(),
  newInstallationId: z.uuid(),
  approvedBy: z.string(),
  reason: z.string(),
  expiresAt: z.string(),
  createdAt: z.string(),
});

const StoreDetailSchema = z
  .object({
    store: StoreSchema,
    hasDeviceHistory: z.boolean(),
    ledgerInitialized: z.boolean(),
    canDelete: z.boolean(),
    installations: z.array(InstallationSchema.extend({
      approvedAt: z.string().nullable(),
      approvedBy: z.string().nullable(),
      revokedAt: z.string().nullable(),
      reason: z.string().nullable(),
    })),
    recoveryPermits: z.array(RecoveryPermitSchema),
  })
  .openapi("AdminStoreDetail");

// The same rules sadid_private.store_settings enforces; the routine stays the authority.
const settings = {
  name: z.string().trim().min(1).max(120),
  username: z.string().regex(/^[A-Za-z0-9._-]{3,64}$/),
  verified: z.boolean(),
  debtorLimit: z.number().int().min(0).max(1_000_000),
  allowedDevices: z.number().int().min(1).max(50),
  permissions: z.partialRecord(z.enum(PERMISSIONS), z.boolean()),
};

const SubscriptionDays = z.number().int().min(1).max(36500);

const CreateStoreSchema = z
  .strictObject({
    name: settings.name,
    username: settings.username,
    subscriptionMode: SubscriptionMode,
    subscriptionDays: SubscriptionDays.optional(),
    verified: settings.verified.optional(),
    debtorLimit: settings.debtorLimit.optional(),
    allowedDevices: settings.allowedDevices.optional(),
    permissions: settings.permissions.optional(),
  })
  .refine((b) => b.subscriptionMode !== "timed" || b.subscriptionDays, {
    path: ["subscriptionDays"],
  })
  .openapi("AdminCreateStore");

const UpdateStoreSchema = z
  .strictObject({
    name: settings.name.optional(),
    username: settings.username.optional(),
    verified: settings.verified.optional(),
    debtorLimit: settings.debtorLimit.optional(),
    allowedDevices: settings.allowedDevices.optional(),
    permissions: settings.permissions.optional(),
    status: z.enum(["active", "suspended"]).optional(),
  })
  .refine((b) => Object.keys(b).length > 0)
  .openapi("AdminUpdateStore");

const SubscriptionSchema = z
  .strictObject({
    mode: SubscriptionMode,
    days: SubscriptionDays.optional(),
    extend: z.boolean().optional().openapi({
      description:
        "timed only: add the days to the time left instead of starting today",
    }),
  })
  .refine((b) => b.mode !== "timed" || b.days, { path: ["days"] })
  .openapi("AdminSubscription");

const ACTIONS = ["archive", "unarchive", "end-sessions", "revoke-all"] as const;

const json = <T extends z.ZodType>(schema: T) => ({
  content: { "application/json": { schema } },
});

const body = <T extends z.ZodType>(schema: T) => ({
  body: { required: true, ...json(schema) },
});

const ok = <T extends z.ZodRawShape>(description: string, shape: T) => ({
  description,
  ...json(z.object({ ok: z.literal(true), ...shape })),
});

const overviewRoute = createRoute({
  method: "get",
  path: "/admin/overview",
  tags,
  security,
  description: "Counts for the dashboard header. admin and support.",
  middleware: [allow("admin", "support")] as const,
  responses: {
    200: ok("Store and device counts", { overview: OverviewSchema }),
    ...errorResponses(
      ...adminRefusals,
      "internal_error",
    ),
  },
});

const listRoute = createRoute({
  method: "get",
  path: "/admin/stores",
  tags,
  security,
  description:
    "Stores newest first, with each store's balances (an audited read). q matches part of the name or username. admin and support.",
  middleware: [allow("admin", "support")] as const,
  request: {
    query: z.object({
      ...pageQuery(100),
      includeArchived: z.enum(["true", "false"]).default("false"),
    }),
  },
  responses: {
    200: ok("One page of stores", {
      stores: z.array(StoreSchema.extend({ summary: SummarySchema })),
      nextCursor: NextCursorSchema,
    }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "internal_error",
    ),
  },
});

const createStoreRoute = createRoute({
  method: "post",
  path: "/admin/stores",
  tags,
  security,
  description:
    "Creates the Auth user <username>@stores.sadad.invalid with a generated temporary password and force_password_change, then the store. The password is returned once and never logged. admin only.",
  middleware: [allow("admin")] as const,
  request: body(CreateStoreSchema),
  responses: {
    201: ok("The new store and its temporary password", {
      storeId: z.number().int(),
      username: z.string(),
      temporaryPassword: z.string(),
    }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "username_taken",
      "internal_error",
    ),
  },
});

const detailRoute = createRoute({
  method: "get",
  path: "/admin/stores/{storeId}",
  tags,
  security,
  description:
    "One store with its devices and recovery permits. admin and support.",
  middleware: [allow("admin", "support")] as const,
  request: { params: StoreIdParam },
  responses: {
    200: ok("The store", { detail: StoreDetailSchema }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "store_not_found",
      "internal_error",
    ),
  },
});

const updateRoute = createRoute({
  method: "patch",
  path: "/admin/stores/{storeId}",
  tags,
  security,
  description:
    "Changes settings or freezes and unfreezes the store. Suspending ends its sessions. allowedDevices below the active device count is refused; revoke a device first. A new username also changes the Auth email. admin only.",
  middleware: [allow("admin")] as const,
  request: { params: StoreIdParam, ...body(UpdateStoreSchema) },
  responses: {
    200: ok("The store", { store: StoreSchema }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "store_not_found",
      "username_taken",
      "internal_error",
    ),
  },
});

const subscriptionRoute = createRoute({
  method: "post",
  path: "/admin/stores/{storeId}/subscription",
  tags,
  security,
  description:
    "permanent and timed activate the store; paused suspends it, ends its sessions and keeps a timed store's time left. admin only.",
  middleware: [allow("admin")] as const,
  request: { params: StoreIdParam, ...body(SubscriptionSchema) },
  responses: {
    200: ok("The store", { store: StoreSchema }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "store_not_found",
      "store_archived",
      "internal_error",
    ),
  },
});

const passwordRoute = createRoute({
  method: "post",
  path: "/admin/stores/{storeId}/password",
  tags,
  security,
  description:
    "Ends the store's sessions and sets a generated temporary password with force_password_change. The password is returned once and never logged. admin only.",
  middleware: [allow("admin")] as const,
  request: {
    params: StoreIdParam,
    ...body(z.strictObject({ reason: ReasonSchema.optional() })),
  },
  responses: {
    200: ok("The temporary password", {
      username: z.string(),
      temporaryPassword: z.string(),
    }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "store_not_found",
      "store_archived",
      "internal_error",
    ),
  },
});

const actionRoute = createRoute({
  method: "post",
  path: "/admin/stores/{storeId}/actions/{action}",
  tags,
  security,
  description:
    "archive (revokes devices and ends sessions), unarchive (back as suspended), end-sessions, revoke-all (devices). admin only.",
  middleware: [allow("admin")] as const,
  request: {
    params: StoreIdParam.extend({ action: z.enum(ACTIONS) }),
    ...body(z.strictObject({ reason: ReasonSchema })),
  },
  responses: {
    200: ok("Done", {
      result: z.object({ storeId: z.number().int(), action: z.enum(ACTIONS) }),
    }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "store_not_found",
      "store_not_archived",
      "internal_error",
    ),
  },
});

const deleteRoute = createRoute({
  method: "delete",
  path: "/admin/stores/{storeId}",
  tags,
  security,
  description:
    "Deletes a store that never had a device or a ledger, and its Auth user. confirm is the store's username. admin only.",
  middleware: [allow("admin")] as const,
  request: {
    params: StoreIdParam,
    query: z.object({ confirm: z.string().max(80).default("") }),
  },
  responses: {
    200: ok("Deleted", { deleted: z.literal(true) }),
    ...errorResponses(
      ...adminRefusals,
      "invalid_input",
      "store_not_found",
      "store_has_history",
      "internal_error",
    ),
  },
});

type Store = z.infer<typeof StoreSchema>;
type Summary = z.infer<typeof SummarySchema>;

/** 24 base64url characters from 18 random bytes. */
export function temporaryPassword(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function authFailure(error: unknown): Error {
  const record = (error ?? {}) as { code?: unknown; message?: unknown };
  if (record.code === "email_exists" || record.code === "user_already_exists") {
    return new ApiError("username_taken");
  }
  return new Error(`auth admin: ${String(record.message ?? error)}`);
}

export function registerStoreRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps,
): void {
  // The store-side routines check role and session themselves, but no routine guards the
  // Auth admin calls that run with the service key, so each Auth admin call below confirms
  // a live admin session through SQL first.
  const confirmAdminSession = async (
    c: Context<AppEnv>,
  ): Promise<void> => {
    const role = await call<string | null>(
      adminDb(c, deps),
      "sadid_admin_role",
    );
    if (role !== "admin") {
      throw new ApiError("admin_authorization_required", 403);
    }
  };

  app.openapi(overviewRoute, async (c) => {
    const overview = await call<z.infer<typeof OverviewSchema>>(
      adminDb(c, deps),
      "sadid_admin_overview",
    );
    return c.json({ ok: true as const, overview }, 200);
  });

  app.openapi(listRoute, async (c) => {
    const { q, cursor, limit, includeArchived } = c.req.valid("query");
    const db = adminDb(c, deps);
    const page = await callPage<{ stores: Store[]; nextCursor: unknown }>(
      db,
      "sadid_admin_stores",
      { q, cursor, limit },
      { p_include_archived: includeArchived === "true" },
    );
    const summaries = page.stores.length
      ? await call<Summary[]>(db, "sadid_admin_summaries", {
        p_stores: page.stores.map((store) => store.id),
      })
      : [];
    const byStore = new Map(summaries.map((s) => [s.storeId, s]));
    return c.json({
      ok: true as const,
      stores: page.stores.map((store) => ({
        ...store,
        summary: byStore.get(store.id) ??
          { storeId: store.id, initialized: false },
      })),
      nextCursor: page.nextCursor,
    }, 200);
  });

  app.openapi(createStoreRoute, async (c) => {
    const { name, username, ...rest } = c.req.valid("json");
    await confirmAdminSession(c);

    const password = temporaryPassword();
    keepSecret(c, password);
    const created = await deps.authAdmin.createUser({
      email: storeEmail(username),
      password,
      email_confirm: true,
      app_metadata: { role: "store", force_password_change: true },
    });
    const user = created.data.user;
    if (created.error || !user) throw authFailure(created.error);

    let storeId: number;
    try {
      storeId = await call<number>(deps.serviceDb, "sadid_create_store", {
        p_user: user.id,
        p_name: name,
        p_username: username,
        p_settings: rest,
      });
    } catch (error) {
      await deps.authAdmin.deleteUser(user.id);
      throw error;
    }
    return c.json({
      ok: true as const,
      storeId: Number(storeId),
      username,
      temporaryPassword: password,
    }, 201);
  });

  app.openapi(detailRoute, async (c) => {
    const { storeId } = c.req.valid("param");
    const detail = await call<z.infer<typeof StoreDetailSchema>>(
      adminDb(c, deps),
      "sadid_admin_controls",
      { p_store: storeId },
    );
    return c.json({ ok: true as const, detail }, 200);
  });

  app.openapi(updateRoute, async (c) => {
    const { storeId } = c.req.valid("param");
    const changes = c.req.valid("json");
    await confirmAdminSession(c);
    const db = adminDb(c, deps);
    const result = await call<
      { store: Store; userId: string | null; previousUsername: string }
    >(db, "sadid_admin_update_store", {
      p_store: storeId,
      p_changes: changes,
    });

    const renamed = changes.username !== undefined &&
      storeEmail(changes.username) !== storeEmail(result.previousUsername);
    if (renamed && result.userId) {
      const updated = await deps.authAdmin.updateUserById(result.userId, {
        email: storeEmail(changes.username!),
        email_confirm: true,
      });
      if (updated.error) {
        // Put the old username back so the store and its Auth email still match.
        await call(db, "sadid_admin_update_store", {
          p_store: storeId,
          p_changes: { username: result.previousUsername },
        });
        throw authFailure(updated.error);
      }
    }
    return c.json({ ok: true as const, store: result.store }, 200);
  });

  app.openapi(subscriptionRoute, async (c) => {
    const { storeId } = c.req.valid("param");
    const { mode, days, extend } = c.req.valid("json");
    const store = await call<Store>(
      adminDb(c, deps),
      "sadid_admin_subscription",
      {
        p_store: storeId,
        p_mode: mode,
        p_days: days ?? null,
        p_extend: extend ?? false,
      },
    );
    return c.json({ ok: true as const, store }, 200);
  });

  app.openapi(passwordRoute, async (c) => {
    const { storeId } = c.req.valid("param");
    const { reason } = c.req.valid("json");
    await confirmAdminSession(c);
    // The order stays routine-first: sadid_admin_reset_password is the only source of the
    // store's userId, and its two effects — end_store_sessions and the account.password-reset
    // audit row — cannot be reversed once it has run (no reverse routine, and only Auth holds
    // the old password, hashed). They are also the safe direction: after a refused update the
    // old password still signs the store owner in, and a retry re-ends the already-ended
    // sessions. So a refused Auth update fails loudly and leaves the routine's state standing.
    const account = await call<{ userId: string; username: string }>(
      adminDb(c, deps),
      "sadid_admin_reset_password",
      {
        p_store: storeId,
        p_reason: reason ?? "Store password reset in the admin dashboard",
      },
    );

    const password = temporaryPassword();
    keepSecret(c, password);
    const updated = await deps.authAdmin.updateUserById(account.userId, {
      password,
      app_metadata: { force_password_change: true },
    });
    if (updated.error) throw authFailure(updated.error);
    return c.json({
      ok: true as const,
      username: account.username,
      temporaryPassword: password,
    }, 200);
  });

  app.openapi(actionRoute, async (c) => {
    const { storeId, action } = c.req.valid("param");
    const { reason } = c.req.valid("json");
    const result = await call<{ storeId: number; action: typeof action }>(
      adminDb(c, deps),
      "sadid_admin_account_action",
      { p_store: storeId, p_action: action, p_reason: reason },
    );
    return c.json({ ok: true as const, result }, 200);
  });

  app.openapi(deleteRoute, async (c) => {
    const { storeId } = c.req.valid("param");
    const { confirm } = c.req.valid("query");
    await confirmAdminSession(c);
    // sadid_admin_delete_store has already removed the row when deleteUser below is reached,
    // and it refuses a retry (store_not_found), so nothing can compensate a failed Auth
    // deletion: the Auth user is left without a store. It can start no session and see no
    // ledger without the membership row, so the orphan waits until a human prunes it in Auth.
    const deleted = await call<{ userId: string | null }>(
      adminDb(c, deps),
      "sadid_admin_delete_store",
      { p_store: storeId, p_confirm: confirm },
    );
    if (deleted.userId) {
      const { error } = await deps.authAdmin.deleteUser(deleted.userId);
      if (error) throw authFailure(error);
    }
    return c.json({ ok: true as const, deleted: true as const }, 200);
  });
}
