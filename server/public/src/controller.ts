// Owns the Model, talks to Auth and the api, and re-renders after every change. The screens follow
// the hash (#/tasks, #/stores, #/stores/12/devices), so the back button and a copied link work.
// Lists that the views filter (stores, pending devices) are loaded whole up to STORE_PAGE_CAP; the
// audit and ledger lists stay server-paged.
import { render } from "lit-html";
import type {
  Api,
  AuditSource,
  LedgerKind,
  PendingInstallation,
  Store,
  StoreAction,
  StoreRow,
} from "./api";
import { ApiFailure, failure, messageOf } from "./errors";
import { fmt, integer } from "./format";
import {
  type Actions,
  type Dialog,
  emptyPage,
  initialModel,
  type LedgerState,
  type Model,
  type Paged,
  type PageStep,
  type RecoveryState,
  STORE_TABS,
  type StoreFilter,
  type StoreState,
  type StoreTab,
} from "./model";
import { inspectManifest, MAX_MANIFEST_BYTES } from "./recovery-manifest";
import { checkCaller, type SessionPort } from "./session";
import { app as view } from "./views/app";
import { readPermissions } from "./views/common";
import { LEDGER_COLUMNS, LEDGER_KINDS } from "./views/ledger";
import { recoveryChoices } from "./views/recovery";

/** The store and device lists are read page by page; this is where the loop stops. */
const STORE_PAGE_CAP = 10;

/** One tap from the queue has no form, so it records a fixed reason the audit can read. */
const QUICK_REASON = {
  approve: "اعتماد جهاز من شاشة المهام",
  reject: "رفض طلب جهاز من شاشة المهام",
  approveDialog: "اعتماد جهاز من لوحة الإدارة",
  revoke: "إلغاء اعتماد جهاز من لوحة الإدارة",
  suspend: "تجميد الحساب من لوحة الإدارة",
  activate: "تفعيل الحساب من لوحة الإدارة",
} as const;

const ACTION_TEXT: Record<StoreAction, { title: string; description: string }> = {
  archive: {
    title: "أرشفة الحساب",
    description: "يُوقف الحساب وتُلغى صلاحية أجهزته وتُنهى جلساته. يبقى الدفتر والسجل محفوظين.",
  },
  unarchive: {
    title: "إعادة الحساب من الأرشيف",
    description: "يعود الحساب مجمّدًا مع دفتره. فعّله واعتمد جهازًا جديدًا بعد ذلك.",
  },
  "end-sessions": {
    title: "إنهاء جلسات الدخول",
    description: "تُنهى كل جلسات المتجر الآن. اعتماد الأجهزة يبقى كما هو.",
  },
  "revoke-all": {
    title: "إلغاء اعتماد جميع الأجهزة",
    description: "تتوقف صلاحية كل أجهزة المتجر للمزامنة. يحتاج كل جهاز تسجيلًا جديدًا واعتمادك.",
  },
};

const NOTICE_MS = 6000;

/** How often an open board renews its seat; the api frees a silent one after ten minutes. */
const HEARTBEAT_MS = 60_000;

const emptyRecovery = (): RecoveryState => ({
  oldId: "",
  newId: "",
  manifest: "",
  reason: "",
  error: "",
  busy: false,
  permit: null,
});

const isAbort = (error: unknown) => error instanceof DOMException && error.name === "AbortError";

const moveCursors = (page: Paged<unknown>, step: PageStep): (string | undefined)[] | null => {
  if (step === "next") return page.next ? [...page.cursors, page.next] : null;
  return page.cursors.length > 1 ? page.cursors.slice(0, -1) : null;
};

/** Cells that start with =, +, - or @ would run as formulas in a spreadsheet. */
const csvCell = (value: string) =>
  `"${(/^[=+\-@\t\r]/.test(value) ? `'${value}` : value).replaceAll('"', '""')}"`;

type Route =
  | { screen: "tasks" | "stores" | "audit" | "create" }
  | { screen: "store"; id: number; tab: StoreTab };

export function parseHash(hash: string): Route {
  const [head, id, tab] = hash.replace(/^#\/?/, "").split("/");
  if (head === "stores") {
    const storeId = Number(id);
    if (id && Number.isSafeInteger(storeId) && storeId > 0) {
      return {
        screen: "store",
        id: storeId,
        tab: STORE_TABS.includes(tab as StoreTab) ? (tab as StoreTab) : "overview",
      };
    }
    return { screen: "stores" };
  }
  if (head === "audit") return { screen: "audit" };
  if (head === "create") return { screen: "create" };
  return { screen: "tasks" };
}

export class Controller implements Actions {
  model: Model = initialModel();
  private channels = new Map<string, AbortController>();
  private heartbeat: number | null = null;
  /** Set while a dialog is being opened, so focus lands in its first field. */
  private focusDialog = false;

  constructor(
    private readonly root: HTMLElement,
    private readonly session: SessionPort,
    private readonly api: Api,
  ) {}

  // ---- rendering and plumbing ----

  private render() {
    render(view(this.model, this), this.root);
    const dialog = this.root.querySelector("dialog");
    if (dialog && !dialog.open) dialog.showModal();
    if (dialog && this.focusDialog) {
      this.focusDialog = false;
      dialog.querySelector<HTMLElement>("textarea, input")?.focus();
    }
  }

  /** The only place the model changes: every write renders, so no handler has to remember to. */
  private set(patch: Partial<Model>) {
    Object.assign(this.model, patch);
    this.render();
  }

  /** Back to the signed-out screen, dropping everything the signed-in board held. */
  private reset(signIn: Model["signIn"] = { error: "", busy: false }) {
    this.set({ ...initialModel(), booting: false, signIn });
  }

  private channel(name: string): AbortSignal {
    this.channels.get(name)?.abort();
    const controller = new AbortController();
    this.channels.set(name, controller);
    return controller.signal;
  }

  private abortAll() {
    for (const controller of this.channels.values()) controller.abort();
    this.channels.clear();
  }

  private notify(text: string, error = false) {
    const id = (this.model.notice?.id ?? 0) + 1;
    this.set({ notice: { text, error, id } });
    setTimeout(() => {
      if (this.model.notice?.id === id) this.set({ notice: null });
    }, NOTICE_MS);
  }

  /** Runs a write; a refusal shows as a notice. */
  private async attempt(run: () => Promise<unknown>, done?: string) {
    try {
      await run();
      if (done) this.notify(done);
    } catch (error) {
      if (!isAbort(error)) this.notify(messageOf(error), true);
    }
  }

  // ---- session ----

  async start() {
    window.addEventListener("hashchange", () => this.applyRoute());
    const caller = await this.session.current().catch(() => null);
    if (!caller) return this.set({ booting: false });
    try {
      checkCaller(caller);
      // A reload keeps the tab's session; the account may still have been opened elsewhere.
      await this.api.claimSession();
      this.set({ booting: false, caller });
      this.startHeartbeat();
      this.applyRoute();
      this.loadAll();
    } catch (error) {
      await this.session.signOut();
      this.set({ booting: false, signIn: { error: messageOf(error), busy: false } });
    }
  }

  signIn = async (email: string, password: string) => {
    this.set({ signIn: { error: "", busy: true } });
    try {
      const caller = checkCaller(await this.session.signIn(email, password));
      // One account, one session: the api refuses a second one with session_in_use.
      await this.api.claimSession();
      this.set({ caller, signIn: { error: "", busy: false } });
      this.startHeartbeat();
      this.applyRoute();
      this.loadAll();
    } catch (error) {
      await this.session.signOut();
      this.set({ signIn: { error: messageOf(error), busy: false } });
    }
  };

  signOut = async () => {
    this.abortAll();
    this.stopHeartbeat();
    // Release first: the lease needs the JWT this tab still holds.
    await this.api.releaseSession().catch(() => undefined);
    await this.session.signOut();
    this.reset();
  };

  /** The api refused the token (expired or ended session). */
  unauthorized = () => {
    if (!this.model.caller) return;
    this.abortAll();
    this.stopHeartbeat();
    void this.session.signOut();
    this.reset({ error: failure("authorization_required").message, busy: false });
  };

  /** Renews the seat while the board is open; a board left alone frees it after ten minutes. */
  private startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeat = window.setInterval(() => {
      void this.api.claimSession().catch((error: unknown) => {
        if (error instanceof ApiFailure && error.code === "session_in_use") this.sessionTaken();
      });
    }, HEARTBEAT_MS);
  }

  private stopHeartbeat() {
    if (this.heartbeat === null) return;
    window.clearInterval(this.heartbeat);
    this.heartbeat = null;
  }

  /** The account was signed in somewhere else, so this board signs out and says why. */
  private sessionTaken = () => {
    if (!this.model.caller) return;
    this.abortAll();
    this.stopHeartbeat();
    void this.session.signOut();
    this.reset({ error: failure("session_in_use").message, busy: false });
  };

  // ---- routing ----

  private applyRoute() {
    if (!this.model.caller) return;
    const route = parseHash(window.location.hash);
    if (route.screen === "store") {
      this.set({ screen: "store" });
      this.openStore(route.id, route.tab);
      return;
    }
    this.leaveStore();
    this.set({
      screen: route.screen,
      store: null,
      secret: null,
      ...(route.screen === "create" ? { create: initialModel().create } : {}),
    });
    if (route.screen === "audit") this.loadAudit(this.model.audit.cursors);
  }

  // ---- lists ----

  private loadAll() {
    this.loadOverview();
    this.loadStores();
    this.loadPending();
    this.loadAudit(this.model.audit.cursors);
  }

  refresh = () => this.loadAll();

  dismissNotice = () => this.set({ notice: null });

  private async loadOverview() {
    try {
      this.set({ overview: await this.api.overview(this.channel("overview")) });
    } catch (error) {
      if (!isAbort(error)) this.notify(messageOf(error), true);
    }
  }

  /** Every page of the store list, so the chips and the queue can filter honestly. */
  private async loadStores() {
    const signal = this.channel("stores");
    this.set({ stores: { ...this.model.stores, loading: true, error: "" } });
    const q = this.model.stores.q;
    const rows: StoreRow[] = [];
    let cursor: string | undefined;
    let complete = false;
    try {
      for (let page = 0; page < STORE_PAGE_CAP; page++) {
        const result = await this.api.stores({ q, cursor, includeArchived: true }, signal);
        rows.push(...result.stores);
        cursor = result.nextCursor ?? undefined;
        if (!cursor) {
          complete = true;
          break;
        }
      }
      this.set({ stores: { ...this.model.stores, rows, loading: false, complete } });
      this.fillStoreSummary(rows);
    } catch (error) {
      if (isAbort(error)) return;
      this.set({
        stores: {
          ...this.model.stores,
          rows,
          loading: false,
          complete: false,
          error: messageOf(error),
        },
      });
    }
  }

  /** The open store's counters may have arrived with this list. */
  private fillStoreSummary(rows: StoreRow[]) {
    const state = this.model.store;
    if (!state) return;
    const row = rows.find((store) => store.id === state.id);
    if (row && state.summary !== row.summary) {
      this.set({ store: { ...state, summary: row.summary } });
    }
  }

  private async loadPending() {
    const signal = this.channel("pending");
    this.set({ pending: { ...this.model.pending, loading: true, error: "" } });
    const rows: PendingInstallation[] = [];
    let cursor: string | undefined;
    let complete = false;
    try {
      for (let page = 0; page < STORE_PAGE_CAP; page++) {
        const result = await this.api.pendingInstallations({ cursor }, signal);
        rows.push(...result.installations);
        cursor = result.nextCursor ?? undefined;
        if (!cursor) {
          complete = true;
          break;
        }
      }
      this.set({ pending: { rows, loading: false, complete, error: "" } });
    } catch (error) {
      if (isAbort(error)) return;
      this.set({
        pending: { rows, loading: false, complete: false, error: messageOf(error) },
      });
    }
  }

  private async loadAudit(cursors: (string | undefined)[]) {
    const signal = this.channel("audit");
    const { q, source } = this.model.audit;
    this.set({ audit: { ...this.model.audit, cursors, loading: true, error: "" } });
    try {
      const result = await this.api.audit({ source, q, cursor: cursors.at(-1) }, signal);
      this.set({
        audit: {
          ...this.model.audit,
          rows: result.events,
          next: result.nextCursor,
          loading: false,
        },
      });
    } catch (error) {
      if (isAbort(error)) return;
      this.set({
        audit: {
          ...this.model.audit,
          rows: [],
          next: null,
          loading: false,
          error: messageOf(error),
        },
      });
    }
  }

  searchStores = (q: string) => {
    this.set({ stores: { ...this.model.stores, q } });
    void this.loadStores();
  };

  setStoreFilter = (filter: StoreFilter) => {
    this.set({ stores: { ...this.model.stores, filter } });
  };

  searchAudit = (q: string) => {
    this.set({ audit: { ...this.model.audit, q } });
    void this.loadAudit([undefined]);
  };

  setAuditSource = (source: AuditSource) => {
    this.set({ audit: { ...this.model.audit, source } });
    void this.loadAudit([undefined]);
  };

  pageAudit = (step: PageStep) => {
    const cursors = moveCursors(this.model.audit, step);
    if (cursors) void this.loadAudit(cursors);
  };

  // ---- the queue's one-tap actions ----

  private afterDeviceChange(storeId: number) {
    this.loadOverview();
    void this.loadStores();
    void this.loadPending();
    if (this.model.store?.id === storeId) this.reloadStore();
  }

  quickApprove = (device: { installationId: string; storeId: number }) =>
    this.attempt(async () => {
      await this.api.decideInstallation(device.installationId, "approve", QUICK_REASON.approve);
      this.afterDeviceChange(device.storeId);
      this.notify("اعتُمد الجهاز. يستطيع البدء الآن.");
    });

  quickReject = (device: { installationId: string; storeId: number }) =>
    this.attempt(async () => {
      await this.api.decideInstallation(device.installationId, "revoke", QUICK_REASON.reject);
      this.afterDeviceChange(device.storeId);
      this.notify("رُفض طلب الجهاز.");
    });

  raiseLimitAndApprove = (device: { installationId: string; storeId: number }, store: Store) =>
    this.attempt(async () => {
      await this.api.updateStore(store.id, { allowedDevices: store.allowedDevices + 1 });
      await this.api.decideInstallation(device.installationId, "approve", QUICK_REASON.approve);
      this.afterDeviceChange(device.storeId);
      this.notify(`رُفع الحد إلى ${fmt(store.allowedDevices + 1)} واعتُمد الجهاز.`);
    });

  quickExtend = (store: Store, days: number) =>
    this.attempt(async () => {
      await this.api.subscription(store.id, { mode: "timed", days, extend: true });
      this.loadOverview();
      void this.loadStores();
      if (this.model.store?.id === store.id) this.reloadStore();
      this.notify(`تم تمديد اشتراك ${store.name} ${fmt(days)} يومًا.`);
    });

  // ---- one store ----

  private openStore(id: number, tab: StoreTab) {
    const existing = this.model.store;
    if (existing?.id === id) {
      if (existing.tab !== tab) this.patchStore({ tab });
      if (!existing.detail && !existing.loading) this.reloadStore();
      return;
    }
    const row = this.model.stores.rows.find((store) => store.id === id);
    const state: StoreState = {
      id,
      detail: null,
      summary: row?.summary ?? null,
      loading: true,
      error: "",
      tab,
      ledger: null,
      audit: { ...emptyPage(), source: "control" },
      recovery: emptyRecovery(),
    };
    this.channels.get("ledger")?.abort();
    this.set({
      store: state,
      secret: this.model.secret?.storeId === id ? this.model.secret : null,
    });
    this.reloadStore();
    this.loadStoreAudit([undefined]);
  }

  private leaveStore() {
    for (const name of ["store", "ledger", "storeAudit"]) this.channels.get(name)?.abort();
  }

  private async reloadStore() {
    const state = this.model.store;
    if (!state) return;
    const id = state.id;
    const signal = this.channel("store");
    this.patchStore({ loading: true, error: "" });
    try {
      const detail = await this.api.store(id, signal);
      this.patchStore({ detail, loading: false }, id);
      if (!this.model.store?.summary) await this.fetchSummary(id, detail.store.username);
    } catch (error) {
      if (!isAbort(error)) this.patchStore({ loading: false, error: messageOf(error) }, id);
    }
  }

  /**
   * The detail endpoint carries no ledger counters, so the list row is read by the store's username
   * and the matching row's summary is kept. One request, and only when the list did not have it.
   */
  private async fetchSummary(id: number, username: string) {
    try {
      const result = await this.api.stores({ q: username, includeArchived: true });
      const row = result.stores.find((store) => store.id === id);
      if (row && this.model.store?.id === id) this.patchStore({ summary: row.summary }, id);
    } catch (error) {
      if (!isAbort(error)) void error; // the overview tab shows dashes instead
    }
  }

  /** Changes the open store, unless the admin has since opened another one. */
  private patchStore(patch: Partial<StoreState>, id = this.model.store?.id) {
    const state = this.model.store;
    if (!state || state.id !== id) return;
    this.set({ store: { ...state, ...patch } });
  }

  /** After a write: the store, its audit trail, and the counts and lists that show it. */
  private afterStoreChange() {
    this.reloadStore();
    this.loadOverview();
    void this.loadStores();
    if (this.model.store) this.loadStoreAudit(this.model.store.audit.cursors);
  }

  private currentStore(): Store {
    const store = this.model.store?.detail?.store;
    if (!store) throw failure("store_not_found");
    return store;
  }

  saveSettings = (form: FormData) =>
    this.attempt(async () => {
      const store = this.currentStore();
      await this.api.updateStore(store.id, {
        name: String(form.get("name") ?? "").trim(),
        username: String(form.get("username") ?? "").trim(),
        debtorLimit: integer(form.get("debtorLimit"), 0, 1_000_000),
        allowedDevices: integer(form.get("allowedDevices"), 1, 50),
        verified: form.get("verified") === "on",
        permissions: readPermissions(form),
      });
      this.afterStoreChange();
    }, "تم حفظ إعدادات المتجر.");

  setStatus = (status: "active" | "suspended") => {
    const store = this.model.store?.detail?.store;
    if (!store) return;
    this.openDanger({
      title: status === "active" ? "تفعيل الحساب؟" : "تجميد الحساب؟",
      description:
        status === "active"
          ? "يعود الحساب للعمل. اعتماد الأجهزة يبقى كما هو."
          : "يُوقف الحساب وينهي جلساته فورًا. الدفتر يبقى محفوظًا.",
      confirm: status === "active" ? "تفعيل" : "تجميد",
      username: store.username,
      needsReason: false,
      run: async () => {
        await this.api.updateStore(store.id, { status });
        this.afterStoreChange();
      },
      done: status === "active" ? "تم تفعيل الحساب." : "تم تجميد الحساب وإنهاء جلساته.",
    });
  };

  storeAction = (action: StoreAction) => {
    const store = this.model.store?.detail?.store;
    if (!store) return;
    const text = ACTION_TEXT[action];
    this.openDanger({
      title: `${text.title}؟`,
      description: text.description,
      confirm: text.title,
      username: store.username,
      needsReason: true,
      reason: `${text.title} من لوحة الإدارة`,
      run: async (reason) => {
        await this.api.storeAction(store.id, action, reason);
        this.afterStoreChange();
      },
      done: "تم حفظ الإجراء وتسجيل سببه.",
    });
  };

  resetPassword = () => {
    const store = this.model.store?.detail?.store;
    if (!store) return;
    this.openDialog({
      kind: "confirm",
      title: "إنشاء كلمة مرور مؤقتة",
      description: `تُنهى جلسات ${store.username} الحالية، ويجب تغيير الكلمة المؤقتة عند أول دخول.`,
      confirm: "إنشاء كلمة مؤقتة",
      error: "",
      busy: false,
      run: async () => {
        const result = await this.api.resetPassword(store.id);
        this.set({
          secret: {
            storeId: store.id,
            username: result.username,
            password: result.temporaryPassword,
          },
        });
        this.afterStoreChange();
      },
    });
  };

  saveSubscription = (mode: "permanent" | "timed" | "paused", days?: number) =>
    this.attempt(async () => {
      const store = this.currentStore();
      await this.api.subscription(store.id, {
        mode,
        ...(mode === "timed" ? { days: days ?? 30 } : {}),
      });
      this.afterStoreChange();
    }, "تم تحديث الاشتراك.");

  dismissSecret = () => this.set({ secret: null });

  approveDevice = (installationId: string, storeId: number) =>
    this.openDialog({
      kind: "reason",
      title: "اعتماد جهاز المتجر",
      description: "يصبح الجهاز معتمدًا للمزامنة ضمن حد أجهزة المتجر. لا يُلغى أي جهاز آخر تلقائيًا.",
      confirm: "اعتماد الجهاز",
      danger: false,
      value: QUICK_REASON.approveDialog,
      error: "",
      busy: false,
      run: async (reason) => {
        await this.api.decideInstallation(installationId, "approve", reason);
        this.afterDeviceChange(storeId);
      },
    });

  revokeDevice = (installationId: string, storeId: number) =>
    this.openDialog({
      kind: "reason",
      title: "إلغاء اعتماد الجهاز",
      description: "تتوقف صلاحية هذا الجهاز للمزامنة فورًا. يحتاج تسجيلًا جديدًا واعتمادك ليعود.",
      confirm: "إلغاء الاعتماد",
      danger: true,
      value: QUICK_REASON.revoke,
      error: "",
      busy: false,
      run: async (reason) => {
        await this.api.decideInstallation(installationId, "revoke", reason);
        this.afterDeviceChange(storeId);
      },
    });

  // ---- create ----

  setName = (name: string) => this.set({ create: { ...this.model.create, name } });

  setUsername = (username: string) => this.set({ create: { ...this.model.create, username } });

  setMode = (mode: "permanent" | "timed" | "paused") =>
    this.set({ create: { ...this.model.create, mode } });

  restartCreate = () => this.set({ create: initialModel().create });

  createStore = async (form: FormData) => {
    const state = this.model.create;
    if (state.busy || state.done) return;
    let body: Parameters<Api["createStore"]>[0];
    try {
      const mode = state.mode;
      body = {
        name: String(form.get("name") ?? "").trim(),
        username: String(form.get("username") ?? "").trim(),
        subscriptionMode: mode,
        ...(mode === "timed"
          ? { subscriptionDays: integer(form.get("subscriptionDays"), 1, 36500) }
          : {}),
        allowedDevices: integer(form.get("allowedDevices"), 1, 50),
        debtorLimit: integer(form.get("debtorLimit"), 0, 1_000_000),
        permissions: readPermissions(form),
      };
    } catch (error) {
      return this.set({ create: { ...state, error: messageOf(error) } });
    }
    this.set({ create: { ...state, busy: true, error: "" } });
    try {
      const created = await this.api.createStore(body);
      this.set({
        create: {
          ...initialModel().create,
          done: {
            storeId: created.storeId,
            username: created.username,
            password: created.temporaryPassword,
          },
        },
      });
      this.loadOverview();
      await this.loadStores();
      this.notify("تم إنشاء الحساب. سلّم بيانات الدخول لصاحب المتجر.");
    } catch (error) {
      this.set({ create: { ...state, busy: false, error: messageOf(error) } });
    }
  };

  // ---- recovery ----

  private patchRecovery(patch: Partial<RecoveryState>) {
    const state = this.model.store;
    if (state) this.patchStore({ recovery: { ...state.recovery, ...patch } });
  }

  setRecovery = (patch: Partial<Pick<RecoveryState, "oldId" | "newId" | "manifest" | "reason">>) =>
    this.patchRecovery({ ...patch, error: "" });

  loadManifestFile = async (file: File) => {
    if (file.size > MAX_MANIFEST_BYTES) {
      return this.patchRecovery({ error: "ملف القائمة أكبر من الحد المسموح (4 MB)." });
    }
    try {
      this.patchRecovery({ manifest: await file.text(), error: "" });
    } catch {
      this.patchRecovery({ error: "تعذر قراءة الملف." });
    }
  };

  approveRecovery = () => {
    const state = this.model.store;
    const detail = state?.detail;
    if (!state || !detail) return;
    const recovery = state.recovery;
    const { active, old } = recoveryChoices(detail);
    const oldPhone = old.find((phone) => phone.installationId === recovery.oldId);
    const newPhone = active.find((phone) => phone.installationId === recovery.newId);
    if (!oldPhone || !newPhone)
      return this.patchRecovery({ error: "اختر الهاتف السابق والهاتف المعتمد." });
    if (!recovery.reason.trim())
      return this.patchRecovery({ error: failure("reason_required").message });
    const report = inspectManifest(recovery.manifest, oldPhone);
    const blocking = report.problems.find((problem) => problem.blocking);
    if (blocking || !report.commands || !report.stats) {
      return this.patchRecovery({
        error: `لا يمكن الاعتماد: ${blocking?.message ?? "القائمة غير صالحة."}`,
      });
    }
    const commands = report.commands;
    const storeId = state.id;
    this.openDialog({
      kind: "confirm",
      title: "اعتماد الاسترداد",
      description: `اعتماد استرداد ${fmt(commands.length)} إدخالًا من الهاتف السابق. الموافقة تشمل هذه الأوامر الأصلية فقط ولا يمكن التراجع عنها.`,
      confirm: "اعتماد الاسترداد نهائيًا",
      error: "",
      busy: false,
      run: async () => {
        const permit = await this.api.approveRecovery({
          oldInstallationId: oldPhone.installationId,
          newInstallationId: newPhone.installationId,
          commands,
          reason: recovery.reason.trim(),
        });
        if (this.model.store?.id !== storeId) return;
        this.patchRecovery({ ...emptyRecovery(), permit });
        this.notify("سُجلت موافقة الاسترداد.");
        this.reloadStore();
      },
    });
  };

  // ---- ledger ----

  private patchLedger(patch: Partial<LedgerState>, id: number) {
    const ledger = this.model.store?.ledger;
    if (ledger && this.model.store?.id === id)
      this.patchStore({ ledger: { ...ledger, ...patch } }, id);
  }

  private async loadLedger(cursors: (string | undefined)[]) {
    const state = this.model.store;
    const ledger = state?.ledger;
    if (!state || !ledger) return;
    const signal = this.channel("ledger");
    this.patchLedger({ cursors, loading: true, error: "" }, state.id);
    try {
      const { nextCursor, ...page } = await this.api.ledger(
        state.id,
        ledger.kind,
        { q: ledger.q, cursor: cursors.at(-1), reason: ledger.reason },
        signal,
      );
      this.patchLedger({ page, next: nextCursor, loading: false }, state.id);
    } catch (error) {
      if (!isAbort(error)) this.patchLedger({ loading: false, error: messageOf(error) }, state.id);
    }
  }

  openLedger = (reason: string) => {
    if (!reason.trim()) return this.notify(failure("reason_required").message, true);
    this.patchStore({
      ledger: {
        kind: "contacts",
        q: "",
        reason: reason.trim(),
        page: null,
        cursors: [undefined],
        next: null,
        loading: true,
        error: "",
      },
    });
    void this.loadLedger([undefined]);
  };

  setLedgerKind = (kind: LedgerKind) => {
    const ledger = this.model.store?.ledger;
    if (!ledger || !this.model.store) return;
    this.patchLedger({ kind, q: "" }, this.model.store.id);
    void this.loadLedger([undefined]);
  };

  searchLedger = (q: string) => {
    if (!this.model.store?.ledger) return;
    this.patchLedger({ q }, this.model.store.id);
    void this.loadLedger([undefined]);
  };

  pageLedger = (step: PageStep) => {
    const ledger = this.model.store?.ledger;
    if (!ledger) return;
    const cursors = moveCursors({ ...ledger, rows: [] }, step);
    if (cursors) void this.loadLedger(cursors);
  };

  /** Every page of the current section, read with the same audited reason. */
  downloadLedger = () =>
    this.attempt(
      async () => {
        const state = this.model.store;
        const ledger = state?.ledger;
        if (!state || !ledger) return;
        const columns = LEDGER_COLUMNS[ledger.kind];
        const lines = [columns.map(([label]) => csvCell(label)).join(",")];
        let cursor: string | undefined;
        do {
          const page = await this.api.ledger(state.id, ledger.kind, {
            q: ledger.q,
            cursor,
            reason: ledger.reason,
            limit: 200,
          });
          for (const row of page.rows)
            lines.push(columns.map(([, cell]) => csvCell(cell(row))).join(","));
          cursor = page.nextCursor ?? undefined;
        } while (cursor);
        const blob = new Blob([`\ufeff${lines.join("\r\n")}`], { type: "text/csv;charset=utf-8" });
        const anchor = document.createElement("a");
        anchor.href = URL.createObjectURL(blob);
        anchor.download = `sadad-store-${state.id}-${ledger.kind}.csv`;
        anchor.click();
        URL.revokeObjectURL(anchor.href);
      },
      `تم تنزيل ${LEDGER_KINDS[this.model.store?.ledger?.kind ?? "contacts"]}.`,
    );

  // ---- store audit ----

  private patchStoreAudit(patch: Partial<StoreState["audit"]>, id: number) {
    const audit = this.model.store?.audit;
    if (audit) this.patchStore({ audit: { ...audit, ...patch } }, id);
  }

  private async loadStoreAudit(cursors: (string | undefined)[]) {
    const state = this.model.store;
    if (!state) return;
    const id = state.id;
    const signal = this.channel("storeAudit");
    this.patchStoreAudit({ cursors, loading: true, error: "" }, id);
    try {
      const result = await this.api.audit(
        { source: state.audit.source, storeId: id, cursor: cursors.at(-1) },
        signal,
      );
      this.patchStoreAudit({ rows: result.events, next: result.nextCursor, loading: false }, id);
    } catch (error) {
      if (isAbort(error)) return;
      this.patchStoreAudit({ rows: [], next: null, loading: false, error: messageOf(error) }, id);
    }
  }

  setStoreAuditSource = (source: AuditSource) => {
    const state = this.model.store;
    if (!state) return;
    this.patchStoreAudit({ source }, state.id);
    void this.loadStoreAudit([undefined]);
  };

  pageStoreAudit = (step: PageStep) => {
    const audit = this.model.store?.audit;
    const cursors = audit && moveCursors(audit, step);
    if (cursors) void this.loadStoreAudit(cursors);
  };

  // ---- dialogs ----

  private openDialog(dialog: Dialog) {
    this.focusDialog = true;
    this.set({ dialog });
  }

  private openDanger(options: {
    title: string;
    description: string;
    confirm: string;
    username: string;
    needsReason: boolean;
    reason?: string;
    run: (reason: string) => Promise<void>;
    done: string;
  }) {
    this.openDialog({
      kind: "danger",
      title: options.title,
      description: options.description,
      confirm: options.confirm,
      username: options.username,
      typed: "",
      needsReason: options.needsReason,
      reason: options.reason ?? "",
      error: "",
      busy: false,
      run: async (reason) => {
        await options.run(reason);
        this.notify(options.done);
      },
    });
  }

  dialogReason = (value: string) => {
    const dialog = this.model.dialog;
    if (!dialog || (dialog.kind !== "reason" && dialog.kind !== "danger")) return;
    this.set({
      dialog: dialog.kind === "reason" ? { ...dialog, value } : { ...dialog, reason: value },
    });
  };

  dialogTyped = (value: string) => {
    const dialog = this.model.dialog;
    if (dialog?.kind !== "danger") return;
    this.set({ dialog: { ...dialog, typed: value } });
  };

  submitDialog = async () => {
    const dialog = this.model.dialog;
    if (!dialog || dialog.busy) return;
    const reason =
      dialog.kind === "danger"
        ? dialog.reason.trim()
        : dialog.kind === "reason"
          ? dialog.value.trim()
          : "";
    if (dialog.kind === "danger" && dialog.typed.trim() !== dialog.username) {
      return this.set({ dialog: { ...dialog, error: "اكتب اسم الدخول كما هو للتأكيد." } });
    }
    const needsReason =
      dialog.kind === "reason" || (dialog.kind === "danger" && dialog.needsReason);
    if (needsReason && !reason) {
      return this.set({ dialog: { ...dialog, error: failure("reason_required").message } });
    }
    this.set({ dialog: { ...dialog, busy: true, error: "" } });
    try {
      await (dialog.kind === "confirm" ? dialog.run() : dialog.run(reason));
      if (this.model.dialog === null || this.model.dialog.kind === dialog.kind) {
        this.set({ dialog: null });
      }
    } catch (error) {
      this.set({ dialog: { ...dialog, busy: false, error: messageOf(error) } });
    }
  };

  closeDialog = () => {
    if (this.model.dialog && !this.model.dialog.busy) this.set({ dialog: null });
  };
}
