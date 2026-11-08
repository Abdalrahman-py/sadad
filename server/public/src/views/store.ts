import { html, nothing, type TemplateResult } from "lit-html";
import type { DetailInstallation, Store, StoreDetail } from "../api";
import { dateTime, day, daysLeft, fmt, money } from "../format";
import {
  canWrite,
  type Model,
  STORE_NAV,
  STORE_TAB_LABELS,
  type StoreActions,
  type StoreState,
  type StoreTab,
  type StoreTabActions,
} from "../model";
import {
  arabicCount,
  arabicDays,
  statusText,
  statusTone,
  subscriptionState,
  type Tone,
} from "../subscription";
import { auditList, sourceFilter } from "./audit";
import {
  copyButton,
  DEVICE_TONE,
  INSTALLATION_STATE,
  loadState,
  metric,
  skeleton,
  state as stateWord,
} from "./common";
import { ledgerView } from "./ledger";
import { recoveryView } from "./recovery";
import { settingsView } from "./settings";

const tabLink = (storeId: number, tab: StoreTab, current: StoreTab, count: number) => {
  // Settings and recovery are pages of the account tab, so it stays lit on them.
  const active = current === tab || (tab === "account" && !STORE_NAV.includes(current));
  return html`
    <a class="tab" href=${`#/stores/${storeId}/${tab}`} aria-current=${active ? "page" : "false"}>
      ${STORE_TAB_LABELS[tab]}${count ? html`<span class="count" aria-label=${`${fmt(count)} يحتاج إجراء`}>${fmt(count)}</span>` : nothing}
    </a>`;
};

const tabs = (storeId: number, current: StoreTab, pending: number) => html`
  <nav class="tabs" aria-label="أقسام المتجر">
    ${STORE_NAV.map((tab) => tabLink(storeId, tab, current, tab === "account" ? pending : 0))}
  </nav>
`;

const facts = (detail: StoreDetail, state: StoreState) => {
  const store = detail.store;
  const sub = subscriptionState(store);
  const summary = state.summary;
  const active = detail.installations.filter((device) => device.status === "active").length;
  return html`
    <div class="facts">
      ${metric(
        "الأشخاص النشطون",
        summary?.initialized ? fmt(summary.activeContacts ?? summary.contacts) : "—",
        `من حد ${fmt(store.debtorLimit)}`,
      )}
      ${metric("مستحق للمتجر", summary?.initialized ? money(summary.receivableCents) : "—")}
      ${metric("الأجهزة", fmt(active), `من ${fmt(store.allowedDevices)}`)}
      ${metric(
        "الاشتراك",
        stateWord(
          store.subscriptionMode === "timed"
            ? arabicDays(daysLeft(store.subscriptionRemainingMs))
            : sub.label,
          sub.tone,
        ),
        store.subscriptionExpiresAt ? `حتى ${day(store.subscriptionExpiresAt)}` : undefined,
      )}
    </div>
  `;
};

const deviceRow = (
  phone: DetailInstallation,
  store: Store,
  write: boolean,
  actions: StoreActions,
) => {
  const tone: Tone = DEVICE_TONE[phone.status];
  const who = [phone.staffName, phone.label].filter(Boolean).join(" · ") || "جهاز";
  const meta = [
    `الجيل ${fmt(phone.generation)}`,
    `سُجل ${dateTime(phone.createdAt)}`,
    phone.approvedBy ? `اعتمده ${phone.approvedBy}` : "",
    phone.reason ?? "",
  ]
    .filter(Boolean)
    .join(" · ");
  return html`
    <div class="list-row">
      <div class="grow">
        <div class="row">
          <b>${who}</b>
          ${stateWord(INSTALLATION_STATE[phone.status], tone)}
        </div>
        <span class="sub">${meta}</span>
        <span class="sub"><code dir="ltr" title=${phone.installationId}>${phone.installationId.slice(0, 8)}</code></span>
      </div>
      ${
        write && phone.status === "revoked"
          ? html`<button class="btn btn-primary" type="button" ?disabled=${store.archived}
              @click=${() => actions.reactivateDevice(phone.installationId, store.id)}>
              إعادة الاعتماد</button>`
          : write
            ? html`<div class="btn-group">
              ${
                phone.status === "pending"
                  ? html`<button class="btn btn-primary" type="button"
                      ?disabled=${store.archived}
                      @click=${() => actions.approveDevice(phone.installationId, store.id)}>اعتماد</button>`
                  : nothing
              }
              <button class="btn" type="button" ?disabled=${store.archived}
                @click=${() => actions.revokeDevice(phone.installationId, store.id)}>
                ${phone.status === "pending" ? "رفض" : "إلغاء الاعتماد"}</button>
            </div>`
            : nothing
      }
    </div>
  `;
};

const devicesSection = (detail: StoreDetail, write: boolean, actions: StoreActions) => {
  const store = detail.store;
  const active = detail.installations.filter((device) => device.status === "active").length;
  return html`
    <section class="section" aria-labelledby="devices-h">
      <div class="spread">
        <h2 id="devices-h">الأجهزة</h2>
        <span class="muted grow">
          مسموح ${arabicCount(store.allowedDevices, "بجهاز واحد", "بجهازين", "أجهزة", "جهازًا")}،
          المعتمد الآن ${fmt(active)}. الاعتماد لا يلغي جهازًا آخر تلقائيًا.
        </span>
        ${write ? html`<a href=${`#/stores/${store.id}/settings`}>تغيير الحد</a>` : nothing}
      </div>
      ${
        detail.installations.length
          ? html`<div class="list">${detail.installations.map((phone) => deviceRow(phone, store, write, actions))}</div>`
          : html`<p class="empty">لا توجد أجهزة. يسجل صاحب المتجر الدخول ويغيّر كلمة المرور المؤقتة ثم يظهر طلب جهازه هنا.</p>`
      }
    </section>
  `;
};

const subscriptionSection = (
  detail: StoreDetail,
  write: boolean,
  working: string[],
  actions: StoreActions,
) => {
  const store = detail.store;
  const busy = working.includes(`extend:${store.id}`);
  const sub = subscriptionState(store);
  const mode = store.subscriptionMode;
  const seg = ([id, label]: [Store["subscriptionMode"], string]) => html`
    <button type="button" aria-pressed=${mode === id} ?disabled=${!write || store.archived}
      @click=${() => id !== "timed" && actions.saveSubscription(id)}>${label}</button>`;
  return html`
    <section class="section" aria-labelledby="sub-h">
      <div class="stack">
        <div class="spread">
          <h2 id="sub-h">الاشتراك</h2>
          <span class="grow">${stateWord(sub.detail, sub.tone)}</span>
        </div>
        ${
          store.archived
            ? html`<p class="small muted">الحساب مؤرشف: أعد الحساب من الأرشيف أولًا من «الإعدادات».</p>`
            : nothing
        }
        ${
          write && !store.archived
            ? html`
              <div class="stack">
                <h3>تمديد سريع</h3>
                <div class="btn-group">
                  ${[30, 90, 365].map(
                    (
                      days,
                    ) => html`<button class="btn" type="button" ?disabled=${store.archived || busy}
                      @click=${() => actions.quickExtend(store, days)}>+ ${arabicDays(days)}</button>`,
                  )}
                </div>
              </div>
              <div class="stack">
                <h3 id="mode-l">نوع الاشتراك</h3>
                <div class="seg" role="group" aria-labelledby="mode-l">
                  ${seg(["permanent", "دائم"])}
                  ${seg(["timed", "بعدد أيام"])}
                  ${seg(["paused", "إيقاف مؤقت"])}
                </div>
                <span class="small muted">
                  «إيقاف مؤقت» ينهي جلسات المتجر ويحفظ المدة المتبقية، ويعيدها عند التفعيل من جديد.
                </span>
                <form class="stack" @submit=${(event: SubmitEvent) => {
                  event.preventDefault();
                  const form = new FormData(event.currentTarget as HTMLFormElement);
                  const days = Number(form.get("days") ?? 0);
                  if (Number.isSafeInteger(days) && days > 0)
                    actions.saveSubscription("timed", days);
                }}>
                  <label class="field">عدد أيام التفعيل
                    <input name="days" inputmode="numeric" value="30" ?disabled=${store.archived}>
                  </label>
                  <div><button class="btn btn-primary" ?disabled=${store.archived}>تفعيل بعدد الأيام</button></div>
                </form>
              </div>`
            : !write
              ? html`<p class="small muted">تعديل الاشتراك للإدارة فقط.</p>`
              : nothing
        }
      </div>
    </section>
  `;
};

function body(
  detail: StoreDetail,
  state: StoreState,
  write: boolean,
  working: string[],
  actions: StoreActions,
  tabActions: StoreTabActions,
): TemplateResult {
  const account = html`<a href=${`#/stores/${detail.store.id}`}>الحساب</a>`;
  switch (state.tab) {
    case "account":
      return html`
        ${facts(detail, state)}
        ${devicesSection(detail, write, actions)}
        ${subscriptionSection(detail, write, working, actions)}`;
    case "settings":
      return html`${account}${settingsView(detail, write, actions)}`;
    case "ledger":
      return ledgerView(detail, state.ledger, tabActions);
    case "recovery":
      return html`${account}${recoveryView(detail, state.recovery, write, tabActions)}`;
    case "audit":
      return html`<section class="section">
        ${sourceFilter(state.audit.source, tabActions.setStoreAuditSource)}
        ${auditList(state.audit, false, "صفحات سجل المتجر", tabActions.pageStoreAudit)}
      </section>`;
  }
}

/** The store beside the list. The list row fills the header until the detail arrives. */
export function storeView(model: Model, actions: StoreActions, tabActions: StoreTabActions) {
  const state = model.store;
  if (!state) return skeleton();
  const detail = state.detail;
  const store = detail?.store ?? model.stores.rows.find((row) => row.id === state.id);
  const close = html`<a class="close" href="#/stores">كل المتاجر</a>`;
  if (!store) return html`${close}${state.error ? loadState(state) : skeleton()}`;
  const write = canWrite(model);
  const sub = subscriptionState(store);
  const pending = detail
    ? detail.installations.filter((device) => device.status === "pending").length
    : 0;
  const secret = model.secret?.storeId === store.id ? model.secret : null;
  return html`
    ${close}
    ${
      write
        ? nothing
        : html`<div class="note" role="note">
            حسابك للدعم: تعرض كل شيء، والتعديل للإدارة فقط. قراءة الدفتر تُسجَّل باسمك مع السبب.
          </div>`
    }
    <header class="page-head">
      <div>
        <h1>${store.name}</h1>
        <div class="meta">
          <span>رقم ${fmt(store.id)}</span>
          <span dir="ltr">${store.username}</span>
          <span>أُنشئ ${day(store.createdAt)}</span>
          ${stateWord(statusText(store), statusTone(store))}
          ${stateWord(sub.label, sub.tone)}
        </div>
      </div>
      <div class="btn-group">
        ${
          write
            ? html`<button class="btn" type="button" ?disabled=${store.archived}
                @click=${actions.resetPassword}>كلمة مرور مؤقتة</button>`
            : nothing
        }
        <a href=${`#/stores/${store.id}/settings`}>الإعدادات</a>
        <a href=${`#/stores/${store.id}/recovery`}>الاسترداد</a>
      </div>
    </header>

    ${
      secret
        ? html`<div class="secret" role="status">
            <div class="grow">
              <b>كلمة المرور المؤقتة لـ <span dir="ltr">${secret.username}</span></b>
              <p class="small">
                تظهر مرة واحدة فقط. انسخها وأرسلها لصاحب المتجر بطريقة آمنة. انتهت جلساته الحالية،
                وسيُطلب منه تغييرها عند الدخول.
              </p>
            </div>
            <code>${secret.password}</code>
            ${copyButton(secret.password)}
            <button class="btn" type="button" @click=${actions.dismissSecret}>تم، إخفاء</button>
          </div>`
        : nothing
    }

    ${tabs(store.id, state.tab, pending)}
    ${state.error ? html`<p class="form-error" role="alert">${state.error}</p>` : nothing}
    ${
      detail
        ? body(detail, state, write, model.working, actions, tabActions)
        : state.error
          ? nothing
          : skeleton()
    }
  `;
}
