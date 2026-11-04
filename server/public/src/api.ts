// The typed client for /v1/admin/*. Types come from contract/openapi.json (npm run gen:api).
import createClient from "openapi-fetch";
import { API_BASE, PUBLISHABLE_KEY } from "./config";
import { ApiFailure, failure, networkFailure } from "./errors";
import type { components, paths } from "./generated/api";

export type Schemas = components["schemas"];
export type Store = Schemas["AdminStore"];
export type StoreRow = Store & { summary: Schemas["AdminStoreSummary"] };
export type StoreDetail = Schemas["AdminStoreDetail"];
export type DetailInstallation = StoreDetail["installations"][number];
export type Overview = Schemas["AdminOverview"];
export type PendingInstallation = Schemas["AdminInstallation"] & { storeName: string };
export type LedgerKind = "contacts" | "debts" | "payments" | "events";
export type LedgerPage = Schemas["AdminLedgerPage"];
export type AuditEvent = Schemas["AdminAuditEvent"];
export type AuditSource = "control" | "access";
export type StoreAction = "archive" | "unarchive" | "end-sessions" | "revoke-all";
export type UpdateStore = Schemas["AdminUpdateStore"];
export type CreateStore = Schemas["AdminCreateStore"];
export type Subscription = Schemas["AdminSubscription"];

type Result<T> = { data?: T; error?: unknown; response: Response };

export interface ApiOptions {
  /** The current access token, refreshed by Auth when needed. */
  jwt: () => Promise<string | null>;
  /** Called when the api refuses the token itself (401). */
  onUnauthorized: () => void;
  fetch?: typeof fetch;
}

export function createApi(options: ApiOptions) {
  const client = createClient<paths>({
    baseUrl: API_BASE,
    fetch: (request) => (options.fetch ?? globalThis.fetch)(request),
  });
  client.use({
    async onRequest({ request }) {
      const jwt = await options.jwt();
      request.headers.set("apikey", PUBLISHABLE_KEY);
      if (jwt) request.headers.set("Authorization", `Bearer ${jwt}`);
      return request;
    },
  });

  async function unwrap<T>(pending: Promise<Result<T>>): Promise<T> {
    let result: Result<T>;
    try {
      result = await pending;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      throw networkFailure();
    }
    if (result.data !== undefined && result.response.ok) return result.data;
    const body = (result.error ?? {}) as { code?: unknown; message?: unknown };
    const status = result.response.status;
    if (status === 401) options.onUnauthorized();
    if (typeof body.code === "string" && typeof body.message === "string") {
      throw new ApiFailure(body.code, body.message, status);
    }
    throw failure("temporary_service_failure", status);
  }

  const storePath = (storeId: number) => ({ path: { storeId } });

  return {
    overview: (signal?: AbortSignal) =>
      unwrap(client.GET("/v1/admin/overview", { signal })).then((r) => r.overview),
    stores: (
      query: { q?: string; cursor?: string; includeArchived: boolean },
      signal?: AbortSignal,
    ) =>
      unwrap(
        client.GET("/v1/admin/stores", {
          params: {
            query: {
              q: query.q || undefined,
              cursor: query.cursor,
              limit: 100,
              includeArchived: query.includeArchived ? "true" : "false",
            },
          },
          signal,
        }),
      ),
    createStore: (body: CreateStore) => unwrap(client.POST("/v1/admin/stores", { body })),
    store: (storeId: number, signal?: AbortSignal) =>
      unwrap(client.GET("/v1/admin/stores/{storeId}", { params: storePath(storeId), signal })).then(
        (r) => r.detail,
      ),
    updateStore: (storeId: number, body: UpdateStore) =>
      unwrap(client.PATCH("/v1/admin/stores/{storeId}", { params: storePath(storeId), body })),
    deleteStore: (storeId: number, confirm: string) =>
      unwrap(
        client.DELETE("/v1/admin/stores/{storeId}", {
          params: { path: { storeId }, query: { confirm } },
        }),
      ),
    subscription: (storeId: number, body: Subscription) =>
      unwrap(
        client.POST("/v1/admin/stores/{storeId}/subscription", {
          params: storePath(storeId),
          body,
        }),
      ),
    resetPassword: (storeId: number, reason?: string) =>
      unwrap(
        client.POST("/v1/admin/stores/{storeId}/password", {
          params: storePath(storeId),
          body: reason ? { reason } : {},
        }),
      ),
    storeAction: (storeId: number, action: StoreAction, reason: string) =>
      unwrap(
        client.POST("/v1/admin/stores/{storeId}/actions/{action}", {
          params: { path: { storeId, action } },
          body: { reason },
        }),
      ),
    pendingInstallations: (query: { q?: string; cursor?: string }, signal?: AbortSignal) =>
      unwrap(
        client.GET("/v1/admin/installations", {
          params: { query: { q: query.q || undefined, cursor: query.cursor, limit: 100 } },
          signal,
        }),
      ),
    decideInstallation: (installationId: string, decision: "approve" | "revoke", reason: string) =>
      unwrap(
        decision === "approve"
          ? client.POST("/v1/admin/installations/{installationId}/approve", {
              params: { path: { installationId } },
              body: { reason },
            })
          : client.POST("/v1/admin/installations/{installationId}/revoke", {
              params: { path: { installationId } },
              body: { reason },
            }),
      ),
    approveRecovery: (body: {
      oldInstallationId: string;
      newInstallationId: string;
      commands: Record<string, unknown>[];
      reason: string;
    }) => unwrap(client.POST("/v1/admin/recovery", { body })).then((r) => r.permit),
    ledger: (
      storeId: number,
      kind: LedgerKind,
      query: { q?: string; cursor?: string; reason: string; limit?: number },
      signal?: AbortSignal,
    ) =>
      unwrap(
        client.GET("/v1/admin/stores/{storeId}/ledger/{kind}", {
          params: {
            path: { storeId, kind },
            query: {
              q: query.q || undefined,
              cursor: query.cursor,
              limit: query.limit ?? 50,
              reason: query.reason,
            },
          },
          signal,
        }),
      ).then((r) => r.ledger),
    /** Claims the account for this Auth session, or renews it; another browser gets 409. */
    claimSession: () => unwrap(client.POST("/v1/admin/session")),
    /** Frees the account at sign-out so the next browser can sign in at once. */
    releaseSession: () => unwrap(client.DELETE("/v1/admin/session")),
    audit: (
      query: { source: AuditSource; storeId?: number; q?: string; cursor?: string },
      signal?: AbortSignal,
    ) =>
      unwrap(
        client.GET("/v1/admin/audit", {
          params: {
            query: {
              source: query.source,
              storeId: query.storeId,
              q: query.q || undefined,
              cursor: query.cursor,
              limit: 50,
            },
          },
          signal,
        }),
      ),
  };
}

export type Api = ReturnType<typeof createApi>;
