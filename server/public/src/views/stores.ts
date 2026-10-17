import { html, nothing } from "lit-html";
import type { StoreRow } from "../api";
import { duration, fmt, money } from "../format";
import type { Actions, Model } from "../model";
import { loadState, pager, searchBox } from "./common";

function subscription(store: StoreRow): string {
  if (store.subscriptionMode === "permanent") return "فعال دائمًا";
  if (store.subscriptionMode === "paused") return "موقوف";
  return (store.subscriptionRemainingMs ?? 0) > 0
    ? `متبقي ${duration(store.subscriptionRemainingMs)}`
    : "منتهٍ";
}

const statusText = (store: StoreRow) =>
  store.archived ? "مؤرشف" : store.status === "active" ? "نشط" : "مجمّد";

const row = (store: StoreRow, actions: Actions) => html`
  <tr>
    <td><b>${store.name}</b>${store.verified ? html`<span class="verified" title="حساب موثّق"> ✓</span>` : nothing}</td>
    <td dir="ltr">${store.username}</td>
    <td><span class="status ${store.status === "active" && !store.archived ? "" : "suspended"}">${statusText(store)}</span></td>
    <td>${subscription(store)}</td>
    <td>${fmt(store.activeDevices)} / ${fmt(store.allowedDevices)}${
      store.pendingDevices
        ? html`<small class="pending-device">${fmt(store.pendingDevices)} طلب انتظار</small>`
        : nothing
    }</td>
    <td>${store.summary.initialized ? fmt(store.summary.contacts) : "—"}</td>
    <td>${store.summary.initialized ? html`${fmt(store.summary.openDebts)} · ${money(store.summary.debtTotalCents)}` : "—"}</td>
    <td><button class="button quiet" type="button" @click=${() => actions.openStore(store.id)}>إدارة</button></td>
  </tr>
`;

export const storesView = (model: Model, actions: Actions) => {
  const page = model.stores;
  return html`
    <section class="panel">
      <div class="panel-head">
        <div><h2>حسابات المتاجر</h2><p class="muted">الأحدث أولًا. البحث يتم على الخادم.</p></div>
        <div class="store-filters">
          <label class="inline-check"><input type="checkbox" .checked=${page.includeArchived}
            @change=${(e: Event) => actions.setIncludeArchived((e.currentTarget as HTMLInputElement).checked)}>إظهار المؤرشفة</label>
          ${searchBox("بحث بالمتجر أو اسم المستخدم", page.q, actions.searchStores)}
        </div>
      </div>
      ${loadState(page)}
      <div class="table-wrap"><table>
        <thead><tr><th>المتجر</th><th>اسم الدخول</th><th>الحالة</th><th>الاشتراك</th><th>الأجهزة</th><th>الأشخاص</th><th>الديون المفتوحة</th><th></th></tr></thead>
        <tbody>${page.rows.map((store) => row(store, actions))}</tbody>
      </table></div>
      ${
        !page.rows.length && !page.loading && !page.error
          ? html`<div class="empty">لا توجد حسابات متاجر${page.q ? " مطابقة" : ""}.</div>`
          : nothing
      }
      ${pager("صفحات المتاجر", page, actions.pageStores)}
    </section>
  `;
};
