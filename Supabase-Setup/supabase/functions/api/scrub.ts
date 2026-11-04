import * as Sentry from "@sentry/deno";
import { ApiError, fromDatabaseError } from "./errors.ts";

// Any numeric or UUID path segment is treated as an id.
const ID_SEGMENT =
  /\/(?:\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?=\/|$)/g;

/** beforeSend for the api function: no request, user, breadcrumbs, extras or message text leaves; the class name, the runtime and the frames stay. */
export function scrubEvent(
  event: Sentry.ErrorEvent | null,
): Sentry.ErrorEvent | null {
  if (!event) return null;
  delete event.breadcrumbs;
  delete event.request;
  delete event.user;
  delete event.extra;
  delete event.message;
  event.contexts = { runtime: event.contexts?.runtime };
  for (const exception of event.exception?.values ?? []) {
    exception.value = exception.type;
  }
  const route = event.tags?.route;
  if (typeof route === "string") {
    event.tags = { route: route.replace(ID_SEGMENT, "/:id") };
  }
  return event;
}

/** Starts crash reporting only when a DSN exists in the project environment; absent, this is a no-op. */
export function initSentry(dsn: string | undefined): void {
  if (!dsn) return;
  Sentry.init({ dsn, beforeSend: scrubEvent });
}

/** Reports the error to Sentry only when it is a 5xx-class unexpected failure, never a contract 4xx refusal. */
export function captureUnexpected(err: unknown, route: string): void {
  const mapped = err instanceof ApiError ? err : fromDatabaseError(err);
  if (mapped && mapped.status < 500) return;
  Sentry.captureException(err, { tags: { route } });
}
