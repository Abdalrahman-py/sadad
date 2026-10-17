import { html, nothing } from "lit-html";
import logo from "../assets/logo.jpeg";
import { fmt } from "../format";
import { type Actions, canWrite, type Model, type Tab } from "../model";
import { auditView } from "./audit";
import { metric } from "./common";
import { dialogView } from "./dialog";
import { installationsView } from "./installations";
import { signInView } from "./signin";
import { storeView } from "./store";
import { storesView } from "./stores";

const TABS: [Tab, string][] = [
  ["stores", "المتاجر"],
  ["installations", "طلبات الأجهزة"],
  ["audit", "سجل الإدارة"],
];

export function app(model: Model, actions: Actions) {
  if (model.booting) return html`<p class="muted" role="status">جارٍ التحميل…</p>`;
  if (!model.caller) return signInView(model, actions);
  const caller = model.caller;
  const overview = model.overview;
  return html`
    <header class="topbar">
      <div class="brand-line"><img src=${logo} alt="" class="brand-small">
        <div><strong>سدد</strong><span>إدارة المتاجر والصلاحيات</span></div></div>
      <div class="top-actions">
        <button class="button quiet" type="button" @click=${actions.refresh}>تحديث</button>
        <span class="pill" title=${caller.role === "admin" ? "إدارة" : "دعم (قراءة فقط)"}>${caller.email}</span>
        <span class="pill">${caller.role === "admin" ? "إدارة" : "دعم · قراءة فقط"}</span>
        <button class="button quiet" type="button" @click=${actions.signOut}>خروج</button>
      </div>
    </header>
    ${
      model.notice
        ? html`<div class="notice ${model.notice.error ? "error" : ""}" role="status">
            <span>${model.notice.text}</span>
            <button type="button" class="icon-button" aria-label="إخفاء" @click=${actions.dismissNotice}>×</button>
          </div>`
        : nothing
    }
    <section class="hero">
      <div><p class="eyebrow">إدارة المنصة</p><h1>لوحة سدد</h1>
        <p class="muted">أنشئ حسابات المتاجر، واعتمد أجهزتها، وتابع نشاط المنصة من مكان واحد.</p></div>
      ${
        canWrite(model)
          ? html`<button class="button primary" type="button" @click=${actions.openCreateStore}>إنشاء حساب متجر</button>`
          : nothing
      }
    </section>
    <section class="metrics" aria-live="polite">
      ${
        overview
          ? [
              metric("المتاجر", fmt(overview.stores)),
              metric("النشطة", fmt(overview.active)),
              metric("الموثقة", fmt(overview.verified)),
              metric("أجهزة بانتظار الاعتماد", fmt(overview.pendingDevices)),
            ]
          : nothing
      }
    </section>
    <nav class="tabs" aria-label="أقسام اللوحة">
      ${TABS.map(
        ([tab, label]) => html`<button type="button" class="tab" aria-pressed=${model.tab === tab}
          @click=${() => actions.setTab(tab)}>${label}</button>`,
      )}
    </nav>
    ${
      model.tab === "stores"
        ? storesView(model, actions)
        : model.tab === "installations"
          ? installationsView(model, actions)
          : auditView(model, actions)
    }
    ${model.store ? storeView(model, model.store, actions) : nothing}
    ${model.dialog ? dialogView(model.dialog, actions) : nothing}
    <footer>سدد · لوحة إدارة المتاجر</footer>
  `;
}
