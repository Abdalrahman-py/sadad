import { html, nothing, type TemplateResult } from "lit-html";
import type { LedgerKind, StoreDetail } from "../api";
import { dateTime, fmt, money } from "../format";
import type { LedgerActions, LedgerState } from "../model";
import { empty, loadState, metric, pager, searchBox } from "./common";

type Row = Record<string, unknown>;
const text = (row: Row, key: string) => {
  const value = row[key];
  return value === null || value === undefined ? "" : String(value);
};
const cents = (row: Row, key: string) => money(Number(row[key] ?? 0));
const direction = (row: Row) =>
  row.direction === "receivable" ? "للمتجر" : row.direction === "payable" ? "على المتجر" : "—";

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
  return html`<div class="table-wrap"><table class="wide">
    <thead><tr>${columns.map(([label]) => html`<th scope="col">${label}</th>`)}</tr></thead>
    <tbody>${
      rows.length
        ? rows.map(
            (row) => html`<tr>${columns.map(([, cell]) => html`<td>${cell(row)}</td>`)}</tr>`,
          )
        : html`<tr><td colspan=${columns.length} class="empty">لا توجد سجلات مطابقة</td></tr>`
    }</tbody>
  </table></div>`;
}

export function ledgerView(detail: StoreDetail, state: LedgerState | null, actions: LedgerActions) {
  if (!detail.ledgerInitialized) {
    return html`<section class="section">
      <h2>دفتر المتجر</h2>
      <p class="muted">لم يُهيأ الدفتر بعد؛ يبدأ بعد تسجيل جهاز واعتماده.</p>
    </section>`;
  }
  if (!state) {
    return html`<section class="section narrow">
      <h2>دفتر المتجر خاص بصاحبه</h2>
      <p>
        كل قراءة تُسجَّل باسمك مع السبب في «سجل النشاط» تحت «قراءات الدفتر». التصحيحات المالية يجريها
        صاحب المتجر من الهاتف.
      </p>
      <form class="stack" data-read @submit=${(event: SubmitEvent) => {
        event.preventDefault();
        const form = new FormData(event.currentTarget as HTMLFormElement);
        actions.openLedger(String(form.get("reason") ?? ""));
      }}>
        <label class="field">سبب القراءة
          <textarea name="reason" rows="2" maxlength="2000" required
            placeholder="مثال: شكوى من صاحب المتجر حول رصيد زبون"></textarea>
        </label>
        <div><button class="btn btn-primary">عرض الدفتر وتسجيل القراءة</button></div>
      </form>
    </section>`;
  }
  const page = state.page;
  return html`
    <section class="section">
      <div class="spread">
        <span class="small muted">سُجّلت القراءة · السبب: ${state.reason}</span>
        <button class="btn" type="button" ?disabled=${state.loading}
          @click=${actions.downloadLedger}>تنزيل CSV</button>
      </div>
      <div class="toolbar">
        <div class="seg" role="group" aria-label="أقسام الدفتر">
          ${(Object.keys(LEDGER_KINDS) as LedgerKind[]).map(
            (kind) => html`<button type="button" aria-pressed=${state.kind === kind}
              @click=${() => actions.setLedgerKind(kind)}>${LEDGER_KINDS[kind]}</button>`,
          )}
        </div>
        ${searchBox("بحث في الدفتر", state.q, actions.searchLedger)}
      </div>
      ${loadState(state)}
      ${
        page && page.kind === state.kind
          ? html`
            <div class="facts">
              ${metric("مستحق للمتجر", money(page.totals.receivableCents))}
              ${metric("مستحق على المتجر", money(page.totals.payableCents))}
              ${metric("صافي الرصيد", money(page.totals.netCents))}
              ${metric("آخر حركة", fmt(page.latestSequence))}
            </div>
            ${table(page.kind, page.rows)}
            ${pager("صفحات الدفتر", state, actions.pageLedger)}`
          : state.loading
            ? nothing
            : empty("لا سجلات بعد")
      }
    </section>
  `;
}
