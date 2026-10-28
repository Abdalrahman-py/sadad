// Everything the views read. Views are pure functions of (Model, Actions); controller.ts owns the
// Model. Screens follow the design's Admin boards: tasks (landing) · stores · store page · create ·
// audit.
import type {
  AuditEvent,
  AuditSource,
  LedgerKind,
  LedgerPage,
  Overview,
  PendingInstallation,
  Store,
  StoreAction,
  StoreDetail,
  StoreRow,
} from "./api";
import type { Caller } from "./session";

export type Screen = "tasks" | "stores" | "store" | "create" | "audit";

/** The store page's tabs, in the order the design shows them. */
export const STORE_TABS = [
  "overview",
  "devices",
  "subscription",
  "settings",
  "ledger",
  "recovery",
  "audit",
] as const;

export type StoreTab = (typeof STORE_TABS)[number];

export const STORE_TAB_LABELS: Record<StoreTab, string> = {
  overview: "نظرة عامة",
  devices: "الأجهزة",
  subscription: "الاشتراك",
  settings: "الإعدادات",
  ledger: "الدفتر",
  recovery: "الاسترداد",
  audit: "سجل النشاط",
};

/** Chips above the store table. `over` is a timed subscription that already ran out. */
export type StoreFilter = "all" | "active" | "soon" | "over" | "suspended" | "archived";

export const STORE_FILTERS: [StoreFilter, string][] = [
  ["all", "الكل"],
  ["active", "نشطة"],
  ["soon", "تنتهي قريبًا"],
  ["over", "منتهية"],
  ["suspended", "مجمّدة"],
  ["archived", "مؤرشفة"],
];

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

/**
 * The store list is loaded whole — every page, up to a cap — because the tasks queue and the
 * filter chips need the full set, and the api can only filter by name. Cursors are not kept: the
 * view paginates nothing.
 */
export interface StoreList {
  rows: StoreRow[];
  q: string;
  filter: StoreFilter;
  loading: boolean;
  error: string;
  /** False when the cap was reached with more pages waiting on the server. */
  complete: boolean;
}

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
  /** The list row's counters, so the overview tab needs no extra call when it has them. */
  summary: StoreRow["summary"] | null;
  loading: boolean;
  error: string;
  tab: StoreTab;
  ledger: LedgerState | null;
  audit: Paged<AuditEvent> & { source: AuditSource };
  recovery: RecoveryState;
}

export type Dialog =
  /** A short reason is required and recorded in the audit trail. */
  | {
      kind: "reason";
      title: string;
      description: string;
      confirm: string;
      danger: boolean;
      value: string;
      error: string;
      busy: boolean;
      run: (reason: string) => Promise<void>;
    }
  /**
   * A dangerous account action: the reason is required and the admin types the username, as the
   * design asks. Two fields, one submit.
   */
  | {
      kind: "danger";
      title: string;
      description: string;
      confirm: string;
      username: string;
      typed: string;
      /** False for actions whose api call records no reason (freezing, activating). */
      needsReason: boolean;
      reason: string;
      error: string;
      busy: boolean;
      run: (reason: string) => Promise<void>;
    }
  | {
      kind: "confirm";
      title: string;
      description: string;
      confirm: string;
      error: string;
      busy: boolean;
      run: () => Promise<void>;
    };

export interface CreateState {
  name: string;
  username: string;
  mode: "permanent" | "timed" | "paused";
  busy: boolean;
  error: string;
  /** Set once the store exists: the temporary password is shown once and never fetched again. */
  done: { storeId: number; username: string; password: string } | null;
}

export interface Model {
  booting: boolean;
  caller: Caller | null;
  signIn: { error: string; busy: boolean };
  notice: { text: string; error: boolean; id: number } | null;
  screen: Screen;
  overview: Overview | null;
  stores: StoreList;
  pending: { rows: PendingInstallation[]; loading: boolean; error: string; complete: boolean };
  audit: Paged<AuditEvent> & { source: AuditSource; q: string };
  store: StoreState | null;
  create: CreateState;
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
    screen: "tasks",
    overview: null,
    stores: { rows: [], q: "", filter: "all", loading: false, error: "", complete: true },
    pending: { rows: [], loading: false, error: "", complete: true },
    audit: { ...emptyPage(), source: "control", q: "" },
    store: null,
    create: { name: "", username: "", mode: "timed", busy: false, error: "", done: null },
    dialog: null,
    secret: null,
  };
}

export const canWrite = (model: Model): boolean => model.caller?.role === "admin";

export type PageStep = "next" | "prev";

/** What the views can ask for. controller.ts implements it; tests pass stubs. */
export interface Actions {
  signIn(email: string, password: string): void;
  signOut(): void;
  refresh(): void;
  dismissNotice(): void;

  setStoreFilter(filter: StoreFilter): void;
  searchStores(q: string): void;

  /** One tap from the queue: approve or reject with the dashboard's standard reason. */
  quickApprove(device: PendingInstallation): void;
  quickReject(device: PendingInstallation): void;
  /** Raises the store's device limit by one and then approves — one decision, two calls. */
  raiseLimitAndApprove(device: PendingInstallation, store: Store): void;
  /** Adds days and reactivates a timed subscription; used by the queue and by the store page. */
  quickExtend(store: Store, days: number): void;

  approveDevice(installationId: string, storeId: number): void;
  revokeDevice(installationId: string, storeId: number): void;

  saveSettings(form: FormData): void;
  setStatus(status: "active" | "suspended"): void;
  storeAction(action: StoreAction): void;
  resetPassword(): void;
  saveSubscription(mode: "permanent" | "timed" | "paused", days?: number): void;
  dismissSecret(): void;

  setName(value: string): void;
  setUsername(value: string): void;
  setMode(mode: CreateState["mode"]): void;
  createStore(form: FormData): void;
  restartCreate(): void;

  setRecovery(patch: Partial<Pick<RecoveryState, "oldId" | "newId" | "manifest" | "reason">>): void;
  loadManifestFile(file: File): void;
  approveRecovery(): void;

  openLedger(reason: string): void;
  setLedgerKind(kind: LedgerKind): void;
  searchLedger(q: string): void;
  pageLedger(step: PageStep): void;
  downloadLedger(): void;

  searchAudit(q: string): void;
  setAuditSource(source: AuditSource): void;
  pageAudit(step: PageStep): void;
  setStoreAuditSource(source: AuditSource): void;
  pageStoreAudit(step: PageStep): void;

  dialogReason(value: string): void;
  dialogTyped(value: string): void;
  submitDialog(): void;
  closeDialog(): void;
}
