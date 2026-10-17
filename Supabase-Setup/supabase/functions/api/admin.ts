// /v1/admin/*: the dashboard, behind Supabase Auth roles. admin reads and writes; support only reads.
// Every route also calls a routine that checks the role and the live Auth session in SQL.
import type { OpenAPIHono } from "@hono/zod-openapi";
import { registerAuditRoutes } from "./admin/audit.ts";
import { type AdminDeps, defaultDeps } from "./admin/common.ts";
import { registerInstallationRoutes } from "./admin/installations.ts";
import { registerLedgerRoutes } from "./admin/ledger.ts";
import { registerRecoveryRoutes } from "./admin/recovery.ts";
import { registerStoreRoutes } from "./admin/stores.ts";
import type { AppEnv } from "./auth.ts";

export type { AdminDeps, AuthAdmin } from "./admin/common.ts";

export function registerAdminRoutes(
  app: OpenAPIHono<AppEnv>,
  deps: AdminDeps = defaultDeps,
): void {
  registerStoreRoutes(app, deps);
  registerInstallationRoutes(app, deps);
  registerRecoveryRoutes(app, deps);
  registerLedgerRoutes(app, deps);
  registerAuditRoutes(app, deps);
}
