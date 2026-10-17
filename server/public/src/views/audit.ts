import { html, nothing } from "lit-html";
import type { AuditEvent, AuditSource } from "../api";
import { dateTime, fmt } from "../format";
import type { Actions, Model, Paged, PageStep } from "../model";
import { loadState, pager, searchBox } from "./common";

const KINDS: Record<string, string> = {
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
  <div class="record-row"><div>
    <b>${item.kind ? (KINDS[item.kind] ?? item.kind) : "قراءة مسجلة"} · ${item.actor}</b>
    <small>${item.reason}${showStore ? ` · المتجر ${item.storeId}` : ""} · ${dateTime(item.createdAt)}</small>
    ${item.recordCounts ? html`<small>${counts(item.recordCounts)}</small>` : nothing}
  </div></div>
`;

export const sourceSwitch = (source: AuditSource, set: (source: AuditSource) => void) => html`
  <div class="button-row" role="group" aria-label="نوع السجل">
    <button type="button" class="tab" aria-pressed=${source === "control"} @click=${() => set("control")}>إجراءات الإدارة</button>
    <button type="button" class="tab" aria-pressed=${source === "access"} @click=${() => set("access")}>قراءات الدفتر</button>
  </div>
`;

export const auditList = (
  page: Paged<AuditEvent>,
  showStore: boolean,
  label: string,
  step: (step: PageStep) => void,
) => html`
  ${loadState(page)}
  <div class="record-list">
    ${
      page.rows.length
        ? page.rows.map((item) => event(item, showStore))
        : page.loading
          ? nothing
          : html`<p class="muted">لا توجد أحداث مسجلة.</p>`
    }
  </div>
  ${pager(label, page, step)}
`;

export const auditView = (model: Model, actions: Actions) => html`
  <section class="panel">
    <div class="panel-head">
      <div><h2>سجل الإدارة</h2><p class="muted">كل إجراء إداري وكل قراءة للدفتر، الأحدث أولًا.</p></div>
      ${searchBox("بحث بالمنفذ أو النوع أو السبب", model.audit.q, actions.searchAudit)}
    </div>
    ${sourceSwitch(model.audit.source, actions.setAuditSource)}
    ${auditList(model.audit, true, "صفحات سجل الإدارة", actions.pageAudit)}
  </section>
`;
