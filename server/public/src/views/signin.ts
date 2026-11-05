import { html } from "lit-html";
import type { Model, SignInActions } from "../model";
import { onSubmit } from "./common";

export function signInView(model: Model, actions: SignInActions) {
  return html`
    <header class="bar"><span class="bar-brand">سدد</span><span class="muted">لوحة الإدارة</span></header>
    <main class="page">
      <form class="stack narrow" @submit=${onSubmit((form) =>
        actions.signIn(String(form.get("email") ?? "").trim(), String(form.get("password") ?? "")),
      )}>
        <div>
          <h1>دخول الإدارة</h1>
          <p class="lead">لحسابات الإدارة والدعم فقط.</p>
        </div>
        <label class="field">البريد الإلكتروني
          <input name="email" type="email" dir="ltr" autocomplete="username" required
            placeholder="name@sadad.app">
        </label>
        <label class="field">كلمة المرور
          <input name="password" type="password" dir="ltr" autocomplete="current-password" required>
        </label>
        <p class="form-error" role="alert">${model.signIn.error}</p>
        <div><button class="btn btn-primary" ?disabled=${model.signIn.busy}>دخول آمن</button></div>
        <p class="small muted">لا يوجد تسجيل عام. حسابات المتاجر تُنشأ من هنا فقط.</p>
      </form>
    </main>
  `;
}
