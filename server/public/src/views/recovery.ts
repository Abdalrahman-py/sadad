import { html, nothing } from "lit-html";
import type { StoreDetail } from "../api";
import { dateTime, fmt, money } from "../format";
import type { Actions, RecoveryState } from "../model";
import { inspectManifest } from "../recovery-manifest";
import { metric } from "./common";

/** The phones a recovery can move commands between: revoked ones, and the active ones after them. */
export function recoveryChoices(detail: StoreDetail) {
  const active = detail.installations.filter((p) => p.status === "active");
  const newest = Math.max(-1, ...active.map((p) => p.generation));
  const old = detail.installations.filter((p) => p.status === "revoked" && p.generation < newest);
  return { active, old };
}

const permits = (detail: StoreDetail) =>
  detail.recoveryPermits.length
    ? html`<h4>تصاريح الاسترداد السابقة</h4><div class="record-list">${detail.recoveryPermits.map(
        (permit) => html`<div class="record-row"><div>
          <b>${permit.approvedBy} · ${new Date(permit.expiresAt) > new Date() ? "صالح" : "منتهٍ"}</b>
          <small>${permit.reason} · ينتهي ${dateTime(permit.expiresAt)}</small>
          <code dir="ltr">${permit.permitId}</code>
        </div></div>`,
      )}</div>`
    : nothing;

function preview(state: RecoveryState, detail: StoreDetail) {
  if (!state.manifest.trim()) return { view: nothing, ready: false };
  const old = detail.installations.find((p) => p.installationId === state.oldId);
  const report = inspectManifest(state.manifest, old);
  const blocking = report.problems.filter((p) => p.blocking);
  const warnings = report.problems.filter((p) => !p.blocking);
  const stats = report.stats;
  const verdict = !stats
    ? "تعذّرت قراءة القائمة."
    : blocking.length
      ? `${fmt(blocking.length)} مشكلة تمنع الاعتماد.`
      : warnings.length
        ? `جاهز للاعتماد مع ${fmt(warnings.length)} تنبيه.`
        : "جاهز للاعتماد.";
  return {
    ready: !!stats && blocking.length === 0,
    view: html`
      <div class="panel-inset stack">
        <div class="panel-head"><h4>معاينة قبل الاعتماد</h4><span class="small muted" role="status">${verdict}</span></div>
        ${
          stats
            ? html`<div class="mini-metrics">
                ${metric("الإدخالات", fmt(stats.total), true)}
                ${metric("أشخاص", fmt(stats.byType["contact.create"] ?? 0), true)}
                ${metric("ديون", fmt(stats.byType["debt.create"] ?? 0), true)}
                ${metric("دفعات", fmt(stats.byType["payment.create"] ?? 0), true)}
                ${metric("إجمالي الديون", money(stats.debtCents), true)}
                ${metric("إجمالي الدفعات", money(stats.paymentCents), true)}
              </div>
              ${
                stats.first !== null && stats.last !== null
                  ? html`<p class="small muted">أقدم إدخال ${dateTime(stats.first)} · أحدث إدخال ${dateTime(stats.last)}</p>`
                  : nothing
              }`
            : nothing
        }
        <div class="record-list">${
          report.problems.length
            ? report.problems.map(
                (p) => html`<div class="record-row"><div>
                  <b class=${p.blocking ? "form-error" : ""}>${p.blocking ? "يمنع الاعتماد" : "تنبيه"}</b>
                  <small>${p.message}</small></div></div>`,
              )
            : html`<div class="record-row"><div><b>لا مشاكل في الصيغة</b>
                <small>كل إدخال يحمل النوع ومعرّف العملية وهوية الهاتف السابق.</small></div></div>`
        }</div>
        <details><summary>تفاصيل الإدخالات</summary><div class="record-list">${report.details
          .slice(0, 200)
          .map(
            (d) =>
              html`<div class="record-row"><div><b>${d.at}</b><small>${d.text} · ${d.sub}</small></div></div>`,
          )}</div></details>
      </div>`,
  };
}

export function recoveryView(
  detail: StoreDetail,
  state: RecoveryState,
  write: boolean,
  actions: Actions,
) {
  const { active, old } = recoveryChoices(detail);
  const permitted = active.length > 0 && old.length > 0 && !detail.store.archived;
  const check = preview(state, detail);
  const set = (key: "oldId" | "newId" | "reason" | "manifest") => (event: Event) =>
    actions.setRecovery({ [key]: (event.currentTarget as HTMLInputElement).value });
  return html`
    <section class="panel-inset stack">
      <h3>استرداد إدخالات الهاتف السابق</h3>
      ${
        write
          ? html`
            <p class="muted small">صدّر «قائمة اعتماد الإدارة» من الهاتف السابق، واخترها أو الصقها هنا. التصريح صالح 24 ساعة؛ يُدخله الهاتف المعتمد عبر «إدخال تصريح الاسترداد».</p>
            <form class="stack" @submit=${(event: SubmitEvent) => {
              event.preventDefault();
              actions.approveRecovery();
            }}>
              <fieldset class="stack" ?disabled=${!permitted || state.busy}>
                <label>الهاتف السابق<select required .value=${state.oldId} @change=${set("oldId")}>
                  <option value="">اختر الهاتف السابق</option>
                  ${old.map((p) => html`<option value=${p.installationId} ?selected=${p.installationId === state.oldId}>${fmt(p.generation)} · ${p.installationId}</option>`)}
                </select></label>
                <label>الهاتف المعتمد حاليًا<select required .value=${state.newId} @change=${set("newId")}>
                  <option value="">${active.length ? "اختر الهاتف المعتمد" : "اعتمد الهاتف الجديد أولًا"}</option>
                  ${active.map((p) => html`<option value=${p.installationId} ?selected=${p.installationId === state.newId}>${fmt(p.generation)} · ${p.installationId}</option>`)}
                </select></label>
                <label>قائمة الأوامر الأصلية<textarea dir="ltr" rows="6" required .value=${state.manifest}
                  @input=${set("manifest")} placeholder="الصق قائمة الأوامر من الهاتف"></textarea></label>
                <label>أو اختر ملف قائمة الأوامر<input type="file" accept="application/json,.json"
                  @change=${(event: Event) => {
                    const file = (event.currentTarget as HTMLInputElement).files?.[0];
                    if (file) actions.loadManifestFile(file);
                  }}></label>
                <label>سبب الاسترداد<textarea rows="2" maxlength="2000" required .value=${state.reason}
                  @input=${set("reason")}></textarea></label>
                <button class="button primary" ?disabled=${!check.ready}>اعتماد الاسترداد</button>
              </fieldset>
              <p class="form-error" role="alert">${state.error}</p>
            </form>
            ${check.view}
            ${
              state.permit
                ? html`<div class="result" role="status"><b>تم اعتماد الاسترداد</b>
                    <span class="permit-id">${state.permit.permitId}</span>
                    <p>ينتهي ${dateTime(state.permit.expiresAt)}. انسخ رقم التصريح إلى الهاتف المعتمد ← مزامنة السجلات والاسترداد ← إدخال تصريح الاسترداد.</p></div>`
                : nothing
            }`
          : html`<p class="muted small">اعتماد الاسترداد للإدارة فقط.</p>`
      }
      ${permits(detail)}
    </section>
  `;
}
