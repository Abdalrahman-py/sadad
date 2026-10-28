import * as Sentry from "@sentry/deno";
import { initSentry, scrubEvent } from "./scrub.ts";
import { assert, assertEqual } from "./test_support.ts";

const SECRET = "store Ali 120.50";

function dirtyEvent(): Sentry.ErrorEvent {
  return {
    type: undefined,
    message: SECRET,
    request: {
      url: "https://test.invalid/v1/stores/42/debts",
      headers: { Authorization: "Bearer jwt-token" },
      data: { note: SECRET },
    },
    user: { id: "11111111-1111-4111-8111-111111111111" },
    breadcrumbs: [{ message: SECRET, category: "console" }],
    extra: { requestId: "req-1", body: SECRET },
    contexts: { os: { name: "linux" }, runtime: { name: "deno" } },
    exception: {
      values: [{
        type: "TypeError",
        value: SECRET,
        stacktrace: { frames: [{ function: "save" }] },
      }],
    },
  };
}

Deno.test("no DSN skips Sentry init entirely", () => {
  initSentry(undefined);
  assert(
    Sentry.getClient() === undefined,
    "Sentry must stay uninitialised without a DSN",
  );
});

Deno.test("a DSN initializes Sentry with the scrubber as beforeSend", () => {
  initSentry("https://public@o0.ingest.sentry.io/1");
  const options = Sentry.getClient()?.getOptions();
  assert(options, "Sentry must initialise with a DSN");
  assertEqual(options?.beforeSend, scrubEvent);
});

Deno.test("the scrubber removes request, user, breadcrumbs, extra and message text", () => {
  const scrubbed = scrubEvent(dirtyEvent());
  assert(scrubbed, "the scrubbed event must survive");
  assertEqual(scrubbed.request, undefined);
  assertEqual(scrubbed.user, undefined);
  assertEqual(scrubbed.breadcrumbs, undefined);
  assertEqual(scrubbed.extra, undefined);
  assertEqual(scrubbed.message, undefined);
  assertEqual(JSON.stringify(scrubbed).includes(SECRET), false);
});

Deno.test("the scrubber keeps the error class name, the runtime context and stack frames", () => {
  const scrubbed = scrubEvent(dirtyEvent());
  assert(scrubbed, "the scrubbed event must survive");
  const exception = scrubbed.exception?.values?.[0];
  assertEqual(exception?.value, "TypeError");
  assertEqual(exception?.type, "TypeError");
  assertEqual(scrubbed.contexts, { runtime: { name: "deno" } });
  assertEqual(exception?.stacktrace?.frames, [{ function: "save" }]);
});

Deno.test("a null event stays null", () => {
  assertEqual(scrubEvent(null), null);
});

Deno.test("the route tag keeps the shape with ids replaced", () => {
  const scrubbed = scrubEvent({
    type: undefined,
    tags: {
      route:
        "/v1/stores/42/contacts/9f8c1b2e-1a2b-4c3d-8e4f-aabbccddeeff/payments/7",
    },
  });
  assertEqual(
    scrubbed?.tags?.route,
    "/v1/stores/:id/contacts/:id/payments/:id",
  );
});
