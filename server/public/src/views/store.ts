import { html, nothing, type TemplateResult } from "lit-html";
import type { DetailInstallation, Store, StoreDetail } from "../api";
import { dateTime, day, daysLeft, fmt, money } from "../format";
import {
  canWrite,
  type Model,
  STORE_TAB_LABELS,
  STORE_TABS,
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
  state as stateWord,
} from "./common";
import { ledgerView } from "./ledger";
import { recoveryView } from "./recovery";
import { settingsView } from "./settings";

const tabLink = (storeId: number, tab: StoreTab, current: StoreTab, count: number) => html`
  <a class="tab" href=${`#/stores/${storeId}/${tab}`} aria-current=${current === tab ? "page" : "false"}>
    ${STORE_TAB_LABELS[tab]}${count ? html`<span class="count" aria-label=${`${fmt(count)} يحتاج إجراء`}>${fmt(count)}</span>` : nothing}
  </a>`;

const tabs = (storeId: number, current: StoreTab, pending: number) => html`
  <nav class="tabs" aria-label="أقسام المتجر">
    ${STORE_TABS.map((tab) => tabLink(storeId, tab, current, tab === "devices" ? pending : 0))}
  </nav>
`;

const overviewTab = (detail: StoreDetail, state: StoreState, write: boolean) => {
  const store = detail.store;
  const sub = subscriptionState(store);
  const summary = state.summary;
  const pending = detail.installations.filter((device) => device.status === "pending");
  const active = detail.installations.filter((device) => device.status === "active").length;
  return html`
    <section class="section">
      <div class="facts">
        ${metric(
          "الأشخاص النشطون",
          summary?.initialized ? fmt(summary.activeContacts ?? summary.contacts) : "—",
          `من حد ${fmt(store.debtorLimit)}`,
        )}
        ${metric(
          "ديون مفتوحة",
          summary?.initialized ? fmt(summary.openDebts) : "—",
          summary?.initialized ? money(summary.debtTotalCents) : undefined,
        )}
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

      ${
        pending.length
          ? html`<div class="list">
              <div class="list-row">
                <div class="grow">
                  <b class="mark">${pending.length === 1 ? "جهاز ينتظر الاعتماد" : `${fmt(pending.length)} أجهزة تنتظر الاعتماد`}</b>
                  <p class="small muted">
                    ${pending
                      .slice(0, 2)
                      .map(
                        (device) =>
                          html`<bdi>${[device.staffName, device.label].filter(Boolean).join(" · ") || "جهاز"}</bdi> · `,
                      )}
                    <bdi>${dateTime(pending[0]?.createdAt)}</bdi>
                  </p>
                </div>
                ${
                  write
                    ? html`<a class="btn btn-primary" href=${`#/stores/${store.id}/devices`}>مراجعة واعتماد</a>`
                    : html`<a class="btn" href=${`#/stores/${store.id}/devices`}>عرض الأجهزة</a>`
                }
              </div>
            </div>`
          : nothing
      }
      ${
        !summary
          ? html`<p class="small muted">الأرقام من قائمة المتاجر؛ تُعرض بعد تحميلها.</p>`
          : nothing
      }
    </section>
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
        <span class="sub"><code dir="ltr">${phone.installationId}</code></span>
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

const devicesTab = (detail: StoreDetail, write: boolean, actions: StoreActions) => {
  const store = detail.store;
  const active = detail.installations.filter((device) => device.status === "active").length;
  return html`
    <section class="section">
      <div class="spread">
        <p>
          مسموح <b>${arabicCount(store.allowedDevices, "بجهاز واحد", "بجهازين", "أجهزة", "جهازًا")}</b>
          (المعتمد الآن ${fmt(active)}). الاعتماد لا يلغي جهازًا آخر تلقائيًا.
        </p>
        ${
          write
            ? html`<a class="btn" href=${`#/stores/${store.id}/settings`}>تغيير الحد</a>`
            : nothing
        }
      </div>
      ${
        detail.installations.length
          ? html`<div class="list">${detail.installations.map((phone) => deviceRow(phone, store, write, actions))}</div>`
          : html`<p class="empty">لا توجد أجهزة. يسجل صاحب المتجر الدخول ويغيّر كلمة المرور المؤقتة ثم يظهر طلب جهازه هنا.</p>`
      }
    </section>
  `;
};

const subscriptionTab = (detail: StoreDetail, write: boolean, actions: StoreActions) => {
  const store = detail.store;
  const sub = subscriptionState(store);
  const mode = store.subscriptionMode;
  const seg = ([id, label]: [Store["subscriptionMode"], string]) => html`
    <button type="button" aria-pressed=${mode === id} ?disabled=${!write || store.archived}
      @click=${() => id !== "timed" && actions.saveSubscription(id)}>${label}</button>`;
  return html`
    <section class="section narrow">
      <div class="stack">
        <p class="lead">${stateWord(sub.detail, sub.tone)}</p>
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
                    (days) => html`<button class="btn" type="button" ?disabled=${store.archived}
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
  actions: StoreActions,
  tabActions: StoreTabActions,
): TemplateResult {
  switch (state.tab) {
    case "overview":
      return overviewTab(detail, state, write);
    case "devices":
      return devicesTab(detail, write, actions);
    case "subscription":
      return subscriptionTab(detail, write, actions);
    case "settings":
      return settingsView(detail, write, actions);
    case "ledger":
      return ledgerView(detail, state.ledger, tabActions);
    case "recovery":
      return recoveryView(detail, state.recovery, write, tabActions);
    case "audit":
      return html`<section class="section">
        ${sourceFilter(state.audit.source, tabActions.setStoreAuditSource)}
        ${auditList(state.audit, false, "صفحات سجل المتجر", tabActions.pageStoreAudit)}
      </section>`;
  }
}

export function storeView(model: Model, actions: StoreActions, tabActions: StoreTabActions) {
  const state = model.store;
  if (!state) {
    return html`<main class="page"><p class="loading" role="status">جارٍ تحميل المتجر…</p></main>`;
  }
  const detail = state.detail;
  if (!detail) {
    return html`
      <main class="page">
        <a href="#/stores">المتاجر</a>
        ${loadState(state)}
      </main>`;
  }
  const store = detail.store;
  const write = canWrite(model);
  const sub = subscriptionState(store);
  const pending = detail.installations.filter((device) => device.status === "pending").length;
  const secret = model.secret?.storeId === store.id ? model.secret : null;
  return html`
    <main class="page">
      ${
        write
          ? nothing
          : html`<div class="note" role="note">
              حسابك للدعم: تعرض كل شيء، والتعديل للإدارة فقط. قراءة الدفتر تُسجَّل باسمك مع السبب.
            </div>`
      }
      <header class="page-head">
        <div>
          <a href="#/stores">المتاجر</a>
          <h1>${store.name}</h1>
          <div class="meta">
            <span>رقم ${fmt(store.id)}</span>
            <span dir="ltr">${store.username}</span>
            <span>أُنشئ ${dateTime(store.createdAt)}</span>
            ${stateWord(statusText(store), statusTone(store))}
            ${stateWord(sub.label, sub.tone)}
          </div>
        </div>
        ${
          write
            ? html`<div class="btn-group">
                <button class="btn btn-primary" type="button" ?disabled=${store.archived}
                  @click=${() => actions.quickExtend(store, 30)}>تمديد 30 يومًا</button>
                <button class="btn" type="button" ?disabled=${store.archived}
                  @click=${actions.resetPassword}>كلمة مرور مؤقتة</button>
              </div>`
            : nothing
        }
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
      ${loadState(state)}
      ${state.error ? nothing : body(detail, state, write, actions, tabActions)}
    </main>
  `;
}
