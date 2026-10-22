import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi";
import { cors } from "hono/cors";
import { type AccountDeps, registerAccountRoutes } from "./account.ts";
import { type AdminDeps, registerAdminRoutes } from "./admin.ts";
import { type AppEnv, type LookupStoreId, type VerifyClaims } from "./auth.ts";
import {
  ApiError,
  type ErrorCode,
  type ErrorLogger,
  errorMessages,
  handleError,
  redact,
} from "./errors.ts";
import { type MobileDeps, registerMobileRoutes, routePath } from "./mobile.ts";

export const DEFAULT_ALLOWED_ORIGINS: readonly string[] = [
  "https://abdalrahman-py.github.io",
  "http://localhost:5173",
  "http://127.0.0.1:5173",
];

const errorCodes = Object.keys(errorMessages) as [ErrorCode, ...ErrorCode[]];

export const ErrorCodeSchema = z.enum(errorCodes).openapi("ErrorCode");

export const ErrorResponseSchema = z
  .object({
    ok: z.literal(false),
    code: ErrorCodeSchema,
    message: z.string(),
    requestId: z.string().optional(),
  })
  .openapi("ErrorResponse");

export const HealthResponseSchema = z
  .object({
    ok: z.literal(true),
  })
  .openapi("HealthResponse");

const healthRoute = createRoute({
  method: "get",
  path: "/health",
  tags: ["system"],
  responses: {
    200: {
      description: "Health check",
      content: {
        "application/json": {
          schema: HealthResponseSchema,
        },
      },
    },
    500: {
      description: "Error response",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
  },
});

/** One line per request: never a body or a header, and secrets are redacted. */
export type RequestLogger = (line: string) => void;

export interface AppOptions {
  allowedOrigins?: readonly string[];
  verifyClaims?: VerifyClaims;
  lookupStoreId?: LookupStoreId;
  logError?: ErrorLogger;
  logRequest?: RequestLogger;
  mobile?: MobileDeps;
  admin?: AdminDeps;
  account?: AccountDeps;
}

const defaultRequestLogger: RequestLogger = (line) => console.log(line);

const defaultVerifyClaims: VerifyClaims = () =>
  Promise.resolve({
    data: null,
    error: new ApiError("authorization_required", 401),
  });

const defaultLookupStoreId: LookupStoreId = () => Promise.resolve(undefined);

export function createApp(options: AppOptions = {}): OpenAPIHono<AppEnv> {
  const allowedOrigins = new Set(
    options.allowedOrigins ?? DEFAULT_ALLOWED_ORIGINS,
  );
  const verifyClaims = options.verifyClaims ?? defaultVerifyClaims;
  const logRequest = options.logRequest ?? defaultRequestLogger;
  const lookupStoreId = options.lookupStoreId ?? defaultLookupStoreId;

  const root = new OpenAPIHono<AppEnv>({
    getPath: routePath,
    defaultHook: (result, c) => {
      if (!result.success) {
        return handleError(
          new ApiError("invalid_input", 400),
          c,
          options.logError,
        );
      }
    },
  });

  const app = root.basePath("/v1");
  app.onError((err, c) => handleError(err, c, options.logError));
  app.notFound((c) =>
    handleError(new ApiError("not_found", 404), c, options.logError)
  );

  app.openAPIRegistry.registerComponent("securitySchemes", "Bearer", {
    type: "http",
    scheme: "bearer",
    bearerFormat: "JWT",
  });
  app.openAPIRegistry.register("ErrorCode", ErrorCodeSchema);
  app.openAPIRegistry.register("ErrorResponse", ErrorResponseSchema);

  app.use(
    "*",
    cors({
      origin: (origin) => (allowedOrigins.has(origin) ? origin : null),
      allowMethods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
      allowHeaders: [
        "authorization",
        "apikey",
        "content-type",
        "x-request-id",
        "x-sadad-installation",
        "x-sadad-time",
        "x-sadad-nonce",
        "x-sadad-signature",
        "x-sadad-recovery",
      ],
      maxAge: 86400,
    }),
  );

  app.use("*", async (c, next) => {
    const started = performance.now();
    const requestId = c.req.header("x-request-id") || crypto.randomUUID();
    c.set("requestId", requestId);
    c.set("authDeps", { verifyClaims, lookupStoreId });
    c.header("Cache-Control", "no-store");
    c.header("X-Content-Type-Options", "nosniff");
    await next();
    logRequest(redact(
      c,
      JSON.stringify({
        requestId,
        method: c.req.method,
        path: routePath(c.req.raw),
        status: c.res.status,
        ms: Math.round(performance.now() - started),
      }),
    ));
  });

  app.openapi(healthRoute, (c) => c.json({ ok: true as const }, 200));
  registerMobileRoutes(app, options.mobile);
  registerAdminRoutes(app, options.admin);
  registerAccountRoutes(app, options.account);

  return app;
}

export function buildOpenApiDocument(app: OpenAPIHono<AppEnv> = createApp()) {
  return app.getOpenAPIDocument({
    openapi: "3.0.0",
    info: {
      title: "Sadad API",
      version: "1.0.0",
    },
  });
}
