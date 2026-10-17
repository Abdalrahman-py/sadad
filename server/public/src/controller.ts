// Owns the Model, talks to Auth and the api, and re-renders after every change. Every list is
// paged and searched on the server; a newer request aborts the older one on the same channel.
import { render } from "lit-html";
import type { Api, AuditEvent, AuditSource, LedgerKind, StoreAction } from "./api";
import { failure, messageOf } from "./errors";
import { integer } from "./format";
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
  type StoreState,
  type Tab,
} from "./model";
import { inspectManifest, MAX_MANIFEST_BYTES } from "./recovery-manifest";
import { checkCaller, type SessionPort } from "./session";
import { app as view } from "./views/app";
import { readPermissions } from "./views/common";
import { LEDGER_COLUMNS, LEDGER_KINDS } from "./views/ledger";
import { recoveryChoices } from "./views/recovery";

const ACTION_TEXT: Record<StoreAction, { title: string; description: string; danger: boolean }> = {
  archive: {
    title: "أرشفة الحساب",
    description: "يُوقف الحساب وتُلغى صلاحية أجهزته وتُنهى جلساته. يبقى الدفتر والسجل محفوظين.",
    danger: true,
  },
  unarchive: {
    title: "إعادة الحساب من الأرشيف",
    description: "يعود الحساب مجمّدًا مع دفتره. فعّله واعتمد جهازًا جديدًا بعد ذلك.",
    danger: false,
  },
  "end-sessions": {
    title: "إنهاء جلسات الدخول",
    description: "تُنهى كل جلسات المتجر الآن. اعتماد الأجهزة يبقى كما هو.",
    danger: false,
  },
  "revoke-all": {
    title: "إلغاء اعتماد جميع الأجهزة",
    description: "تتوقف صلاحية كل أجهزة المتجر للمزامنة. يحتاج كل جهاز تسجيلًا جديدًا واعتمادك.",
    danger: true,
  },
};

const NOTICE_MS = 6000;

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

function moveCursors(page: Paged<unknown>, step: PageStep): (string | undefined)[] | null {
  if (step === "next") return page.next ? [...page.cursors, page.next] : null;
  return page.cursors.length > 1 ? page.cursors.slice(0, -1) : null;
}

/** Cells that start with =, +, - or @ would run as formulas in a spreadsheet. */
const csvCell = (value: string) =>
  `"${(/^[=+\-@\t\r]/.test(value) ? `'${value}` : value).replaceAll('"', '""')}"`;

export class Controller implements Actions {
  model: Model = initialModel();
  private channels = new Map<string, AbortController>();
  private scrollToStore = false;

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
    if (this.scrollToStore) {
      this.scrollToStore = false;
      this.root
        .querySelector("#store-panel")
        ?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }

  private set(patch: Partial<Model>) {
    Object.assign(this.model, patch);
    this.render();
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
    const caller = await this.session.current().catch(() => null);
    if (!caller) return this.set({ booting: false });
    try {
      this.set({ booting: false, caller: checkCaller(caller) });
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
      this.set({ caller, signIn: { error: "", busy: false } });
      this.loadAll();
    } catch (error) {
      await this.session.signOut();
      this.set({ signIn: { error: messageOf(error), busy: false } });
    }
  };

  signOut = async () => {
    this.abortAll();
    await this.session.signOut();
    this.model = { ...initialModel(), booting: false };
    this.render();
  };

  /** The api refused the token (expired or ended session). */
  unauthorized = () => {
    if (!this.model.caller) return;
    this.abortAll();
    void this.session.signOut();
    this.model = {
      ...initialModel(),
      booting: false,
      signIn: { error: failure("authorization_required").message, busy: false },
    };
    this.render();
  };

  // ---- lists ----

  private loadAll() {
    this.loadOverview();
    this.loadTab();
    if (this.model.store) this.reloadStore();
  }

  private async loadOverview() {
    try {
      this.set({ overview: await this.api.overview(this.channel("overview")) });
    } catch (error) {
      if (!isAbort(error)) this.notify(messageOf(error), true);
    }
  }

  private loadTab() {
    const tab = this.model.tab;
    if (tab === "stores") this.loadStores(this.model.stores.cursors);
    else if (tab === "installations") this.loadPending(this.model.pending.cursors);
    else this.loadAudit(this.model.audit.cursors);
  }

  private async loadPaged<T, P extends Paged<T>>(
    name: string,
    get: () => P,
    put: (page: P) => void,
    cursors: (string | undefined)[],
    fetch: (
      cursor: string | undefined,
      signal: AbortSignal,
    ) => Promise<{ rows: T[]; next: string | null }>,
  ) {
    const signal = this.channel(name);
    put({ ...get(), cursors, loading: true, error: "" });
    this.render();
    try {
      const { rows, next } = await fetch(cursors.at(-1), signal);
      put({ ...get(), rows, next, loading: false });
    } catch (error) {
      if (isAbort(error)) return;
      put({ ...get(), rows: [], next: null, loading: false, error: messageOf(error) });
    }
    this.render();
  }

  private loadStores(cursors: (string | undefined)[]) {
    const { q, includeArchived } = this.model.stores;
    return this.loadPaged(
      "stores",
      () => this.model.stores,
      (page) => {
        this.model.stores = page;
      },
      cursors,
      async (cursor, signal) => {
        const r = await this.api.stores({ q, cursor, includeArchived }, signal);
        return { rows: r.stores, next: r.nextCursor };
      },
    );
  }

  private loadPending(cursors: (string | undefined)[]) {
    const { q } = this.model.pending;
    return this.loadPaged(
      "pending",
      () => this.model.pending,
      (page) => {
        this.model.pending = page;
      },
      cursors,
      async (cursor, signal) => {
        const r = await this.api.pendingInstallations({ q, cursor }, signal);
        return { rows: r.installations, next: r.nextCursor };
      },
    );
  }

  private loadAudit(cursors: (string | undefined)[]) {
    const { q, source } = this.model.audit;
    return this.loadPaged(
      "audit",
      () => this.model.audit,
      (page) => {
        this.model.audit = page;
      },
      cursors,
      async (cursor, signal) => {
        const r = await this.api.audit({ source, q, cursor }, signal);
        return { rows: r.events, next: r.nextCursor };
      },
    );
  }

  refresh = () => this.loadAll();

  setTab = (tab: Tab) => {
    this.set({ tab });
    this.loadTab();
  };

  dismissNotice = () => this.set({ notice: null });

  searchStores = (q: string) => {
    this.model.stores = { ...this.model.stores, q };
    this.loadStores([undefined]);
  };

  setIncludeArchived = (includeArchived: boolean) => {
    this.model.stores = { ...this.model.stores, includeArchived };
    this.loadStores([undefined]);
  };

  pageStores = (step: PageStep) => {
    const cursors = moveCursors(this.model.stores, step);
    if (cursors) this.loadStores(cursors);
  };

  searchPending = (q: string) => {
    this.model.pending = { ...this.model.pending, q };
    this.loadPending([undefined]);
  };

  pagePending = (step: PageStep) => {
    const cursors = moveCursors(this.model.pending, step);
    if (cursors) this.loadPending(cursors);
  };

  searchAudit = (q: string) => {
    this.model.audit = { ...this.model.audit, q };
    this.loadAudit([undefined]);
  };

  setAuditSource = (source: AuditSource) => {
    this.model.audit = { ...this.model.audit, source };
    this.loadAudit([undefined]);
  };

  pageAudit = (step: PageStep) => {
    const cursors = moveCursors(this.model.audit, step);
    if (cursors) this.loadAudit(cursors);
  };

  // ---- create ----

  openCreateStore = () => this.set({ dialog: { kind: "create", error: "", busy: false } });

  createStore = async (form: FormData) => {
    const dialog = this.model.dialog;
    if (dialog?.kind !== "create" || dialog.busy) return;
    let body: Parameters<Api["createStore"]>[0];
    try {
      const subscriptionMode = String(form.get("subscriptionMode")) as
        | "permanent"
        | "timed"
        | "paused";
      body = {
        name: String(form.get("name") ?? "").trim(),
        username: String(form.get("username") ?? "").trim(),
        subscriptionMode,
        ...(subscriptionMode === "timed"
          ? { subscriptionDays: integer(form.get("subscriptionDays"), 1, 36500) }
          : {}),
        allowedDevices: integer(form.get("allowedDevices"), 1, 50),
        debtorLimit: integer(form.get("debtorLimit"), 0, 1_000_000),
        verified: form.get("verified") === "on",
        permissions: readPermissions(form),
      };
    } catch (error) {
      return this.set({ dialog: { ...dialog, error: messageOf(error) } });
    }
    this.set({ dialog: { ...dialog, busy: true, error: "" } });
    try {
      const created = await this.api.createStore(body);
      this.set({
        dialog: null,
        secret: {
          storeId: created.storeId,
          username: created.username,
          password: created.temporaryPassword,
        },
      });
      this.notify(
        "تم إنشاء الحساب. يسجل صاحب المتجر الدخول ويغيّر كلمة المرور ثم يطلب اعتماد جهازه.",
      );
      this.loadOverview();
      this.model.stores = { ...this.model.stores, q: "" };
      if (this.model.tab !== "stores") this.set({ tab: "stores" });
      this.loadStores([undefined]);
      this.openStore(created.storeId);
    } catch (error) {
      this.set({ dialog: { ...dialog, busy: false, error: messageOf(error) } });
    }
  };

  // ---- one store ----

  openStore = (storeId: number) => {
    this.channels.get("ledger")?.abort();
    const state: StoreState = {
      id: storeId,
      detail: null,
      loading: true,
      error: "",
      ledger: null,
      audit: { ...emptyPage(), source: "control" },
      recovery: emptyRecovery(),
    };
    if (this.model.secret && this.model.secret.storeId !== storeId) this.model.secret = null;
    this.scrollToStore = true;
    this.set({ store: state });
    this.reloadStore();
  };

  closeStore = () => {
    for (const name of ["store", "ledger", "storeAudit"]) this.channels.get(name)?.abort();
    this.set({ store: null, secret: null });
  };

  private async reloadStore() {
    const state = this.model.store;
    if (!state) return;
    const id = state.id;
    this.loadStoreAudit(state.audit.cursors);
    const signal = this.channel("store");
    this.patchStore({ loading: true, error: "" });
    try {
      const detail = await this.api.store(id, signal);
      this.patchStore({ detail, loading: false }, id);
    } catch (error) {
      if (!isAbort(error)) this.patchStore({ loading: false, error: messageOf(error) }, id);
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
    this.loadTab();
  }

  private currentStore() {
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

  setStatus = (status: "active" | "suspended") =>
    this.attempt(
      async () => {
        await this.api.updateStore(this.currentStore().id, { status });
        this.afterStoreChange();
      },
      status === "active" ? "تم تفعيل الحساب." : "تم تجميد الحساب وإنهاء جلساته.",
    );

  saveSubscription = (form: FormData) =>
    this.attempt(async () => {
      const mode = String(form.get("mode")) as "permanent" | "timed" | "paused";
      await this.api.subscription(this.currentStore().id, {
        mode,
        ...(mode === "timed" ? { days: integer(form.get("days"), 1, 36500) } : {}),
      });
      this.afterStoreChange();
    }, "تم تحديث الاشتراك.");

  extendSubscription = (form: FormData) =>
    this.attempt(async () => {
      await this.api.subscription(this.currentStore().id, {
        mode: "timed",
        days: integer(form.get("days"), 1, 36500),
        extend: true,
      });
      this.afterStoreChange();
    }, "تم تمديد الاشتراك.");

  resetPassword = () => {
    const store = this.model.store?.detail?.store;
    if (!store) return;
    this.openDialog({
      kind: "confirm",
      title: "إنشاء كلمة مرور مؤقتة",
      description: `تُنهى جلسات ${store.username} الحالية، ويجب تغيير الكلمة المؤقتة عند أول دخول.`,
      confirm: "إنشاء كلمة مؤقتة",
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

  storeAction = (action: StoreAction) => {
    const store = this.model.store?.detail?.store;
    if (!store) return;
    const text = ACTION_TEXT[action];
    this.openDialog({
      kind: "reason",
      ...text,
      confirm: "تأكيد الإجراء",
      run: async (reason) => {
        await this.api.storeAction(store.id, action, reason);
        this.notify("تم حفظ الإجراء وتسجيل سببه.");
        this.afterStoreChange();
      },
    });
  };

  openDelete = () => {
    const store = this.model.store?.detail?.store;
    if (!store) return;
    this.set({
      dialog: {
        kind: "delete",
        storeId: store.id,
        username: store.username,
        error: "",
        busy: false,
      },
    });
  };

  deleteStore = async (confirm: string) => {
    const dialog = this.model.dialog;
    if (dialog?.kind !== "delete" || dialog.busy) return;
    if (confirm.trim() !== dialog.username) {
      return this.set({
        dialog: { ...dialog, error: failure("delete_confirmation_required").message },
      });
    }
    this.set({ dialog: { ...dialog, busy: true, error: "" } });
    try {
      await this.api.deleteStore(dialog.storeId, confirm.trim());
      this.set({ dialog: null });
      this.closeStore();
      this.notify("تم حذف الحساب نهائيًا.");
      this.loadOverview();
      this.loadTab();
    } catch (error) {
      this.set({ dialog: { ...dialog, busy: false, error: messageOf(error) } });
    }
  };

  decideInstallation = (installationId: string, decision: "approve" | "revoke", storeId: number) =>
    this.openDialog({
      kind: "reason",
      title: decision === "approve" ? "اعتماد جهاز المتجر" : "إلغاء اعتماد الجهاز",
      description:
        decision === "approve"
          ? "يصبح الجهاز معتمدًا للمزامنة ضمن حد أجهزة المتجر. لا يُلغى أي جهاز آخر تلقائيًا."
          : "تتوقف صلاحية هذا الجهاز للمزامنة فورًا. يحتاج تسجيلًا جديدًا واعتمادك ليعود.",
      confirm: "تأكيد الإجراء",
      danger: decision === "revoke",
      run: async (reason) => {
        await this.api.decideInstallation(installationId, decision, reason);
        this.notify(decision === "approve" ? "تم اعتماد الجهاز." : "تم إلغاء اعتماد الجهاز.");
        if (this.model.store?.id === storeId) this.reloadStore();
        this.loadOverview();
        this.loadTab();
      },
    });

  dismissSecret = () => this.set({ secret: null });

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
    const oldPhone = old.find((p) => p.installationId === recovery.oldId);
    const newPhone = active.find((p) => p.installationId === recovery.newId);
    if (!oldPhone || !newPhone)
      return this.patchRecovery({ error: "اختر الهاتف السابق والهاتف المعتمد." });
    if (!recovery.reason.trim())
      return this.patchRecovery({ error: failure("reason_required").message });
    const report = inspectManifest(recovery.manifest, oldPhone);
    const blocking = report.problems.find((p) => p.blocking);
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
      description: `اعتماد استرداد ${commands.length} إدخالًا من الهاتف السابق. الموافقة تشمل هذه الأوامر الأصلية فقط ولا يمكن التراجع عنها.`,
      confirm: "اعتماد الاسترداد نهائيًا",
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
    this.loadLedger([undefined]);
  };

  setLedgerKind = (kind: LedgerKind) => {
    const ledger = this.model.store?.ledger;
    if (!ledger || !this.model.store) return;
    this.patchLedger({ kind, q: "" }, this.model.store.id);
    this.loadLedger([undefined]);
  };

  searchLedger = (q: string) => {
    if (!this.model.store?.ledger) return;
    this.patchLedger({ q }, this.model.store.id);
    this.loadLedger([undefined]);
  };

  pageLedger = (step: PageStep) => {
    const ledger = this.model.store?.ledger;
    if (!ledger) return;
    const cursors = moveCursors({ ...ledger, rows: [] }, step);
    if (cursors) this.loadLedger(cursors);
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
        const blob = new Blob([`﻿${lines.join("\r\n")}`], { type: "text/csv;charset=utf-8" });
        const anchor = document.createElement("a");
        anchor.href = URL.createObjectURL(blob);
        anchor.download = `sadad-store-${state.id}-${ledger.kind}.csv`;
        anchor.click();
        URL.revokeObjectURL(anchor.href);
      },
      `تم تنزيل ${LEDGER_KINDS[this.model.store?.ledger?.kind ?? "contacts"]}.`,
    );

  // ---- store audit ----

  private loadStoreAudit(cursors: (string | undefined)[]) {
    const state = this.model.store;
    if (!state) return;
    const id = state.id;
    const source = state.audit.source;
    return this.loadPaged(
      "storeAudit",
      () => this.model.store?.audit ?? { ...emptyPage<AuditEvent>(), source },
      (page) => {
        if (this.model.store?.id === id) this.model.store = { ...this.model.store, audit: page };
      },
      cursors,
      async (cursor, signal) => {
        const r = await this.api.audit({ source, storeId: id, cursor }, signal);
        return { rows: r.events, next: r.nextCursor };
      },
    );
  }

  setStoreAuditSource = (source: AuditSource) => {
    const state = this.model.store;
    if (!state) return;
    this.model.store = { ...state, audit: { ...state.audit, source } };
    this.loadStoreAudit([undefined]);
  };

  pageStoreAudit = (step: PageStep) => {
    const audit = this.model.store?.audit;
    const cursors = audit && moveCursors(audit, step);
    if (cursors) this.loadStoreAudit(cursors);
  };

  // ---- dialogs ----

  private openDialog(
    dialog:
      | Omit<Extract<Dialog, { kind: "reason" }>, "error" | "busy">
      | Omit<Extract<Dialog, { kind: "confirm" }>, "error" | "busy">,
  ) {
    this.set({ dialog: { ...dialog, error: "", busy: false } as Dialog });
  }

  submitDialog = async (value: string) => {
    const dialog = this.model.dialog;
    if (!dialog || dialog.busy) return;
    if (dialog.kind === "delete") return this.deleteStore(value);
    if (dialog.kind !== "reason" && dialog.kind !== "confirm") return;
    const reason = value.trim();
    if (dialog.kind === "reason" && !reason) {
      return this.set({ dialog: { ...dialog, error: failure("reason_required").message } });
    }
    this.set({ dialog: { ...dialog, busy: true, error: "" } });
    try {
      await (dialog.kind === "reason" ? dialog.run(reason) : dialog.run());
      if (this.model.dialog === null || this.model.dialog.kind === dialog.kind)
        this.set({ dialog: null });
    } catch (error) {
      this.set({ dialog: { ...dialog, busy: false, error: messageOf(error) } });
    }
  };

  closeDialog = () => {
    if (this.model.dialog && !this.model.dialog.busy) this.set({ dialog: null });
  };
}
