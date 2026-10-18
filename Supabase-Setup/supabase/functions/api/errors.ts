import type { Context } from "hono";
import type { ContentfulStatusCode } from "hono/utils/http-status";
import rawErrors from "../../../../contract/errors.json" with { type: "json" };

export const errorMessages = rawErrors;
export type ErrorCode = keyof typeof errorMessages;

const DEFAULT_STATUS: Record<ErrorCode, ContentfulStatusCode> = {
  authorization_required: 401,
  installation_or_session_revoked: 401,
  invalid_proof: 401,
  admin_authorization_required: 403,
  store_permission_denied: 403,
  installation_revoked: 403,
  store_suspended: 403,
  password_change_required: 403,
  current_password_incorrect: 403,
  recovery_not_authorized: 403,
  ledger_not_initialized: 403,
  history_is_immutable: 403,
  not_found: 404,
  store_not_found: 404,
  installation_unavailable: 404,
  contact_unavailable: 404,
  debt_unavailable: 404,
  payment_unavailable: 404,
  method_not_allowed: 405,
  dependency_pending: 409,
  dependency_rejected: 409,
  epoch_changed: 409,
  operation_id_reused: 409,
  replayed_request: 409,
  proof_replayed: 409,
  installation_key_changed: 409,
  session_installation_conflict: 409,
  duplicate_phone: 409,
  entity_id_exists: 409,
  entity_version_changed: 409,
  already_reversed: 409,
  below_paid_total: 409,
  overpayment: 409,
  contact_limit_reached: 409,
  device_limit_reached: 409,
  store_not_archived: 409,
  store_archived: 409,
  store_has_history: 409,
  username_taken: 409,
  subscription_inactive: 409,
  payload_too_large: 413,
  request_rate_limited: 429,
  enrollment_rate_limited: 429,
  invalid_input: 400,
  invalid_phone: 400,
  invalid_money: 400,
  invalid_timestamp: 400,
  reason_required: 400,
  unsupported_operation: 400,
  duplicate_operation_id: 400,
  unsupported_schema: 400,
  invalid_manifest: 400,
  invalid_dependencies: 400,
  invalid_operation: 400,
  invalid_public_key: 400,
  invalid_ids: 400,
  invalid_transfer: 400,
  invalid_scope: 400,
  delete_confirmation_required: 400,
  weak_password: 400,
  internal_error: 500,
  temporary_service_failure: 503,
};

export function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === "string" && Object.hasOwn(errorMessages, value);
}

export function statusForCode(code: ErrorCode): ContentfulStatusCode {
  return DEFAULT_STATUS[code] ?? 400;
}

export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: ContentfulStatusCode;

  constructor(code: ErrorCode, status?: ContentfulStatusCode) {
    super(errorMessages[code]);
    this.name = "ApiError";
    this.code = code;
    this.status = status ?? statusForCode(code);
  }
}

export function fromDatabaseError(err: unknown): ApiError | null {
  if (!err || typeof err !== "object") return null;
  const record = err as { code?: unknown; message?: unknown };
  if (isErrorCode(record.message)) {
    return new ApiError(record.message);
  }
  if (typeof record.code === "string" && record.code.startsWith("22")) {
    return new ApiError("invalid_input");
  }
  return null;
}

export type ErrorLogger = (entry: {
  requestId: string;
  error: string;
}) => void;

const defaultLogger: ErrorLogger = (entry) => {
  console.error(JSON.stringify(entry));
};

const ErrorRef = { $ref: "#/components/schemas/ErrorResponse" } as const;

/** OpenAPI responses for the contract errors a route can return. */
export function errorResponses(...statuses: number[]) {
  return Object.fromEntries(
    statuses.map((status) => [status, {
      description: "Contract error; code is listed in contract/errors.json",
      content: { "application/json": { schema: ErrorRef } },
    }]),
  );
}

/** The text with every secret this request registered (c.var.secrets) replaced. */
export function redact(c: Context, text: string): string {
  const secrets = (c.get("secrets") as string[] | undefined) ?? [];
  return secrets.reduce(
    (out, secret) => secret ? out.replaceAll(secret, "[redacted]") : out,
    text,
  );
}

export function handleError(
  err: unknown,
  c: Context,
  logError: ErrorLogger = defaultLogger,
): Response {
  const mapped = err instanceof ApiError ? err : fromDatabaseError(err);
  if (mapped) {
    return c.json(
      {
        ok: false,
        code: mapped.code,
        message: errorMessages[mapped.code],
      },
      mapped.status,
    );
  }

  const requestId = (c.get("requestId") as string | undefined) ||
    c.req.header("x-request-id") ||
    crypto.randomUUID();
  logError({
    requestId,
    error: redact(c, err instanceof Error ? err.message : String(err)),
  });
  return c.json(
    {
      ok: false,
      code: "internal_error" satisfies ErrorCode,
      message: errorMessages.internal_error,
      requestId,
    },
    500,
  );
}
