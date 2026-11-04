import { html, nothing } from "lit-html";
import type { StoreDetail } from "../api";
import { fmt } from "../format";
import type { StoreActions } from "../model";
import { statusText, statusTone } from "../subscription";
import { onSubmit, permissionBoxes, pill } from "./common";

interface DangerAction {
  label: string;
  why: string;
  button: string;
  strong: boolean;
  run: (actions: StoreActions) => void;
}

/**
 * The four account actions, archiving swapped for restoring when the store is archived. An archived
 * account takes only «إعادة»: the api refuses every other action while it is archived.
 */
function dangerActions(detail: StoreDetail): DangerAction[] {
  const store = detail.store;
  if (store.archived) {
    return [
      {
        label: "إعادة الحساب من الأرشيف",
        why: "يعود الحساب مجمّدًا مع دفتره؛ فعّله واعتمد جهازًا جديدًا بعد ذلك.",
        button: "إعادة",
        strong: false,
        run: (actions) => actions.storeAction("unarchive"),
      },
    ];
  }
  const archive: DangerAction = {
    label: "أرشفة الحساب",
    why: "يوقف الحساب وأجهزته ويحفظ دفتره وسجله. يمكن إعادته.",
    button: "أرشفة",
    strong: true,
    run: (actions) => actions.storeAction("archive"),
  };
  const rows: DangerAction[] = [
    store.status === "active"
      ? {
          label: "تجميد الحساب",
          why: "يوقف الحساب وينهي جلساته فورًا. الدفتر يبقى.",
          button: "تجميد",
          strong: true,
          run: (actions) => actions.setStatus("suspended"),
        }
      : {
          label: "تفعيل الحساب",
          why: "يعيد الحساب للعمل. اعتماد الأجهزة يبقى كما هو.",
          button: "تفعيل",
          strong: false,
          run: (actions) => actions.setStatus("active"),
        },
    {
      label: "إنهاء جلسات الدخول",
      why: "يُطلب من كل الأجهزة تسجيل الدخول من جديد. اعتماد الأجهزة لا يتغير.",
      button: "إنهاء الجلسات",
      strong: false,
      run: (actions) => actions.storeAction("end-sessions"),
    },
  ];
  if (!store.archived) {
    rows.push({
      label: "إلغاء اعتماد كل الأجهزة",
      why: "تحتاج كل الأجهزة تسجيلًا واعتمادًا جديدًا قبل المزامنة.",
      button: "إلغاء الكل",
      strong: true,
      run: (actions) => actions.storeAction("revoke-all"),
    });
  }
  rows.push(archive);
  return rows;
}

export function settingsView(detail: StoreDetail, write: boolean, actions: StoreActions) {
  const store = detail.store;
  const locked = !write || store.archived;
  return html`
    <section class="columns">
      <form class="card main stack" data-settings @submit=${onSubmit(actions.saveSettings)}>
        <h2 class="section-title">بيانات الحساب وصلاحياته</h2>
        <fieldset class="stack" ?disabled=${locked}>
          <div class="field-row">
            <label class="field">اسم المتجر
              <input name="name" required maxlength="120" .value=${store.name}>
            </label>
            <label class="field">اسم الدخول
              <input name="username" required dir="ltr" pattern="[A-Za-z0-9._\\-]{3,64}" .value=${store.username}>
            </label>
            <label class="field">حد الأشخاص النشطين
              <input name="debtorLimit" inputmode="numeric" .value=${String(store.debtorLimit)}>
            </label>
            <label class="field">الأجهزة المسموحة
              <input name="allowedDevices" inputmode="numeric" .value=${String(store.allowedDevices)}>
              <span class="hint">لا يقل عن الأجهزة المعتمدة حاليًا (${fmt(store.activeDevices)}).</span>
            </label>
          </div>
          ${permissionBoxes(store.permissions)}
          <label class="check">
            <input type="checkbox" name="verified" .checked=${store.verified}>
            حساب موثّق <span class="small muted">(شارة تظهر لهذا المتجر)</span>
          </label>
          ${
            store.archived
              ? html`<p class="small muted">الحساب مؤرشف: أعد الحساب من الأرشيف لتعديل الإعدادات.</p>`
              : write
                ? html`<button class="btn btn-primary start">حفظ التغييرات</button>`
                : html`<p class="small muted">التعديل للإدارة فقط.</p>`
          }
        </fieldset>
      </form>

      ${
        write
          ? html`<section class="danger-card aside" aria-labelledby="danger-h">
              <h2 id="danger-h">إجراءات حساسة</h2>
              <p class="small muted push-sm">
                كل إجراء هنا يُسجَّل باسمك وسببه، ويحتاج كتابة اسم الدخول للتأكيد.
              </p>
              ${dangerActions(detail).map(
                (action) => html`<div class="danger-row">
                  <div class="grow">
                    <b>${action.label}</b>
                    <p class="small muted">${action.why}</p>
                  </div>
                  <button type="button" class=${`btn btn-sm ${action.strong ? "btn-danger" : "btn-outline"}`}
                    @click=${() => action.run(actions)}>${action.button}</button>
                </div>`,
              )}
            </section>`
          : html`<section class="card aside stack">
              <h2 class="section-title">حالة الحساب</h2>
              <div class="row">
                ${pill(statusText(store), statusTone(store))}
                ${pill(store.verified ? "موثّق" : "غير موثّق", store.verified ? "ok" : "muted")}
              </div>
              <p class="small muted">
                الإجراءات الحساسة (التجميد والأرشفة وإلغاء الأجهزة) للإدارة فقط.
              </p>
              ${store.archived ? html`<p class="small muted">الحساب مؤرشف.</p>` : nothing}
            </section>`
      }
    </section>
  `;
}
