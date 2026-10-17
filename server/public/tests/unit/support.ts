import type { Actions, Model } from "../../src/model";
import { initialModel } from "../../src/model";
import { callerFromJwt } from "../../src/session";
import { auditEvent, detail, jwtFor, overview, pending, STORE_ID, storeRow } from "../data";

/** Every action is a no-op. */
export const noActions = new Proxy({}, { get: () => () => undefined }) as Actions;

/** A signed-in model with one store open, one pending device and one audit row. */
export function loadedModel(role: "admin" | "support", text?: string): Model {
  const model = initialModel();
  model.booting = false;
  model.caller = callerFromJwt(jwtFor(role));
  model.overview = overview;
  model.stores.rows = [storeRow(text)];
  model.pending.rows = [pending(text)];
  model.audit.rows = [auditEvent(text)];
  model.store = {
    id: STORE_ID,
    detail: detail(text),
    loading: false,
    error: "",
    ledger: {
      kind: "contacts",
      q: "",
      reason: "مراجعة",
      page: {
        storeId: STORE_ID,
        kind: "contacts",
        epoch: "9d3a2c1b-0000-4000-8000-000000000002",
        latestSequence: 9,
        totals: { receivableCents: 12_500, payableCents: 0, netCents: 12_500 },
        rows: [
          {
            id: "c1",
            name: text ?? "سامي",
            phone: "+970599000000",
            archived: false,
            receivableCents: 12_500,
            payableCents: 0,
            note: text ?? "",
            createdAt: "2026-10-15T10:00:00Z",
          },
        ],
      },
      cursors: [undefined],
      next: null,
      loading: false,
      error: "",
    },
    audit: {
      rows: [auditEvent(text)],
      cursors: [undefined],
      next: null,
      loading: false,
      error: "",
      source: "control",
    },
    recovery: {
      oldId: "",
      newId: "",
      manifest: "",
      reason: "",
      error: "",
      busy: false,
      permit: null,
    },
  };
  return model;
}
