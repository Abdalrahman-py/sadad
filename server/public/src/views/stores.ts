import { html, nothing } from "lit-html";
import type { StoreRow } from "../api";
import { fmt, money } from "../format";
import { canWrite, type Model, STORE_FILTERS, type StoreSort, type StoresActions } from "../model";
import {
  arabicCount,
  matchesFilter,
  sortStores,
  statusText,
  statusTone,
  subscriptionState,
} from "../subscription";
import { empty, loadState, searchBox, state } from "./common";

/** Key, header, and whether the column stays when a store is open beside the list. */
const COLUMNS: [StoreSort, string, string][] = [
  ["id", "الرقم", "num"],
  ["name", "المتجر", ""],
  ["subscription", "الاشتراك", ""],
  ["devices", "الأجهزة", "num opt"],
  ["people", "الأشخاص", "num opt full-only"],
  ["debts", "مستحق للمتجر", "num opt"],
];

const header = (sort: Model["stores"]["sort"], actions: StoresActions) =>
  COLUMNS.map(([key, label, kind]) => {
    const active = sort.key === key;
    return html`<th scope="col" class=${kind}
      aria-sort=${active ? (sort.desc ? "descending" : "ascending") : "none"}>
      <button class=${`sort ${active ? (sort.desc ? "desc" : "asc") : ""}`} type="button"
        @click=${() => actions.setStoreSort(key)}>${label}</button>
    </th>`;
  });

const row = (store: StoreRow, selected: boolean, actions: StoresActions) => {
  const sub = subscriptionState(store);
  const summary = store.summary;
  const href = `#/stores/${store.id}`;
  const open = () => {
    window.location.hash = href;
  };
  return html`
    <tr class=${selected ? "selected" : ""} @click=${open}
      @mouseenter=${() => actions.prefetchStore(store.id)}>
      <td class="num">${fmt(store.id)}</td>
      <td>
        <a href=${href} aria-current=${selected ? "true" : "false"}><b>${store.name}</b></a>
        <span class="sub">
          <span dir="ltr">${store.username}</span>${store.verified ? " · موثّق" : ""}
          ${store.status === "active" && !store.archived ? nothing : html` · ${state(statusText(store), statusTone(store))}`}
          ${store.pendingDevices ? html` · ${state("طلب ينتظر", "warn")}` : nothing}
        </span>
      </td>
      <td>${state(sub.label, sub.tone)}</td>
      <td class="num opt">${fmt(store.activeDevices)} / ${fmt(store.allowedDevices)}</td>
      <td class="num opt full-only">${summary.initialized ? fmt(summary.activeContacts ?? summary.contacts) : "—"}</td>
      <td class="num opt">${summary.initialized ? money(summary.receivableCents) : "—"}</td>
    </tr>
  `;
};

/** Up and Down walk the list and open each store, so a person can scan without the mouse. */
const walkRows = (event: KeyboardEvent) => {
  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
  const links = [...(event.currentTarget as HTMLElement).querySelectorAll("a")];
  const at = links.indexOf(document.activeElement as HTMLAnchorElement);
  const next = links[at + (event.key === "ArrowDown" ? 1 : -1)];
  if (!next) return;
  event.preventDefault();
  next.focus();
  next.click();
};

export function storesView(model: Model, actions: StoresActions) {
  const page = model.stores;
  const list = page.search ?? page;
  const counts = (filter: (typeof STORE_FILTERS)[number][0]) =>
    list.rows.filter((store) => matchesFilter(store, filter)).length;
  const rows = sortStores(
    list.rows.filter((store) => matchesFilter(store, page.filter)),
    page.sort,
  );
  const open = model.screen === "store";
  const title = arabicCount(
    list.rows.filter((store) => !store.archived).length,
    "متجر واحد",
    "متجران",
    "متاجر",
    "متجرًا",
  );
  return html`
    <div class="pane-head">
      <div class="spread">
        ${open ? html`<h2>المتاجر</h2>` : html`<h1>المتاجر</h1>`}
        <span class="muted grow">${title} غير مؤرشفة</span>
        ${canWrite(model) ? html`<a class="btn" href="#/create">إنشاء حساب متجر</a>` : nothing}
      </div>
      <div class="toolbar">
        ${searchBox("بحث باسم المتجر أو اسم الدخول", page.q, actions.searchStores)}
        <div class="seg" role="group" aria-label="تصفية المتاجر">
          ${STORE_FILTERS.map(
            ([id, label]) => html`<button type="button" aria-pressed=${page.filter === id}
              @click=${() => actions.setStoreFilter(id)}>${label} ${fmt(counts(id))}</button>`,
          )}
        </div>
      </div>
    </div>

    ${loadState(list)}
    ${
      rows.length
        ? html`<table class="stores">
            <thead><tr>${header(page.sort, actions)}</tr></thead>
            <tbody @keydown=${walkRows}>
              ${rows.map((store) => row(store, model.store?.id === store.id, actions))}
            </tbody>
          </table>`
        : !list.loading && !list.error
          ? empty(page.q ? "لا توجد متاجر مطابقة للبحث." : "لا توجد متاجر بعد. أنشئ أول حساب متجر.")
          : nothing
    }
  `;
}
