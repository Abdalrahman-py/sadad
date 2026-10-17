import { html, nothing } from "lit-html";
import type { Actions, Dialog } from "../model";
import { onSubmit, permissionBoxes } from "./common";

const close = (
  actions: Actions,
) => html`<button type="button" class="icon-button" aria-label="إغلاق"
  @click=${actions.closeDialog}>×</button>`;

const footer = (dialog: Dialog, label: string, danger = false) => html`
  <p class="form-error" role="alert">${dialog.error}</p>
  <button class="button ${danger ? "danger" : "primary"}" ?disabled=${dialog.busy}>${label}</button>
`;

function body(dialog: Dialog, actions: Actions) {
  switch (dialog.kind) {
    case "reason":
      return html`
        <form class="stack" @submit=${onSubmit((form) => actions.submitDialog(String(form.get("reason") ?? "")))}>
          <div class="panel-head"><h2>${dialog.title}</h2>${close(actions)}</div>
          <p class="muted">${dialog.description}</p>
          <label>سبب الإجراء<textarea name="reason" rows="3" maxlength="2000" required></textarea></label>
          ${footer(dialog, dialog.confirm, dialog.danger)}
        </form>`;
    case "confirm":
      return html`
        <form class="stack" @submit=${onSubmit(() => actions.submitDialog(""))}>
          <div class="panel-head"><h2>${dialog.title}</h2>${close(actions)}</div>
          <p class="muted">${dialog.description}</p>
          ${footer(dialog, dialog.confirm, true)}
        </form>`;
    case "delete":
      return html`
        <form class="stack" @submit=${onSubmit((form) => actions.submitDialog(String(form.get("confirm") ?? "")))}>
          <div class="panel-head"><h2>حذف حساب غير مستخدم</h2>${close(actions)}</div>
          <p class="muted">يُحذف المتجر وحساب دخوله نهائيًا. اكتب اسم المستخدم <b dir="ltr">${dialog.username}</b> للتأكيد.</p>
          <label>اسم المستخدم<input name="confirm" autocomplete="off" required dir="ltr"></label>
          ${footer(dialog, "حذف نهائي", true)}
        </form>`;
    case "create":
      return html`
        <form class="stack" @submit=${onSubmit((form) => actions.createStore(form))}>
          <div class="panel-head"><div><p class="eyebrow">حساب جديد</p><h2>إنشاء متجر</h2></div>${close(actions)}</div>
          <label>اسم المتجر<input name="name" required maxlength="120"></label>
          <label>اسم المستخدم<input name="username" required pattern="[A-Za-z0-9._\\-]{3,64}" dir="ltr"
            autocomplete="off" title="3 إلى 64 حرفًا: أحرف إنجليزية وأرقام و . _ -"></label>
          <label>نوع الاشتراك<select name="subscriptionMode">
            <option value="timed">مدة محددة</option>
            <option value="permanent">فعال دائمًا</option>
            <option value="paused">إنشاء موقوف</option>
          </select></label>
          <label>مدة الاشتراك بالأيام<input name="subscriptionDays" inputmode="numeric" value="30"></label>
          <label>عدد الأجهزة المسموحة<input name="allowedDevices" inputmode="numeric" value="1"></label>
          <label>حد جهات الاتصال النشطة<input name="debtorLimit" inputmode="numeric" value="5000"></label>
          <label class="inline-check"><input type="checkbox" name="verified">حساب موثّق</label>
          ${permissionBoxes({})}
          ${footer(dialog, "إنشاء الحساب")}
          <p class="muted small">ينشئ الخادم كلمة مرور مؤقتة تظهر مرة واحدة بعد الإنشاء. انسخها وأرسلها لصاحب المتجر بطريقة آمنة.</p>
        </form>`;
    default:
      return nothing;
  }
}

/** main.ts opens it with showModal() after render; Escape and × both close it. */
export const dialogView = (dialog: Dialog, actions: Actions) => html`
  <dialog class="dialog" @close=${actions.closeDialog}>${body(dialog, actions)}</dialog>
`;
