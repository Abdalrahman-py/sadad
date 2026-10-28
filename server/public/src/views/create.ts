import { html } from "lit-html";
import { type Actions, type CreateState, canWrite, type Model } from "../model";
import { copyButton, onSubmit, permissionBoxes } from "./common";

const USERNAME = /^[A-Za-z0-9._-]{3,64}$/;

const seg = (
  mode: CreateState["mode"],
  value: CreateState["mode"],
  label: string,
  actions: Actions,
) => html`
  <button type="button" aria-pressed=${mode === value} @click=${() => actions.setMode(value)}>${label}</button>
`;

const doneStep = (done: NonNullable<CreateState["done"]>, actions: Actions) => html`
  <div class="stack">
    <div class="secret" role="status">
      <div class="grow">
        <b class="row-title">بيانات الدخول لـ ${done.username}. تظهر كلمة المرور الآن فقط.</b>
        <p class="small muted">يُطلب تغييرها عند أول دخول.</p>
      </div>
      <code dir="ltr">${done.username}</code>
      <code dir="ltr">${done.password}</code>
      ${copyButton(`${done.username}\n${done.password}`, "نسخ الاثنين")}
    </div>
    <div class="stack">
      <b class="row-title">ما يحدث بعد ذلك</b>
      <ol class="step-list">
        <li>أرسل البيانات لصاحب المتجر بطريقة آمنة.</li>
        <li>يدخل من التطبيق ويغيّر كلمة المرور المؤقتة.</li>
        <li>يظهر طلب هاتفه في «المهام» فتعتمده، ويبدأ العمل.</li>
      </ol>
    </div>
    <div class="btn-group">
      <a class="btn btn-primary btn-lg" href=${`#/stores/${done.storeId}`}>فتح صفحة المتجر</a>
      <button class="btn btn-soft btn-lg" type="button" @click=${actions.restartCreate}>إنشاء متجر آخر</button>
    </div>
  </div>
`;

export function createView(model: Model, actions: Actions) {
  if (!canWrite(model)) {
    return html`<main class="page">
      <a class="back-link" href="#/stores">‹ المتاجر</a>
      <section class="card stack">
        <h1 class="section-title">إنشاء حساب متجر</h1>
        <p class="muted">هذا القسم للإدارة فقط؛ حسابك للدعم يقرأ ويعرض بلا تعديل.</p>
      </section>
    </main>`;
  }
  const state = model.create;
  const validUser = USERNAME.test(state.username);
  const ready = state.name.trim() !== "" && validUser && !state.busy;
  const done = state.done;
  return html`
    <main class="page">
      <a class="back-link" href="#/stores">‹ المتاجر</a>
      <section class="card stack center-x">
        <div class="stack">
          <span class="eyebrow">${done ? "تم الإنشاء" : "حساب جديد"}</span>
          <h1 class="auth-title">
            ${done ? "سلّم البيانات لصاحب المتجر" : "إنشاء حساب متجر"}
          </h1>
        </div>
        ${
          done
            ? doneStep(done, actions)
            : html`
              <form class="stack" @submit=${onSubmit(actions.createStore)}>
                <div class="field-row">
                  <label class="field">اسم المتجر
                    <input name="name" required maxlength="120" .value=${state.name}
                      @input=${(event: InputEvent) => actions.setName((event.currentTarget as HTMLInputElement).value)}>
                  </label>
                  <label class="field">اسم الدخول
                    <input name="username" required dir="ltr" autocomplete="off" .value=${state.username}
                      aria-invalid=${state.username !== "" && !validUser}
                      @input=${(event: InputEvent) => actions.setUsername((event.currentTarget as HTMLInputElement).value)}>
                    <span class="hint ${state.username !== "" && !validUser ? "error" : ""}">
                      ${
                        state.username !== "" && !validUser
                          ? "من 3 إلى 64: أحرف إنجليزية وأرقام و . _ -"
                          : "هذا ما يكتبه صاحب المتجر عند الدخول."
                      }
                    </span>
                  </label>
                </div>

                <div class="stack">
                  <span id="mode-l" class="field">الاشتراك</span>
                  <div class="seg" role="group" aria-labelledby="mode-l">
                    ${seg(state.mode, "timed", "بعدد أيام", actions)}
                    ${seg(state.mode, "permanent", "دائم", actions)}
                    ${seg(state.mode, "paused", "يبدأ موقوفًا", actions)}
                  </div>
                  ${
                    state.mode === "timed"
                      ? html`<label class="field narrow-field">المدة
                          <input name="subscriptionDays" inputmode="numeric" value="30" required>
                          <span class="hint">تُحسب من اليوم.</span>
                        </label>`
                      : html`<input type="hidden" name="subscriptionDays" value="0">`
                  }
                </div>

                <div class="field-row">
                  <label class="field">الأجهزة المسموحة
                    <input name="allowedDevices" inputmode="numeric" value="1" required>
                  </label>
                  <label class="field">حد الأشخاص النشطين
                    <input name="debtorLimit" inputmode="numeric" value="5000" required>
                  </label>
                </div>

                <details class="card details-box">
                  <summary>
                    الصلاحيات <span class="small muted">كلها مفعّلة — اضغط للتعديل</span>
                  </summary>
                  <div>${permissionBoxes({})}</div>
                </details>

                <p class="form-error" role="alert">${state.error}</p>
                <button class="btn btn-primary btn-lg" ?disabled=${!ready}>إنشاء الحساب</button>
                <p class="small muted">
                  ينشئ الخادم كلمة مرور مؤقتة تظهر مرة واحدة بعد الإنشاء. احتفظ بالصفحة مفتوحة حتى
                  تنسخها؛ لا يمكن عرضها مرة أخرى.
                </p>
              </form>`
        }
      </section>
    </main>
  `;
}
