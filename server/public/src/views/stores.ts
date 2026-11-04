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
import { empty, loadState, pill, searchBox } from "./common";

const verifiedTick = () => html`
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#064B42" stroke-width="2.4"
    stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="موثّق">
    <path d="M5 12.5l4.5 4.5L19 7"/>
  </svg>`;

const row = (store: StoreRow) => {
  const sub = subscriptionState(store);
  const summary = store.summary;
  return html`
    <tr class="is-link">
      <td>
        <a class="cell-link" href=${`#/stores/${store.id}`}>
          <b>${store.name}${store.verified ? verifiedTick() : nothing}</b>
          <small class="cell-sub" dir="ltr">${store.username}</small>
        </a>
      </td>
      <td>${pill(statusText(store), statusTone(store))}</td>
      <td>
        <span class=${sub.tone === "ok" ? "" : `loud-${sub.tone}`}>${sub.label}</span>
      </td>
      <td>
        <span class="num">${fmt(store.activeDevices)} / ${fmt(store.allowedDevices)}</span>
        ${store.pendingDevices ? html`<span class="pill pill-warn pill-gap">طلب ينتظر</span>` : nothing}
      </td>
      <td class="num">${summary.initialized ? fmt(summary.activeContacts ?? summary.contacts) : "—"}</td>
      <td class="num">
        ${summary.initialized ? html`${fmt(summary.openDebts)} · ${money(summary.debtTotalCents)}` : "—"}
      </td>
    </tr>
  `;
};

export function storesView(model: Model, actions: StoresActions) {
  const page = model.stores;
  const counts = (filter: (typeof STORE_FILTERS)[number][0]) =>
    page.rows.filter((store) => matchesFilter(store, filter)).length;
  const rows = page.rows.filter((store) => matchesFilter(store, page.filter));
  return html`
    <main class="page">
      <div class="page-head">
        <div class="title-group">
          <h1>المتاجر</h1>
          <p class="muted">
            ${arabicCount(
              page.rows.filter((store) => !store.archived).length,
              "متجر واحد",
              "متجران",
              "متاجر",
              "متجرًا",
            )} غير مؤرشفة.
          </p>
        </div>
        ${
          canWrite(model)
            ? html`<a class="btn btn-primary btn-lg" href="#/create">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"
                  stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
                إنشاء حساب متجر</a>`
            : nothing
        }
      </div>

      <div class="row">
        ${searchBox("بحث باسم المتجر أو اسم الدخول", page.q, actions.searchStores)}
        <div class="chips" role="group" aria-label="تصفية المتاجر">
          ${STORE_FILTERS.map(
            ([
              id,
              label,
            ]) => html`<button class="chip" type="button" aria-pressed=${page.filter === id}
              @click=${() => actions.setStoreFilter(id)}>${label} <span>${fmt(counts(id))}</span></button>`,
          )}
        </div>
      </div>

      ${loadState(page)}
      ${
        rows.length
          ? html`<div class="table-wrap"><table>
              <thead><tr>
                <th scope="col">المتجر</th><th scope="col">الحالة</th><th scope="col">الاشتراك</th>
                <th scope="col">الأجهزة</th><th scope="col">الأشخاص</th><th scope="col">الديون المفتوحة</th>
              </tr></thead>
              <tbody>${rows.map((store) => row(store))}</tbody>
            </table></div>`
          : !page.loading && !page.error
            ? empty(
                page.q ? "لا توجد متاجر مطابقة للبحث." : "لا توجد متاجر بعد. أنشئ أول حساب متجر.",
              )
            : nothing
      }
      <p class="small muted">
        الأحدث إنشاءً أولًا · البحث على الخادم · اضغط اسم المتجر لفتح صفحته.
      </p>
    </main>
  `;
}
