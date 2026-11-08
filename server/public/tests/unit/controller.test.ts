import { render } from "lit-html";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Api, AuditEvent } from "../../src/api";
import { Controller } from "../../src/controller";
import type { Dialog } from "../../src/model";
import type { SessionPort } from "../../src/session";
import { dialogView } from "../../src/views/dialog";
import { taskCount } from "../../src/views/tasks";
import { auditEvent, overview, STORE_ID, storeRow } from "../data";
import { ledgerState, loadedModel, noActions } from "./support";

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
  it("filters the store list and repaints the filter", () => {
    const { controller, root } = setup({});
    controller.model.screen = "stores";

    controller.setStoreFilter("suspended");

    expect(controller.model.stores.filter).toBe("suspended");
    const pressed = [...root.querySelectorAll<HTMLElement>(".seg button[aria-pressed=true]")];
    expect(pressed.map((button) => button.textContent?.trim())).toEqual([
      expect.stringContaining("مجمّدة"),
    ]);
  });
});

describe("controller: store search", () => {
  const expiring = { ...storeRow("بقالة النور"), subscriptionRemainingMs: 86_400_000 };
  const other = { ...storeRow("مخبز السلام"), id: 8, subscriptionRemainingMs: 90 * 86_400_000 };
  const stores = (async ({ q }: { q?: string }) => ({
    ok: true,
    stores: q ? [other] : [expiring, other],
    nextCursor: null,
  })) as unknown as Api["stores"];

  it("keeps every store in the queue while a search narrows the stores table", async () => {
    const { controller, root } = setup({ stores });
    controller.model.pending.rows = [];
    controller.model.stores.rows = [expiring, other];

    controller.searchStores("مخبز");
    await settled();

    expect(controller.model.stores.search?.rows).toEqual([other]);
    expect(controller.model.stores.rows).toEqual([expiring, other]);
    expect(taskCount(controller.model)).toBe(1);
    expect(root.textContent).toContain("بقالة النور");
  });

  it("drops the results when the search box is emptied", async () => {
    const { controller } = setup({ stores });

    controller.searchStores("مخبز");
    await settled();
    controller.searchStores("");
    await settled();

    expect(controller.model.stores.search).toBeNull();
  });
});

describe("controller: one-tap queue actions", () => {
  const device = { installationId: "a", storeId: STORE_ID };

  it("sends a double tap once and clears the row as soon as the server agrees", async () => {
    let finish = () => {};
    const decideInstallation = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const { controller } = setup({
      decideInstallation: decideInstallation as unknown as Api["decideInstallation"],
    });
    controller.model.pending.rows = [
      { ...controller.model.pending.rows[0], installationId: "a" },
    ] as typeof controller.model.pending.rows;

    void controller.quickApprove(device);
    void controller.quickApprove(device);
    expect(decideInstallation).toHaveBeenCalledTimes(1);
    expect(controller.model.working).toEqual(["device:a"]);

    finish();
    await settled();
    expect(controller.model.pending.rows).toEqual([]);
    expect(controller.model.working).toEqual([]);
  });

  it("moves an extended subscription out of the queue before the reload lands", async () => {
    const row = { ...storeRow(), subscriptionRemainingMs: 86_400_000 };
    const { controller } = setup({
      subscription: (async () => ({ ok: true })) as unknown as Api["subscription"],
    });
    controller.model.pending.rows = [];
    controller.model.stores.rows = [row];

    await controller.quickExtend(row, 30);

    expect(taskCount(controller.model)).toBe(0);
  });
});

describe("controller: sign-in", () => {
  it("asks for the lists without waiting for the seat claim", async () => {
    let claimed = () => {};
    const stores = vi.fn(async () => ({ ok: true, stores: [], nextCursor: null }));
    const root = document.createElement("div");
    const signedIn = { ...session, current: async () => loadedModel("admin").caller };
    const controller = new Controller(
      root,
      signedIn as SessionPort,
      {
        claimSession: () =>
          new Promise<void>((resolve) => {
            claimed = resolve;
          }),
        stores,
        overview: async () => overview,
        pendingInstallations: async () => ({ ok: true, installations: [], nextCursor: null }),
        audit: async () => ({ ok: true, events: [], nextCursor: null }),
      } as unknown as Api,
    );

    void controller.start();
    await settled();

    expect(stores).toHaveBeenCalled();
    expect(controller.model.caller).toBeNull();
    claimed();
    await settled();
    expect(controller.model.caller).not.toBeNull();
  });
});

describe("danger dialog", () => {
  const dialog = (needsReason: boolean): Dialog => ({
    kind: "danger",
    title: "تجميد الحساب؟",
    description: "",
    confirm: "تجميد",
    username: "corner-shop",
    typed: "corner-shop",
    needsReason,
    reason: "",
    error: "",
    busy: false,
    run: async () => undefined,
  });
  const confirmDisabled = (value: Dialog) => {
    const root = document.createElement("div");
    render(dialogView(value, noActions), root);
    return root.querySelector<HTMLButtonElement>("button:not([type=button])")?.disabled;
  };

  it("confirms on the typed username alone when the action records no reason", () => {
    expect(confirmDisabled(dialog(false))).toBe(false);
  });

  it("still waits for a reason when the action records one", () => {
    expect(confirmDisabled(dialog(true))).toBe(true);
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
