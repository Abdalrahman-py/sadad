import * as Sentry from "@sentry/browser";
import { describe, expect, it } from "vitest";
import { initSentry, scrubEvent } from "../../src/scrub";

const SECRET = "store Ali 120.50";

function dirtyEvent(): Sentry.ErrorEvent {
  return {
    type: undefined,
    message: SECRET,
    request: {
      url: "https://abdalrahman-py.github.io/sadad/",
      data: { note: SECRET },
    },
    user: { id: "11111111-1111-4111-8111-111111111111" },
    breadcrumbs: [{ message: SECRET, category: "ui.click" }],
    extra: { body: SECRET },
    contexts: { browser: { name: "Chrome" } },
    exception: {
      values: [
        {
          type: "TypeError",
          value: SECRET,
          stacktrace: { frames: [{ function: "save" }] },
        },
      ],
    },
  };
}

describe("the dashboard Sentry scrubber", () => {
  it("removes request, user, breadcrumbs, extra and message text", () => {
    const scrubbed = scrubEvent(dirtyEvent());
    expect(scrubbed?.request).toBeUndefined();
    expect(scrubbed?.user).toBeUndefined();
    expect(scrubbed?.breadcrumbs).toBeUndefined();
    expect(scrubbed?.extra).toBeUndefined();
    expect(scrubbed?.message).toBeUndefined();
    expect(JSON.stringify(scrubbed)).not.toContain(SECRET);
  });

  it("keeps the error class name and stack frames", () => {
    const scrubbed = scrubEvent(dirtyEvent());
    expect(scrubbed?.exception?.values?.[0]?.value).toBe("TypeError");
    expect(scrubbed?.exception?.values?.[0]?.type).toBe("TypeError");
    expect(scrubbed?.exception?.values?.[0]?.stacktrace?.frames).toEqual([{ function: "save" }]);
  });

  it("keeps a null event null", () => {
    expect(scrubEvent(null)).toBeNull();
  });
});

describe("dashboard Sentry init", () => {
  it("skips init without a DSN", () => {
    initSentry(undefined);
    expect(Sentry.getClient()).toBeUndefined();
  });

  it("initializes with the scrubber as beforeSend when a DSN exists", () => {
    initSentry("https://public@o0.ingest.sentry.io/1");
    expect(Sentry.getClient()?.getOptions().beforeSend).toBe(scrubEvent);
  });
});
