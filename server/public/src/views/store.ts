import { html, nothing } from "lit-html";
import type { DetailInstallation, Store } from "../api";
import { dateTime, duration, fmt } from "../format";
import { type Actions, canWrite, type Model, type StoreState } from "../model";
import { auditList, sourceSwitch } from "./audit";
import { INSTALLATION_STATE, loadState, onSubmit, permissionBoxes } from "./common";
import { ledgerView } from "./ledger";
import { recoveryView } from "./recovery";

function subscriptionState(store: Store): string {
  if (store.subscriptionMode === "permanent") return "فعال دائمًا.";
  if (store.subscriptionMode === "paused") {
    return `الاشتراك موقوف، والمدة المتبقية محفوظة: ${duration(store.subscriptionRemainingMs)}.`;
  }
  return (store.subscriptionRemainingMs ?? 0) > 0
    ? `متبقي ${duration(store.subscriptionRemainingMs)}، وينتهي ${dateTime(store.subscriptionExpiresAt)}.`
    : `انتهى الاشتراك في ${dateTime(store.subscriptionExpiresAt)}. يحتاج تجديدًا.`;
}

const statusState = (store: Store) =>
  store.archived
    ? "الحساب مؤرشف: الدفتر والسجل محفوظان، والأجهزة ملغاة."
    : store.status === "active"
      ? "الحساب فعال حاليًا."
      : "الحساب مجمّد حتى يُعاد تفعيله.";

const settingsForm = (store: Store, write: boolean, actions: Actions) => html`
  <form class="stack panel-inset" data-settings @submit=${onSubmit(actions.saveSettings)}>
    <fieldset class="stack" ?disabled=${!write || store.archived}>
      <h3>بيانات وصلاحيات الحساب</h3>
      <label>اسم المتجر<input name="name" required maxlength="120" .value=${store.name}></label>
      <label>اسم المستخدم<input name="username" required dir="ltr" pattern="[A-Za-z0-9._\\-]{3,64}" .value=${store.username}></label>
      <label>حد جهات الاتصال النشطة<input name="debtorLimit" inputmode="numeric" .value=${String(store.debtorLimit)}></label>
      <label>عدد الأجهزة المسموحة<input name="allowedDevices" inputmode="numeric" .value=${String(store.allowedDevices)}></label>
      <p class="small muted">لا يمكن خفض العدد تحت الأجهزة المعتمدة حاليًا (${fmt(store.activeDevices)})؛ ألغِ اعتماد جهاز أولًا.</p>
      <label class="switch-row"><span><b>توثيق الحساب</b><small>شارة الموثوقية تظهر لهذا المتجر فقط</small></span>
        <input type="checkbox" name="verified" .checked=${store.verified}></label>
      ${permissionBoxes(store.permissions)}
      ${write ? html`<button class="button primary">حفظ إعدادات المتجر</button>` : nothing}
    </fieldset>
  </form>
`;

const controls = (store: Store, canDelete: boolean, actions: Actions) => html`
  <section class="panel-inset stack">
    <h3>حالة الحساب</h3>
    <p class="muted">${statusState(store)}</p>
    <div class="button-row">
      <button class="button success" type="button" ?disabled=${store.archived || store.status === "active"}
        @click=${() => actions.setStatus("active")}>تفعيل الحساب</button>
      <button class="button danger" type="button" ?disabled=${store.archived || store.status !== "active"}
        @click=${() => actions.setStatus("suspended")}>تجميد الحساب</button>
    </div>
    <p class="small muted">التجميد يُنهي جلسات المتجر فورًا.</p>
    <hr>
    <h3>كلمة المرور</h3>
    <p class="muted">يُنهي الجلسات الحالية ويُنشئ الخادم كلمة مؤقتة يجب تغييرها عند الدخول.</p>
    <div class="button-row"><button class="button secondary" type="button" ?disabled=${store.archived}
      @click=${actions.resetPassword}>إنشاء كلمة مؤقتة</button></div>
    <hr>
    <div class="button-row">
      ${
        store.archived
          ? html`<button class="button secondary" type="button" @click=${() => actions.storeAction("unarchive")}>إعادة الحساب من الأرشيف</button>`
          : html`<button class="button danger" type="button" @click=${() => actions.storeAction("archive")}>أرشفة الحساب</button>`
      }
      <button class="button secondary" type="button" ?disabled=${store.archived}
        @click=${() => actions.storeAction("end-sessions")}>إنهاء جلسات الدخول</button>
      <button class="button danger" type="button" ?disabled=${store.archived}
        @click=${() => actions.storeAction("revoke-all")}>إلغاء اعتماد جميع الأجهزة</button>
      ${
        canDelete && !store.archived
          ? html`<button class="button danger" type="button" @click=${actions.openDelete}>حذف حساب غير مستخدم</button>`
          : nothing
      }
    </div>
    <p class="small muted">${
      store.archived
        ? "إعادة الحساب تحفظ الدفتر وتتركه مجمّدًا حتى تفعّله وتعتمد هاتفه."
        : "الأرشفة توقف الحساب وأجهزته وتحفظ دفتره وسجل نشاطه."
    }</p>
  </section>
  <section class="panel-inset stack">
    <h3>الاشتراك والمدة</h3>
    <p class="muted">${subscriptionState(store)}</p>
    <form class="stack" @submit=${onSubmit(actions.saveSubscription)}>
      <fieldset class="stack" ?disabled=${store.archived}>
        <label>حالة الاشتراك<select name="mode" .value=${store.subscriptionMode}>
          <option value="permanent" ?selected=${store.subscriptionMode === "permanent"}>فعال دائمًا</option>
          <option value="timed" ?selected=${store.subscriptionMode === "timed"}>محدد بعدد أيام</option>
          <option value="paused" ?selected=${store.subscriptionMode === "paused"}>إيقاف الاشتراك</option>
        </select></label>
        <label>عدد الأيام (للمدة المحددة)<input name="days" inputmode="numeric" value="30"></label>
        <button class="button secondary">حفظ حالة الاشتراك</button>
      </fieldset>
    </form>
    <hr>
    <form class="stack" @submit=${onSubmit(actions.extendSubscription)}>
      <fieldset class="stack" ?disabled=${store.archived || store.subscriptionMode !== "timed"}>
        <label>أيام إضافية<input name="days" inputmode="numeric" value="30"></label>
        <button class="button success">تمديد الاشتراك</button>
      </fieldset>
    </form>
  </section>
`;

const readOnlyState = (store: Store) => html`
  <section class="panel-inset stack">
    <h3>حالة الحساب</h3>
    <p class="muted">${statusState(store)}</p>
    <h3>الاشتراك</h3>
    <p class="muted">${subscriptionState(store)}</p>
  </section>
`;

const installation = (
  phone: DetailInstallation,
  store: Store,
  write: boolean,
  actions: Actions,
) => html`
  <article class="device-card"><div class="record-row">
    <div>
      <b class="installation-state ${phone.status}">${INSTALLATION_STATE[phone.status]} · الجيل ${fmt(phone.generation)}</b>
      <small>${phone.staffName || phone.label || "جهاز"} · سُجل ${dateTime(phone.createdAt)}${
        phone.approvedBy ? ` · اعتمده ${phone.approvedBy}` : ""
      }</small>
      ${phone.reason ? html`<small>${phone.reason}</small>` : nothing}
      <code dir="ltr">${phone.installationId}</code>
    </div>
    ${
      write
        ? html`<div class="button-row">
            ${
              phone.status === "pending"
                ? html`<button class="button primary" type="button" ?disabled=${store.archived}
                    @click=${() => actions.decideInstallation(phone.installationId, "approve", store.id)}>اعتماد الهاتف</button>`
                : nothing
            }
            ${
              phone.status !== "revoked"
                ? html`<button class="button danger" type="button" ?disabled=${store.archived}
                    @click=${() => actions.decideInstallation(phone.installationId, "revoke", store.id)}>إلغاء الاعتماد</button>`
                : nothing
            }
          </div>`
        : nothing
    }
  </div></article>
`;

export function storeView(model: Model, state: StoreState, actions: Actions) {
  const write = canWrite(model);
  const detail = state.detail;
  if (!detail) {
    return html`<section class="panel" aria-busy=${state.loading}>
      <div class="panel-head"><h2>المتجر</h2>
        <button class="button quiet" type="button" @click=${actions.closeStore}>إغلاق</button></div>
      ${loadState(state)}
    </section>`;
  }
  const store = detail.store;
  const secret = model.secret?.storeId === store.id ? model.secret : null;
  return html`
    <section class="panel" id="store-panel" aria-busy=${state.loading}>
      <div class="panel-head">
        <div><p class="eyebrow">إدارة حساب</p><h2>${store.name}</h2>
          <p class="muted"><span dir="ltr">${store.username}</span> · أُنشئ ${dateTime(store.createdAt)}</p></div>
        <button class="button quiet" type="button" @click=${actions.closeStore}>إغلاق</button>
      </div>
      ${loadState(state)}
      ${
        secret
          ? html`<div class="result stack" role="status">
              <b>كلمة المرور المؤقتة للحساب <span dir="ltr">${secret.username}</span>:</b>
              <code dir="ltr" class="permit-id">${secret.password}</code>
              <span class="small">تظهر مرة واحدة. انسخها الآن وأرسلها لصاحب المتجر بطريقة آمنة؛ يُطلب تغييرها عند أول دخول.</span>
              <div class="button-row"><button class="button quiet" type="button" @click=${actions.dismissSecret}>إخفاء</button></div>
            </div>`
          : nothing
      }
      <div class="admin-grid">
        ${settingsForm(store, write, actions)}
        <div class="stack">${write ? controls(store, detail.canDelete, actions) : readOnlyState(store)}</div>
      </div>
      <section class="panel-inset stack">
        <h3>أجهزة المتجر</h3>
        <p class="muted small">يعتمد كل جهاز ضمن حد الأجهزة المسموحة. الاعتماد لا يلغي جهازًا آخر تلقائيًا.</p>
        <div class="record-list">${
          detail.installations.length
            ? detail.installations.map((phone) => installation(phone, store, write, actions))
            : html`<p class="muted">لا توجد أجهزة. يسجل صاحب المتجر الدخول ويغيّر كلمة المرور المؤقتة ثم يظهر طلب جهازه هنا.</p>`
        }</div>
      </section>
      ${recoveryView(detail, state.recovery, write, actions)}
      ${ledgerView(detail, state.ledger, actions)}
      <section class="panel-inset stack">
        <h3>سجل نشاط المتجر</h3>
        ${sourceSwitch(state.audit.source, actions.setStoreAuditSource)}
        ${auditList(state.audit, false, "صفحات سجل المتجر", actions.pageStoreAudit)}
      </section>
    </section>
  `;
}
