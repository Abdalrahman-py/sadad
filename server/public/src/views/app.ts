import { html, nothing } from "lit-html";
import type { Actions, Model } from "../model";
import { auditView } from "./audit";
import { skeleton } from "./common";
import { createView } from "./create";
import { dialogView } from "./dialog";
import { signInView } from "./signin";
import { storeView } from "./store";
import { storesView } from "./stores";
import { taskCount, tasksView } from "./tasks";

const link = (href: string, label: string, active: boolean, count = 0) => html`
  <a class="navlink" href=${href} aria-current=${active ? "page" : "false"}>
    ${label}${count ? html`<span class="count" aria-label=${`${count} مهمة`}>${count}</span>` : nothing}
  </a>`;

export function app(model: Model, actions: Actions) {
  if (model.booting) {
    return html`<div class="progress"></div><main class="page">${skeleton()}</main>`;
  }
  if (!model.caller) return signInView(model, actions);
  const caller = model.caller;
  const support = caller.role === "support";
  const tasks = taskCount(model);
  const open = model.screen === "store";
  const screen =
    open || model.screen === "stores"
      ? html`<main class=${`split ${open ? "open" : ""}`}>
          <section class="pane list-pane" aria-label="المتاجر">${storesView(model, actions)}</section>
          ${open ? html`<section class="pane detail">${storeView(model, actions, actions)}</section>` : nothing}
        </main>`
      : model.screen === "create"
        ? createView(model, actions)
        : model.screen === "audit"
          ? auditView(model, actions)
          : tasksView(model, actions);
  const refreshing =
    model.stores.loading ||
    model.stores.search?.loading ||
    model.audit.loading ||
    model.store?.loading ||
    model.store?.audit.loading ||
    model.store?.ledger?.loading;
  return html`
    ${refreshing ? html`<div class="progress"></div>` : nothing}
    <header class="bar">
      <a class="bar-brand" href="#/tasks" aria-label="سدد، لوحة الإدارة">سدد</a>
      <nav class="bar-nav" aria-label="أقسام اللوحة">
        ${link("#/tasks", "المهام", model.screen === "tasks", tasks)}
        ${link("#/stores", "المتاجر", model.screen === "stores" || model.screen === "store" || model.screen === "create")}
        ${link("#/audit", "سجل الإدارة", model.screen === "audit")}
      </nav>
      <div class="bar-user">
        <span>${support ? "دعم · قراءة فقط" : "إدارة"}</span>
        <span class="email" dir="ltr">${caller.email}</span>
        <button class="text-button" type="button" @click=${actions.refresh}>تحديث</button>
        <button class="text-button" type="button" @click=${actions.signOut}>خروج</button>
      </div>
    </header>
    ${
      model.notice
        ? html`<div class="toast ${model.notice.error ? "error" : ""}" role="status">
            <span>${model.notice.text}</span>
            <button class="text-button" type="button" aria-label="إخفاء" @click=${actions.dismissNotice}>إخفاء</button>
          </div>`
        : nothing
    }
    ${screen}
    ${model.dialog ? dialogView(model.dialog, actions) : nothing}
  `;
}
