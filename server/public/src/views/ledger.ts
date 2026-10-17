import { html, nothing, type TemplateResult } from "lit-html";
import type { LedgerKind, StoreDetail } from "../api";
import { dateTime, fmt, money } from "../format";
import type { Actions, LedgerState } from "../model";
import { loadState, metric, onSubmit, pager, searchBox } from "./common";

type Row = Record<string, unknown>;
const text = (row: Row, key: string) => {
  const value = row[key];
  return value === null || value === undefined ? "" : String(value);
};
const cents = (row: Row, key: string) => money(Number(row[key] ?? 0));
const direction = (row: Row) =>
  row.direction === "receivable" ? "للمتجر" : row.direction === "payable" ? "على المتجر" : "—";

export const DEFAULT_LEDGER_REASON = "مراجعة دعم من لوحة الإدارة";

export const LEDGER_KINDS: Record<LedgerKind, string> = {
  contacts: "جهات الاتصال",
  debts: "الديون",
  payments: "الدفعات",
  events: "سجل الحركات",
};

/** Header and cells per kind; the CSV export uses the same columns. */
export const LEDGER_COLUMNS: Record<LedgerKind, [string, (row: Row) => string][]> = {
  contacts: [
    ["الشخص", (r) => text(r, "name")],
    ["الهاتف", (r) => text(r, "phone")],
    ["الحالة", (r) => (r.archived ? "مؤرشف" : "نشط")],
    ["مستحق للمتجر", (r) => cents(r, "receivableCents")],
    ["مستحق على المتجر", (r) => cents(r, "payableCents")],
    ["ملاحظة", (r) => text(r, "note")],
  ],
  debts: [
    ["الشخص", (r) => text(r, "contactName")],
    ["الاتجاه", direction],
    ["المبلغ الحالي", (r) => cents(r, "amountCents")],
    ["المدفوع", (r) => cents(r, "paidCents")],
    ["المتبقي", (r) => cents(r, "remainingCents")],
    ["الاستحقاق", (r) => text(r, "dueDate") || "—"],
    ["التاريخ", (r) => dateTime(text(r, "createdAt"))],
    ["البيان", (r) => text(r, "note")],
  ],
  payments: [
    ["الشخص", (r) => text(r, "contactName")],
    ["الاتجاه", direction],
    ["المبلغ", (r) => cents(r, "amountCents")],
    ["الطريقة", (r) => text(r, "method")],
    ["الحالة", (r) => (r.reversed ? "معكوسة" : "سارية")],
    ["التاريخ", (r) => dateTime(text(r, "createdAt"))],
    ["البيان", (r) => text(r, "note")],
  ],
  events: [
    ["التسلسل", (r) => text(r, "sequence")],
    ["النوع", (r) => text(r, "kind")],
    ["الجهاز", (r) => text(r, "installationId").slice(0, 8)],
    ["الجيل", (r) => text(r, "generation")],
    ["التاريخ", (r) => dateTime(text(r, "createdAt"))],
    ["رقم العملية", (r) => text(r, "operationId")],
  ],
};

function table(kind: LedgerKind, rows: Row[]): TemplateResult {
  const columns = LEDGER_COLUMNS[kind];
  return html`<div class="table-wrap"><table>
    <thead><tr>${columns.map(([label]) => html`<th>${label}</th>`)}</tr></thead>
    <tbody>${
      rows.length
        ? rows.map(
            (row) => html`<tr>${columns.map(([, cell]) => html`<td>${cell(row)}</td>`)}</tr>`,
          )
        : html`<tr><td colspan=${columns.length} class="empty">لا توجد سجلات مطابقة.</td></tr>`
    }</tbody>
  </table></div>`;
}

export function ledgerView(detail: StoreDetail, state: LedgerState | null, actions: Actions) {
  if (!detail.ledgerInitialized) {
    return html`<section class="panel-inset stack"><h3>دفتر المتجر</h3>
      <p class="muted">لم يُهيأ الدفتر بعد؛ يبدأ بعد تسجيل جهاز واعتماده.</p></section>`;
  }
  if (!state) {
    return html`<section class="panel-inset stack">
      <h3>دفتر المتجر</h3>
      <p class="muted small">كل قراءة للدفتر تُسجَّل مع سببها في سجل الإدارة. التصحيحات المالية يجريها صاحب المتجر من الهاتف.</p>
      <form class="stack" data-read @submit=${onSubmit((form) => actions.openLedger(String(form.get("reason") ?? "")))}>
        <label>سبب القراءة<input name="reason" required maxlength="2000" value=${DEFAULT_LEDGER_REASON}></label>
        <div class="button-row"><button class="button secondary">عرض الدفتر</button></div>
      </form>
    </section>`;
  }
  const page = state.page;
  return html`
    <section class="panel-inset stack">
      <div class="panel-head">
        <div><h3>دفتر المتجر</h3><p class="small muted">سبب القراءة: ${state.reason}</p></div>
        <div class="button-row">
          ${searchBox("بحث في الدفتر", state.q, actions.searchLedger)}
          <button class="button quiet" type="button" ?disabled=${state.loading} @click=${actions.downloadLedger}>تنزيل CSV</button>
        </div>
      </div>
      <div class="button-row" role="group" aria-label="أقسام الدفتر">
        ${(Object.keys(LEDGER_KINDS) as LedgerKind[]).map(
          (kind) => html`<button type="button" class="tab" aria-pressed=${state.kind === kind}
            @click=${() => actions.setLedgerKind(kind)}>${LEDGER_KINDS[kind]}</button>`,
        )}
      </div>
      ${
        page
          ? html`<div class="mini-metrics">
              ${metric("مستحق للمتجر", money(page.totals.receivableCents), true)}
              ${metric("مستحق على المتجر", money(page.totals.payableCents), true)}
              ${metric("صافي الرصيد", money(page.totals.netCents), true)}
            </div>
            <p class="small muted">آخر حركة رقم ${fmt(page.latestSequence)}</p>`
          : nothing
      }
      ${loadState(state)}
      ${page && page.kind === state.kind ? table(page.kind, page.rows) : nothing}
      ${pager("صفحات الدفتر", state, actions.pageLedger)}
    </section>
  `;
}
