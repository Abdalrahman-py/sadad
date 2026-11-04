import type { Actions, LedgerState, Model } from "../../src/model";
import { initialModel } from "../../src/model";
import { callerFromJwt } from "../../src/session";
import { auditEvent, detail, jwtFor, overview, pending, STORE_ID, storeRow } from "../data";

const noop = () => undefined;

/** Every action is a no-op; a name the views do not have, or a missing one, fails `tsc`. */
export const noActions: Actions = {
  signIn: noop,
  signOut: noop,
  refresh: noop,
  dismissNotice: noop,
  setStoreFilter: noop,
  searchStores: noop,
  quickApprove: noop,
  quickReject: noop,
  raiseLimitAndApprove: noop,
  quickExtend: noop,
  approveDevice: noop,
  revokeDevice: noop,
  saveSettings: noop,
  setStatus: noop,
  storeAction: noop,
  resetPassword: noop,
  saveSubscription: noop,
  dismissSecret: noop,
  setName: noop,
  setUsername: noop,
  setMode: noop,
  createStore: noop,
  restartCreate: noop,
  setRecovery: noop,
  loadManifestFile: noop,
  approveRecovery: noop,
  openLedger: noop,
  setLedgerKind: noop,
  searchLedger: noop,
  pageLedger: noop,
  downloadLedger: noop,
  searchAudit: noop,
  setAuditSource: noop,
  pageAudit: noop,
  setStoreAuditSource: noop,
  pageStoreAudit: noop,
  dialogReason: noop,
  dialogTyped: noop,
  submitDialog: noop,
  closeDialog: noop,
};

export const ledgerState = (reason = "مراجعة"): LedgerState => ({
  kind: "contacts",
  q: "",
  reason,
  page: {
    storeId: STORE_ID,
    kind: "contacts",
    epoch: "9d3a2c1b-0000-4000-8000-000000000002",
    latestSequence: 9,
    totals: { receivableCents: 12_500, payableCents: 0, netCents: 12_500 },
    rows: [
      {
        id: "c1",
        name: "سامي",
        phone: "+970****0000",
        archived: false,
        receivableCents: 12_500,
        payableCents: 0,
        note: "",
        createdAt: "2026-10-15T10:00:00Z",
      },
    ],
  },
  cursors: [undefined],
  next: null,
  loading: false,
  error: "",
});

/** A signed-in model with one store, one pending device, one audit row and the store open. */
export function loadedModel(role: "admin" | "support", text?: string): Model {
  const model = initialModel();
  const row = storeRow(text);
  model.booting = false;
  model.caller = callerFromJwt(jwtFor(role));
  model.overview = overview;
  model.stores = { rows: [row], q: "", filter: "all", loading: false, error: "", complete: true };
  model.pending = { rows: [pending(text)], loading: false, error: "", complete: true };
  model.audit = {
    rows: [auditEvent(text)],
    cursors: [undefined],
    next: null,
    loading: false,
    error: "",
    source: "control",
    q: "",
  };
  model.store = {
    id: STORE_ID,
    detail: detail(text),
    summary: row.summary,
    loading: false,
    error: "",
    tab: "overview",
    ledger: ledgerState(),
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
