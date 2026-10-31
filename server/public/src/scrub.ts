import * as Sentry from "@sentry/browser";

/** beforeSend for the dashboard: no request, user, breadcrumbs, extras or message text leaves; the class name and the frames stay. */
export function scrubEvent(event: Sentry.ErrorEvent | null): Sentry.ErrorEvent | null {
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
  return event;
}

/** Starts crash reporting only when a DSN was handed to the build; without one this is a no-op. */
export function initSentry(dsn: string | undefined): void {
  if (!dsn) return;
  Sentry.init({ dsn, beforeSend: scrubEvent });
}
