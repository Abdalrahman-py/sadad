import { html, nothing } from "lit-html";
import type { StoreDetail } from "../api";
import { dateTime, fmt, money } from "../format";
import type { RecoveryActions, RecoveryState } from "../model";
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
    ? html`<div class="stack">
        <h3>تصاريح الاسترداد السابقة</h3>
        <div class="list">
          ${detail.recoveryPermits.map(
            (permit) => html`<div class="list-row">
              <div class="grow">
                <b>${permit.approvedBy} · ${new Date(permit.expiresAt) > new Date() ? "صالح" : "منتهٍ"}</b>
                <small class="small muted">${permit.reason} · ينتهي ${dateTime(permit.expiresAt)}</small>
                <span class="sub"><code dir="ltr">${permit.permitId}</code></span>
              </div>
            </div>`,
          )}
        </div>
      </div>`
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
      <div class="stack">
        <div class="spread">
          <h3>معاينة قبل الاعتماد</h3>
          <span class="small muted" role="status">${verdict}</span>
        </div>
        ${
          stats
            ? html`
              <div class="facts">
                ${metric("الإدخالات", fmt(stats.total))}
                ${metric("أشخاص", fmt(stats.byType["contact.create"] ?? 0))}
                ${metric("ديون", fmt(stats.byType["debt.create"] ?? 0))}
                ${metric("دفعات", fmt(stats.byType["payment.create"] ?? 0))}
                ${metric("إجمالي الديون", money(stats.debtCents))}
                ${metric("إجمالي الدفعات", money(stats.paymentCents))}
              </div>
              ${
                stats.first !== null && stats.last !== null
                  ? html`<p class="small muted">أقدم إدخال ${dateTime(stats.first)} · أحدث إدخال ${dateTime(stats.last)}</p>`
                  : nothing
              }`
            : nothing
        }
        <div class="list">
          ${
            report.problems.length
              ? report.problems.map(
                  (problem) => html`<div class="list-row"><div class="grow">
                    <b class=${problem.blocking ? "form-error" : ""}>${problem.blocking ? "يمنع الاعتماد" : "تنبيه"}</b>
                    <small class="small muted">${problem.message}</small></div></div>`,
                )
              : html`<div class="list-row"><div class="grow"><b>لا مشاكل في الصيغة</b>
                  <small class="small muted">كل إدخال يحمل النوع ومعرّف العملية وهوية الهاتف السابق.</small></div></div>`
          }
        </div>
        <details>
          <summary>تفاصيل الإدخالات</summary>
          <div class="list">
            ${report.details.slice(0, 200).map(
              (detailRow) => html`<div class="list-row"><div class="grow">
                <b>${detailRow.at}</b><small class="small muted">${detailRow.text} · ${detailRow.sub}</small></div></div>`,
            )}
          </div>
        </details>
      </div>`,
  };
}

export function recoveryView(
  detail: StoreDetail,
  state: RecoveryState,
  write: boolean,
  actions: RecoveryActions,
) {
  const { active, old } = recoveryChoices(detail);
  const permitted = active.length > 0 && old.length > 0 && !detail.store.archived;
  const check = preview(state, detail);
  const set = (key: "oldId" | "newId" | "reason" | "manifest") => (event: Event) =>
    actions.setRecovery({ [key]: (event.currentTarget as HTMLInputElement).value });
  const optionLabel = (phone: (typeof active)[number]) =>
    `${fmt(phone.generation)} · ${phone.staffName || phone.label || phone.installationId}`;
  return html`
    <section class="section narrow">
      <div class="stack">
        <h2>استرداد إدخالات الهاتف السابق</h2>
        <p>
          حين يغيّر صاحب المتجر هاتفه وعليه إدخالات لم تُرسل، يرسل لك «قائمة اعتماد» من التطبيق. تعتمدها
          هنا فتُقبل إدخالات الهاتف القديم. التصريح صالح 24 ساعة؛ يُدخله الهاتف المعتمد عبر «إدخال تصريح
          الاسترداد».
        </p>
      </div>
      ${
        write
          ? html`
            <form class="stack" @submit=${(event: SubmitEvent) => {
              event.preventDefault();
              actions.approveRecovery();
            }}>
              <fieldset class="stack" ?disabled=${!permitted || state.busy}>
                <ol class="steps">
                  <li>
                    <b>1 · الهاتفان</b>
                    <div class="field-row">
                      <label class="field">الهاتف القديم
                        <select required .value=${state.oldId} @change=${set("oldId")}>
                          <option value="">اختر الهاتف السابق</option>
                          ${old.map(
                            (phone) => html`<option value=${phone.installationId}
                              ?selected=${phone.installationId === state.oldId}>${optionLabel(phone)}</option>`,
                          )}
                        </select>
                      </label>
                      <label class="field">الهاتف المعتمد الآن
                        <select required .value=${state.newId} @change=${set("newId")}>
                          <option value="">${active.length ? "اختر الهاتف المعتمد" : "اعتمد الهاتف الجديد أولًا"}</option>
                          ${active.map(
                            (phone) => html`<option value=${phone.installationId}
                              ?selected=${phone.installationId === state.newId}>${optionLabel(phone)}</option>`,
                          )}
                        </select>
                      </label>
                    </div>
                    ${
                      permitted
                        ? nothing
                        : html`<span class="small state-warn">
                            يحتاج الاسترداد هاتفًا قديمًا ملغى وهاتفًا معتمدًا حاليًا على الأقل.
                          </span>`
                    }
                  </li>
                  <li>
                    <b>2 · قائمة الاعتماد</b>
                    <label class="file-pick">
                      اختر ملف القائمة (JSON)
                      <input type="file" accept="application/json,.json" class="sr-only"
                        @change=${(event: Event) => {
                          const file = (event.currentTarget as HTMLInputElement).files?.[0];
                          if (file) actions.loadManifestFile(file);
                        }}>
                    </label>
                    <label class="field">أو الصق محتوى القائمة
                      <textarea dir="ltr" rows="4" required .value=${state.manifest}
                        @input=${set("manifest")} placeholder="الصق قائمة الأوامر من الهاتف"></textarea>
                    </label>
                  </li>
                  <li>
                    <b>3 · السبب والاعتماد</b>
                    <label class="field">سبب الاسترداد
                      <textarea rows="2" maxlength="2000" required .value=${state.reason}
                        @input=${set("reason")} placeholder="مثال: تغيير هاتف صاحب المتجر بعد عطل"></textarea>
                    </label>
                    <div><button class="btn btn-primary"
                      ?disabled=${!check.ready}>اعتماد الاسترداد</button></div>
                  </li>
                </ol>
              </fieldset>
              <p class="form-error" role="alert">${state.error}</p>
            </form>
            ${check.view}
            ${
              state.permit
                ? html`<div class="note" role="status">
                    <b>تم اعتماد الاسترداد</b>
                    <p><code dir="ltr">${state.permit.permitId}</code></p>
                    <p class="small">
                      ينتهي ${dateTime(state.permit.expiresAt)}. انسخ رقم التصريح إلى الهاتف المعتمد ←
                      مزامنة السجلات والاسترداد ← إدخال تصريح الاسترداد.
                    </p>
                  </div>`
                : nothing
            }`
          : html`<p class="small muted">اعتماد الاسترداد للإدارة فقط.</p>`
      }
      ${permits(detail)}
    </section>
  `;
}
