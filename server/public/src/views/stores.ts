import { html, nothing } from "lit-html";
import type { StoreRow } from "../api";
import { fmt, money } from "../format";
import { canWrite, type Model, STORE_FILTERS, type StoresActions } from "../model";
import {
  arabicCount,
  matchesFilter,
  statusText,
  statusTone,
  subscriptionState,
} from "../subscription";
import { empty, loadState, searchBox, state } from "./common";

const row = (store: StoreRow) => {
  const sub = subscriptionState(store);
  const summary = store.summary;
  return html`
    <tr>
      <td class="num">${fmt(store.id)}</td>
      <td>
        <a href=${`#/stores/${store.id}`}><b>${store.name}</b></a>
        <span class="sub"><span dir="ltr">${store.username}</span>${store.verified ? " · موثّق" : ""}</span>
        ${store.pendingDevices ? html`<span class="sub">${state("طلب ينتظر", "warn")}</span>` : nothing}
      </td>
      <td>${state(statusText(store), statusTone(store))}</td>
      <td>${state(sub.label, sub.tone)}</td>
      <td class="num opt">${fmt(store.activeDevices)} / ${fmt(store.allowedDevices)}</td>
      <td class="num opt">${summary.initialized ? fmt(summary.activeContacts ?? summary.contacts) : "—"}</td>
      <td class="num opt">
        ${summary.initialized ? html`${fmt(summary.openDebts)} · ${money(summary.debtTotalCents)}` : "—"}
      </td>
    </tr>
  `;
};

export function storesView(model: Model, actions: StoresActions) {
  const page = model.stores;
  const list = page.search ?? page;
  const counts = (filter: (typeof STORE_FILTERS)[number][0]) =>
    list.rows.filter((store) => matchesFilter(store, filter)).length;
  const rows = list.rows.filter((store) => matchesFilter(store, page.filter));
  return html`
    <main class="page">
      <div class="page-head">
        <div>
          <h1>المتاجر</h1>
          <p class="lead">
            ${arabicCount(
              list.rows.filter((store) => !store.archived).length,
              "متجر واحد",
              "متجران",
              "متاجر",
              "متجرًا",
            )} غير مؤرشفة.
          </p>
        </div>
        ${canWrite(model) ? html`<a class="btn" href="#/create">إنشاء حساب متجر</a>` : nothing}
      </div>

      <section class="section">
        <div class="toolbar">
          ${searchBox("بحث باسم المتجر أو اسم الدخول", page.q, actions.searchStores)}
          <div class="seg" role="group" aria-label="تصفية المتاجر">
            ${STORE_FILTERS.map(
              ([id, label]) => html`<button type="button" aria-pressed=${page.filter === id}
                @click=${() => actions.setStoreFilter(id)}>${label} ${fmt(counts(id))}</button>`,
            )}
          </div>
        </div>

        ${loadState(list)}
        ${
          rows.length
            ? html`<div class="table-wrap"><table>
                <thead><tr>
                  <th scope="col">الرقم</th><th scope="col">المتجر</th>
                  <th scope="col">الحالة</th><th scope="col">الاشتراك</th>
                  <th scope="col" class="opt">الأجهزة</th><th scope="col" class="opt">الأشخاص</th>
                  <th scope="col" class="opt">الديون المفتوحة</th>
                </tr></thead>
                <tbody>${rows.map((store) => row(store))}</tbody>
              </table></div>`
            : !list.loading && !list.error
              ? empty(
                  page.q ? "لا توجد متاجر مطابقة للبحث." : "لا توجد متاجر بعد. أنشئ أول حساب متجر.",
                )
              : nothing
        }
        <p class="small muted">الأحدث إنشاءً أولًا. البحث على الخادم.</p>
      </section>
    </main>
  `;
}
