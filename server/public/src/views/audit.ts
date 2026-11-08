import { html, nothing } from "lit-html";
import type { AuditEvent, AuditSource } from "../api";
import { dateTime, fmt } from "../format";
import type { AuditActions, Model, Paged, PageStep } from "../model";
import { loadState, pager, searchBox } from "./common";

export const KIND_LABELS: Record<string, string> = {
  "store.updated": "تعديل المتجر",
  "store.subscription": "تعديل الاشتراك",
  "account.password-reset": "كلمة مرور مؤقتة",
  "account.deleted": "حذف الحساب",
  "account.archive": "أرشفة الحساب",
  "account.unarchive": "إعادة من الأرشيف",
  "account.end-sessions": "إنهاء الجلسات",
  "account.revoke-all": "إلغاء جميع الأجهزة",
  "installation.approved": "اعتماد هاتف",
  "installation.revoked": "إلغاء اعتماد هاتف",
  "installation.reactivated": "إعادة اعتماد هاتف",
  "recovery.approved": "اعتماد استرداد",
};

const counts = (recordCounts: Record<string, number> | undefined) =>
  recordCounts
    ? Object.entries(recordCounts)
        .map(([name, count]) => `${name}: ${fmt(count)}`)
        .join(" · ")
    : "";

const event = (item: AuditEvent, showStore: boolean) => html`
  <tr>
    <td class="num opt">${dateTime(item.createdAt)}</td>
    <td>
      <b>${item.kind ? (KIND_LABELS[item.kind] ?? item.kind) : "قراءة مسجلة"}</b>
      <span class="sub phone"><bdi>${item.actor}</bdi><bdi>${dateTime(item.createdAt)}</bdi></span>
    </td>
    <td class="opt">${item.actor}</td>
    ${
      showStore
        ? html`<td class="num">${item.storeId ? html`<a href=${`#/stores/${item.storeId}`}>${fmt(item.storeId)}</a>` : "—"}</td>`
        : nothing
    }
    <td>
      ${item.reason}
      ${item.recordCounts ? html`<span class="sub">${counts(item.recordCounts)}</span>` : nothing}
    </td>
  </tr>
`;

export const sourceFilter = (source: AuditSource, set: (source: AuditSource) => void) => html`
  <div class="seg" role="group" aria-label="نوع السجل">
    <button type="button" aria-pressed=${source === "control"} @click=${() => set("control")}>
      إجراءات الإدارة</button>
    <button type="button" aria-pressed=${source === "access"} @click=${() => set("access")}>
      قراءات الدفتر</button>
  </div>
`;

export const auditList = (
  page: Paged<AuditEvent>,
  showStore: boolean,
  label: string,
  step: (step: PageStep) => void,
) => html`
  ${loadState(page)}
  ${
    page.rows.length
      ? html`<table>
          <thead><tr>
            <th scope="col" class="opt">الوقت</th><th scope="col">الإجراء</th><th scope="col" class="opt">المنفذ</th>
            ${showStore ? html`<th scope="col">المتجر</th>` : nothing}
            <th scope="col">السبب</th>
          </tr></thead>
          <tbody>${page.rows.map((item) => event(item, showStore))}</tbody>
        </table>`
      : page.loading
        ? nothing
        : html`<p class="empty">لا توجد أحداث مسجلة</p>`
  }
  ${pager(label, page, step)}
`;

export function auditView(model: Model, actions: AuditActions) {
  return html`
    <main class="page">
      <div class="page-head">
        <div>
          <h1>سجل الإدارة</h1>
          <p class="lead">كل إجراء إداري وكل قراءة للدفتر، الأحدث أولًا.</p>
        </div>
      </div>
      <section class="section">
        <div class="toolbar">
          ${searchBox("بحث بالمنفذ أو النوع أو السبب", model.audit.q, actions.searchAudit)}
          ${sourceFilter(model.audit.source, actions.setAuditSource)}
        </div>
        ${auditList(model.audit, true, "صفحات سجل الإدارة", actions.pageAudit)}
      </section>
    </main>
  `;
}
