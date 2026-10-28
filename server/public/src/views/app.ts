import { html, nothing } from "lit-html";
import logo from "../assets/logo.jpeg";
import type { Actions, Model } from "../model";
import { needsDecision } from "../subscription";
import { auditView } from "./audit";
import { createView } from "./create";
import { dialogView } from "./dialog";
import { signInView } from "./signin";
import { storeView } from "./store";
import { storesView } from "./stores";
import { tasksView } from "./tasks";

/** Pending devices plus subscriptions ending within a week: what the queue would show. */
export function taskCount(model: Model): number {
  if (!model.pending.complete || !model.stores.complete) return 0;
  return model.pending.rows.length + model.stores.rows.filter(needsDecision).length;
}

const link = (href: string, label: string, active: boolean, count = 0) => html`
  <a class="navlink" href=${href} aria-current=${active ? "page" : "false"}>
    ${label}${count ? html`<span class="count" aria-label=${`${count} مهمة`}>${count}</span>` : nothing}
  </a>`;

export function app(model: Model, actions: Actions) {
  if (model.booting) {
    return html`<div class="center-page"><p class="loading" role="status">جارٍ التحميل…</p></div>`;
  }
  if (!model.caller) return signInView(model, actions);
  const caller = model.caller;
  const support = caller.role === "support";
  const tasks = taskCount(model);
  const screen =
    model.screen === "store"
      ? storeView(model, actions)
      : model.screen === "create"
        ? createView(model, actions)
        : model.screen === "audit"
          ? auditView(model, actions)
          : model.screen === "stores"
            ? storesView(model, actions)
            : tasksView(model, actions);
  return html`
    <header class="bar">
      <a class="bar-brand" href="#/tasks">
        <img src=${logo} alt="">
        <div><strong>سدد</strong><span>لوحة الإدارة</span></div>
      </a>
      <nav class="bar-nav" aria-label="أقسام اللوحة">
        ${link("#/tasks", "المهام", model.screen === "tasks", tasks)}
        ${link("#/stores", "المتاجر", model.screen === "stores" || model.screen === "store" || model.screen === "create")}
        ${link("#/audit", "سجل الإدارة", model.screen === "audit")}
      </nav>
      <div class="bar-user">
        <button class="btn btn-ghost btn-sm" type="button" @click=${actions.refresh}>تحديث</button>
        <span class="pill ${support ? "pill-warn" : "pill-ok"}">${support ? "دعم · قراءة فقط" : "إدارة"}</span>
        <span class="email" dir="ltr">${caller.email}</span>
        <button class="signout" type="button" @click=${actions.signOut}>خروج</button>
      </div>
    </header>
    ${
      model.notice
        ? html`<div class="toast ${model.notice.error ? "error" : ""}" role="status">
            <span>${model.notice.text}</span>
            <button type="button" aria-label="إخفاء" @click=${actions.dismissNotice}>إخفاء</button>
          </div>`
        : nothing
    }
    ${screen}
    ${model.dialog ? dialogView(model.dialog, actions) : nothing}
    <footer class="footer">سدد · لوحة الإدارة</footer>
  `;
}
