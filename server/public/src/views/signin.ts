import { html } from "lit-html";
import logo from "../assets/logo.jpeg";
import type { Actions, Model } from "../model";
import { onSubmit } from "./common";

export function signInView(model: Model, actions: Actions) {
  return html`
    <main class="auth">
      <div class="auth-card">
        <div class="auth-brand">
          <img src=${logo} alt="">
          <div><strong class="brand-name">سدد</strong>
            <span class="small muted brand-sub">لوحة الإدارة</span></div>
        </div>
        <form class="auth-form" @submit=${onSubmit((form) =>
          actions.signIn(
            String(form.get("email") ?? "").trim(),
            String(form.get("password") ?? ""),
          ),
        )}>
          <h1>دخول الإدارة</h1>
          <p class="small muted">لحسابات الإدارة والدعم فقط.</p>
          <label class="field">البريد الإلكتروني
            <input name="email" type="email" dir="ltr" autocomplete="username" required
              placeholder="name@sadad.app">
          </label>
          <label class="field">كلمة المرور
            <input name="password" type="password" dir="ltr" autocomplete="current-password" required>
          </label>
          <button class="btn btn-primary btn-lg" ?disabled=${model.signIn.busy}>دخول آمن</button>
          <p class="form-error" role="alert">${model.signIn.error}</p>
        </form>
        <p class="small muted">لا يوجد تسجيل عام. حسابات المتاجر تُنشأ من هنا فقط.</p>
      </div>
    </main>
  `;
}
