import { html } from "lit-html";
import logo from "../assets/logo.jpeg";
import type { Actions, Model } from "../model";
import { onSubmit } from "./common";

export const signInView = (model: Model, actions: Actions) => html`
  <section class="auth-wrap">
    <div class="auth-card">
      <img src=${logo} alt="سدد" class="brand">
      <p class="eyebrow">لوحة التحكم المركزية</p>
      <h1>دخول الإدارة</h1>
      <p class="muted">لحسابات الإدارة والدعم فقط.</p>
      <form class="stack" @submit=${onSubmit((form) =>
        actions.signIn(String(form.get("email") ?? "").trim(), String(form.get("password") ?? "")),
      )}>
        <label>البريد الإلكتروني<input name="email" type="email" autocomplete="username" required></label>
        <label>كلمة المرور<input name="password" type="password" autocomplete="current-password" required></label>
        <button class="button primary" ?disabled=${model.signIn.busy}>دخول آمن</button>
        <p class="form-error" role="alert">${model.signIn.error}</p>
      </form>
      <p class="small muted">لا يوجد تسجيل عام. ينشأ حساب المتجر من الإدارة فقط.</p>
    </div>
  </section>
`;
