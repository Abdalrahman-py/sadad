import { html, nothing } from "lit-html";
import type { PendingInstallation } from "../api";
import { dateTime, fmt } from "../format";
import { type Actions, canWrite, type Model } from "../model";
import { loadState, pager, searchBox } from "./common";

const row = (device: PendingInstallation, write: boolean, actions: Actions) => html`
  <div class="record-row">
    <div>
      <b>${device.storeName}</b>
      <small>${device.staffName || device.label || "جهاز"} · الجيل ${fmt(device.generation)} · ${dateTime(device.createdAt)}</small>
      <code dir="ltr">${device.installationId}</code>
    </div>
    <div class="button-row">
      <button class="button quiet" type="button" @click=${() => actions.openStore(device.storeId)}>فتح المتجر</button>
      ${
        write
          ? html`
            <button class="button primary" type="button"
              @click=${() => actions.decideInstallation(device.installationId, "approve", device.storeId)}>اعتماد الهاتف</button>
            <button class="button danger" type="button"
              @click=${() => actions.decideInstallation(device.installationId, "revoke", device.storeId)}>إلغاء الاعتماد</button>`
          : nothing
      }
    </div>
  </div>
`;

export const installationsView = (model: Model, actions: Actions) => {
  const page = model.pending;
  const write = canWrite(model);
  return html`
    <section class="panel">
      <div class="panel-head">
        <div><h2>طلبات الأجهزة</h2><p class="muted">أجهزة سجّلها أصحاب المتاجر وتنتظر الاعتماد، الأحدث أولًا.</p></div>
        ${searchBox("بحث بالمتجر أو الجهاز", page.q, actions.searchPending)}
      </div>
      ${loadState(page)}
      <div class="record-list">${page.rows.map((device) => row(device, write, actions))}</div>
      ${
        !page.rows.length && !page.loading && !page.error
          ? html`<div class="empty">لا توجد أجهزة بانتظار الاعتماد.</div>`
          : nothing
      }
      ${pager("صفحات طلبات الأجهزة", page, actions.pagePending)}
    </section>
  `;
};
