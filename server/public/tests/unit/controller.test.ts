import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Api, AuditEvent } from "../../src/api";
import { Controller } from "../../src/controller";
import type { SessionPort } from "../../src/session";
import { auditEvent, STORE_ID } from "../data";
import { ledgerState, loadedModel } from "./support";

const session: SessionPort = {
  current: async () => null,
  signIn: async () => {
    throw new Error("not used");
  },
  signOut: async () => undefined,
};

/** A controller over the loaded admin model, drawing into a real (happy-dom) root. */
function setup(api: Partial<Api>) {
  const root = document.createElement("div");
  document.body.append(root);
  const controller = new Controller(root, session, api as Api);
  controller.model = loadedModel("admin");
  return { controller, root };
}

type AuditQuery = Parameters<Api["audit"]>[0];
interface Pending {
  query: AuditQuery;
  signal: AbortSignal;
  resolve(events: AuditEvent[], nextCursor?: string | null): void;
}

/** An audit call that stays open until the test settles it, and fails like fetch when aborted. */
function openAudit() {
  const calls: Pending[] = [];
  const audit = vi.fn(
    (query: AuditQuery, signal?: AbortSignal) =>
      new Promise((resolve, reject) => {
        const signalOrThrow = signal as AbortSignal;
        signalOrThrow.addEventListener("abort", () =>
          reject(new DOMException("aborted", "AbortError")),
        );
        calls.push({
          query,
          signal: signalOrThrow,
          resolve: (events, nextCursor = null) => resolve({ events, nextCursor }),
        });
      }),
  );
  return { audit: audit as unknown as Api["audit"], calls };
}

const settled = () => new Promise((resolve) => setTimeout(resolve));

afterEach(() => {
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("controller: one action mutates and renders", () => {
  it("filters the store list and repaints the chip", () => {
    const { controller, root } = setup({});
    controller.model.screen = "stores";

    controller.setStoreFilter("suspended");

    expect(controller.model.stores.filter).toBe("suspended");
    const pressed = [...root.querySelectorAll<HTMLElement>(".chip[aria-pressed=true]")];
    expect(pressed.map((chip) => chip.textContent?.trim())).toEqual([
      expect.stringContaining("مجمّدة"),
    ]);
  });
});

describe("controller: paging", () => {
  beforeEach(() => vi.useRealTimers());

  it("walks the cursor stack forward and back, and stops at both ends", async () => {
    const { audit, calls } = openAudit();
    const { controller } = setup({ audit });

    controller.searchAudit("x");
    calls[0]?.resolve([auditEvent()], "c2");
    await settled();
    expect(calls[0]?.query).toMatchObject({ q: "x", cursor: undefined });
    expect(controller.model.audit.cursors).toEqual([undefined]);
    expect(controller.model.audit.next).toBe("c2");

    controller.pageAudit("next");
    expect(calls[1]?.query.cursor).toBe("c2");
    expect(controller.model.audit.cursors).toEqual([undefined, "c2"]);
    calls[1]?.resolve([auditEvent()], null);
    await settled();

    controller.pageAudit("next"); // no next cursor: nothing to ask for
    expect(audit).toHaveBeenCalledTimes(2);

    controller.pageAudit("prev");
    expect(calls[2]?.query.cursor).toBeUndefined();
    expect(controller.model.audit.cursors).toEqual([undefined]);
    calls[2]?.resolve([auditEvent()], "c2");
    await settled();

    controller.pageAudit("prev"); // already on the first page
    expect(audit).toHaveBeenCalledTimes(3);
  });

  it("reads every page of a ledger download with the audited reason", async () => {
    const page = ledgerState().page;
    const ledger = vi.fn(async (_id: number, _kind: string, query: { cursor?: string }) => ({
      ...page,
      nextCursor: query.cursor ? null : "p2",
    }));
    const { controller } = setup({ ledger } as unknown as Partial<Api>);
    URL.createObjectURL = vi.fn(() => "blob:csv");
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);

    await controller.downloadLedger();

    expect(ledger.mock.calls.map(([, , query]) => query.cursor)).toEqual([undefined, "p2"]);
    expect(
      ledger.mock.calls.every(([, , query]) => (query as { limit?: number }).limit === 200),
    ).toBe(true);
    expect(click).toHaveBeenCalledOnce();
    expect(controller.model.notice?.error).toBe(false);
  });
});

describe("controller: abort and the race guard", () => {
  it("aborts the earlier request of a channel and ignores its answer", async () => {
    const { audit, calls } = openAudit();
    const { controller } = setup({ audit });

    controller.searchAudit("a");
    controller.searchAudit("ab");
    expect(calls[0]?.signal.aborted).toBe(true);
    expect(calls[1]?.signal.aborted).toBe(false);

    calls[1]?.resolve([auditEvent("second")]);
    await settled();

    expect(controller.model.audit.rows.map((row) => row.reason)).toEqual(["second"]);
    expect(controller.model.audit.loading).toBe(false);
    expect(controller.model.audit.error).toBe("");
    expect(controller.model.notice).toBeNull();
  });

  it("drops a store's audit page that arrives after another store was opened", async () => {
    const { audit, calls } = openAudit();
    const { controller } = setup({ audit });
    const opened = controller.model.store;
    if (!opened) throw new Error("the loaded model has a store open");

    controller.setStoreAuditSource("access");
    expect(calls[0]?.query).toMatchObject({ source: "access", storeId: STORE_ID });

    controller.model.store = { ...opened, id: STORE_ID + 1, audit: { ...opened.audit, rows: [] } };
    calls[0]?.resolve([auditEvent("late")]);
    await settled();

    expect(controller.model.store?.id).toBe(STORE_ID + 1);
    expect(controller.model.store?.audit.rows).toEqual([]);
  });
});

describe("controller: the notice timer", () => {
  it("clears its own notice only, not a newer one", () => {
    vi.useFakeTimers();
    const { controller } = setup({});

    controller.openLedger(" "); // no reason: an error notice
    const first = controller.model.notice;
    vi.advanceTimersByTime(4000);
    controller.openLedger(" ");
    const second = controller.model.notice;
    expect(second?.id).toBe((first?.id ?? 0) + 1);

    vi.advanceTimersByTime(2000); // the first timer fires here
    expect(controller.model.notice).toBe(second);
    vi.advanceTimersByTime(4000);
    expect(controller.model.notice).toBeNull();
  });
});
