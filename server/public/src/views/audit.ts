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
  "recovery.approved": "اعتماد استرداد",
};

const counts = (recordCounts: Record<string, number> | undefined) =>
  recordCounts
    ? Object.entries(recordCounts)
        .map(([name, count]) => `${name}: ${fmt(count)}`)
        .join(" · ")
    : "";

const event = (item: AuditEvent, showStore: boolean) => html`
  <div class="list-row">
    <div class="grow">
      <b>${item.kind ? (KIND_LABELS[item.kind] ?? item.kind) : "قراءة مسجلة"} · ${item.actor}</b>
      <small class="small muted">
        ${item.reason}${showStore && item.storeId ? ` · المتجر ${fmt(item.storeId)}` : ""} ·
        ${dateTime(item.createdAt)}
      </small>
      ${item.recordCounts ? html`<small class="small muted">${counts(item.recordCounts)}</small>` : nothing}
    </div>
  </div>
`;

export const sourceChips = (source: AuditSource, set: (source: AuditSource) => void) => html`
  <div class="chips" role="group" aria-label="نوع السجل">
    <button class="chip" type="button" aria-pressed=${source === "control"} @click=${() => set("control")}>
      إجراءات الإدارة</button>
    <button class="chip" type="button" aria-pressed=${source === "access"} @click=${() => set("access")}>
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
      ? html`<div class="list">${page.rows.map((item) => event(item, showStore))}</div>`
      : page.loading
        ? nothing
        : html`<p class="empty">لا توجد أحداث مسجلة.</p>`
  }
  ${pager(label, page, step)}
`;

export function auditView(model: Model, actions: AuditActions) {
  return html`
    <main class="page">
      <div class="page-head">
        <div class="title-group">
          <h1>سجل الإدارة</h1>
          <p class="muted">كل إجراء إداري وكل قراءة للدفتر، الأحدث أولًا.</p>
        </div>
      </div>
      <div class="row">
        ${searchBox("بحث بالمنفذ أو النوع أو السبب", model.audit.q, actions.searchAudit)}
        ${sourceChips(model.audit.source, actions.setAuditSource)}
      </div>
      ${auditList(model.audit, true, "صفحات سجل الإدارة", actions.pageAudit)}
    </main>
  `;
}
