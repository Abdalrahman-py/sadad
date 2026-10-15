import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi";
import { cors } from "hono/cors";
import { type AppEnv, type LookupStoreId, type VerifyClaims } from "./auth.ts";
import {
  ApiError,
  type ErrorCode,
  type ErrorLogger,
  errorMessages,
  handleError,
} from "./errors.ts";

export const DEFAULT_ALLOWED_ORIGINS: readonly string[] = [
  "https://abdalrahman-py.github.io",
  "https://ibrahem57.github.io",
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

export interface AppOptions {
  allowedOrigins?: readonly string[];
  verifyClaims?: VerifyClaims;
  lookupStoreId?: LookupStoreId;
  logError?: ErrorLogger;
}

const getPath = (req: Request): string =>
  new URL(req.url).pathname.replace(/^(?:\/functions\/v1)?\/api(?=\/|$)/, "") ||
  "/";

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
  const lookupStoreId = options.lookupStoreId ?? defaultLookupStoreId;

  const root = new OpenAPIHono<AppEnv>({
    getPath,
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
    const requestId = c.req.header("x-request-id") || crypto.randomUUID();
    c.set("requestId", requestId);
    c.set("authDeps", { verifyClaims, lookupStoreId });
    c.header("Cache-Control", "no-store");
    c.header("X-Content-Type-Options", "nosniff");
    await next();
  });

  app.openapi(healthRoute, (c) => c.json({ ok: true as const }, 200));

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
