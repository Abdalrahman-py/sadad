// Everything the views read. Views are pure functions of (Model, Actions); main.ts owns the Model.
import type {
  AuditEvent,
  AuditSource,
  LedgerKind,
  LedgerPage,
  Overview,
  PendingInstallation,
  StoreAction,
  StoreDetail,
  StoreRow,
} from "./api";
import type { Caller } from "./session";

export interface Paged<T> {
  rows: T[];
  /** Cursors of the pages before this one; the last entry is this page's own cursor. */
  cursors: (string | undefined)[];
  next: string | null;
  loading: boolean;
  error: string;
}

export const emptyPage = <T>(): Paged<T> => ({
  rows: [],
  cursors: [undefined],
  next: null,
  loading: false,
  error: "",
});

export interface LedgerState {
  kind: LedgerKind;
  q: string;
  reason: string;
  page: Omit<LedgerPage, "nextCursor"> | null;
  cursors: (string | undefined)[];
  next: string | null;
  loading: boolean;
  error: string;
}

export interface RecoveryState {
  oldId: string;
  newId: string;
  manifest: string;
  reason: string;
  error: string;
  busy: boolean;
  permit: { permitId: string; expiresAt: string } | null;
}

export interface StoreState {
  id: number;
  detail: StoreDetail | null;
  loading: boolean;
  error: string;
  ledger: LedgerState | null;
  audit: Paged<AuditEvent> & { source: AuditSource };
  recovery: RecoveryState;
}

export type Dialog =
  | {
      kind: "reason";
      title: string;
      description: string;
      confirm: string;
      danger: boolean;
      run: (reason: string) => Promise<void>;
      error: string;
      busy: boolean;
    }
  | {
      kind: "confirm";
      title: string;
      description: string;
      confirm: string;
      run: () => Promise<void>;
      error: string;
      busy: boolean;
    }
  | { kind: "create"; error: string; busy: boolean }
  | { kind: "delete"; storeId: number; username: string; error: string; busy: boolean };

export type Tab = "stores" | "installations" | "audit";

export interface Model {
  booting: boolean;
  caller: Caller | null;
  signIn: { error: string; busy: boolean };
  notice: { text: string; error: boolean; id: number } | null;
  tab: Tab;
  overview: Overview | null;
  stores: Paged<StoreRow> & { q: string; includeArchived: boolean };
  pending: Paged<PendingInstallation> & { q: string };
  audit: Paged<AuditEvent> & { q: string; source: AuditSource };
  store: StoreState | null;
  dialog: Dialog | null;
  /** A temporary password, shown once. */
  secret: { storeId: number; username: string; password: string } | null;
}

export function initialModel(): Model {
  return {
    booting: true,
    caller: null,
    signIn: { error: "", busy: false },
    notice: null,
    tab: "stores",
    overview: null,
    stores: { ...emptyPage(), q: "", includeArchived: false },
    pending: { ...emptyPage(), q: "" },
    audit: { ...emptyPage(), q: "", source: "control" },
    store: null,
    dialog: null,
    secret: null,
  };
}

export const canWrite = (model: Model): boolean => model.caller?.role === "admin";

export type PageStep = "next" | "prev";

/** What the views can ask for. main.ts implements it; tests pass stubs. */
export interface Actions {
  signIn(email: string, password: string): void;
  signOut(): void;
  refresh(): void;
  setTab(tab: Tab): void;
  dismissNotice(): void;

  searchStores(q: string): void;
  setIncludeArchived(value: boolean): void;
  pageStores(step: PageStep): void;
  openCreateStore(): void;
  createStore(form: FormData): void;

  searchPending(q: string): void;
  pagePending(step: PageStep): void;

  searchAudit(q: string): void;
  setAuditSource(source: AuditSource): void;
  pageAudit(step: PageStep): void;

  openStore(storeId: number): void;
  closeStore(): void;
  saveSettings(form: FormData): void;
  setStatus(status: "active" | "suspended"): void;
  saveSubscription(form: FormData): void;
  extendSubscription(form: FormData): void;
  resetPassword(): void;
  storeAction(action: StoreAction): void;
  openDelete(): void;
  deleteStore(confirm: string): void;
  decideInstallation(installationId: string, decision: "approve" | "revoke", storeId: number): void;
  dismissSecret(): void;

  setRecovery(patch: Partial<Pick<RecoveryState, "oldId" | "newId" | "manifest" | "reason">>): void;
  loadManifestFile(file: File): void;
  approveRecovery(): void;

  openLedger(reason: string): void;
  setLedgerKind(kind: LedgerKind): void;
  searchLedger(q: string): void;
  pageLedger(step: PageStep): void;
  downloadLedger(): void;

  setStoreAuditSource(source: AuditSource): void;
  pageStoreAudit(step: PageStep): void;

  submitDialog(value: string): void;
  closeDialog(): void;
}
