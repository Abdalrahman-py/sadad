import { html, nothing } from "lit-html";
import type { PendingInstallation, StoreRow } from "../api";
import { dateTime, fmt } from "../format";
import { canWrite, type Model, type QueueActions } from "../model";
import { arabicCount, byUrgency, needsDecision, subscriptionState } from "../subscription";
import { KIND_LABELS } from "./audit";
import { empty, loadState } from "./common";

/** Pending devices plus subscriptions ending within a week: the rows the queue shows. */
export const taskCount = (model: Model): number =>
  model.pending.rows.length + model.stores.rows.filter(needsDecision).length;

const slots = (store: StoreRow | undefined) => {
  if (!store) return null;
  const used = store.activeDevices;
  const limit = store.allowedDevices;
  return used >= limit
    ? { full: true, text: `الحد ممتلئ: ${fmt(used)} من ${fmt(limit)} أجهزة معتمدة` }
    : { full: false, text: `يصبح ${fmt(used + 1)} من ${fmt(limit)} أجهزة` };
};

const deviceTask = (
  device: PendingInstallation,
  store: StoreRow | undefined,
  write: boolean,
  actions: QueueActions,
) => {
  const slot = slots(store);
  return html`
    <li class="task">
      <span class="mark">جهاز ينتظر الاعتماد</span>
      <span>
        <a href=${`#/stores/${device.storeId}/devices`}><b>${device.storeName}</b></a>
        <span class="sub">رقم ${fmt(device.storeId)}</span>
      </span>
      <span>
        <bdi>${[device.staffName, device.label].filter(Boolean).join(" · ") || "جهاز"}</bdi> ·
        <bdi>${dateTime(device.createdAt)}</bdi>
        ${slot ? html`<span class="sub ${slot.full ? "state-warn" : ""}">${slot.text}</span>` : nothing}
      </span>
      ${
        write
          ? html`<div class="btn-group task-actions">
              ${
                slot?.full && store
                  ? html`<button class="btn btn-primary" type="button"
                      @click=${() => actions.raiseLimitAndApprove(device, store)}>رفع الحد إلى ${fmt(store.allowedDevices + 1)} واعتماد</button>`
                  : html`<button class="btn btn-primary" type="button"
                      @click=${() => actions.quickApprove(device)}>اعتماد الجهاز</button>`
              }
              <button class="btn" type="button" @click=${() => actions.quickReject(device)}>رفض</button>
              ${
                slot?.full
                  ? html`<a href=${`#/stores/${device.storeId}/devices`}>استبدال جهاز قديم</a>`
                  : nothing
              }
            </div>`
          : nothing
      }
    </li>`;
};

const subscriptionTask = (store: StoreRow, write: boolean, actions: QueueActions) => {
  const sub = subscriptionState(store);
  const ended = sub.tone === "danger";
  return html`
    <li class="task">
      <span class=${ended ? "mark" : "state-warn"}>${ended ? "اشتراك انتهى" : "اشتراك ينتهي قريبًا"}</span>
      <span>
        <a href=${`#/stores/${store.id}/subscription`}><b>${store.name}</b></a>
        <span class="sub">رقم ${fmt(store.id)}</span>
      </span>
      <span>${sub.queue}</span>
      ${
        write
          ? html`<div class="btn-group task-actions">
              <button class=${`btn ${ended ? "btn-primary" : ""}`} type="button"
                @click=${() => actions.quickExtend(store, 30)}>
                ${ended ? "تجديد 30 يومًا وتفعيل" : "تمديد 30 يومًا"}</button>
            </div>`
          : nothing
      }
    </li>`;
};

const platformNumbers = (model: Model) => {
  const overview = model.overview;
  if (!overview) return html`<p class="loading">جارٍ التحميل…</p>`;
  return html`<p class="line">
    <span>متاجر نشطة ${fmt(overview.active)} من ${fmt(overview.stores)}</span>
    <span>موثّقة ${fmt(overview.verified)}</span>
    <span>مجمّدة ${fmt(overview.suspended)}</span>
    <span>أجهزة تنتظر ${fmt(overview.pendingDevices)}</span>
    ${overview.archived ? html`<span>مؤرشفة ${fmt(overview.archived)}</span>` : nothing}
  </p>`;
};

const recentActions = (model: Model) => html`
  <section class="section" aria-labelledby="recent-h">
    <div class="spread">
      <h2 id="recent-h">آخر إجراءات الإدارة</h2>
      <a href="#/audit">السجل كاملًا</a>
    </div>
    ${loadState(model.audit)}
    ${
      model.audit.rows.length
        ? html`<div class="list">
            ${model.audit.rows.slice(0, 5).map(
              (event) => html`<div class="list-row">
                <span>${event.kind ? (KIND_LABELS[event.kind] ?? event.kind) : "قراءة مسجلة"} · ${event.actor}</span>
                <span class="small muted">${event.storeId ? `المتجر ${fmt(event.storeId)} · ` : ""}${dateTime(event.createdAt)}</span>
              </div>`,
            )}
          </div>`
        : model.audit.loading
          ? nothing
          : empty("لا إجراءات مسجلة بعد.")
    }
  </section>
`;

export function tasksView(model: Model, actions: QueueActions) {
  const write = canWrite(model);
  const devices = model.pending.rows;
  const subs = model.stores.rows.filter(needsDecision).sort(byUrgency);
  const count = taskCount(model);
  const loading = model.pending.loading || model.stores.loading;
  const failed = model.pending.error || model.stores.error;
  return html`
    <main class="page">
      <div class="page-head">
        <div>
          <h1>المهام</h1>
          <p class="lead">
            ${
              count
                ? html`<span class="mark">${arabicCount(count, "مهمة واحدة", "مهمتان", "مهام", "مهمة")}</span> تنتظرك:
                  ${arabicCount(devices.length, "جهاز واحد", "جهازان", "أجهزة", "جهازًا")}
                  و${arabicCount(subs.length, "اشتراك واحد", "اشتراكان", "اشتراكات", "اشتراكًا")}.`
                : "لا مهام الآن. كل المتاجر تعمل."
            }
          </p>
        </div>
        ${write ? html`<a class="btn" href="#/create">إنشاء حساب متجر</a>` : nothing}
      </div>

      <section class="section" aria-label="قائمة المهام">
        ${loadState(model.pending)}
        ${loadState(model.stores)}
        ${
          count
            ? html`<ol class="list">
                ${devices.map((device) =>
                  deviceTask(
                    device,
                    model.stores.rows.find((store) => store.id === device.storeId),
                    write,
                    actions,
                  ),
                )}
                ${subs.map((store) => subscriptionTask(store, write, actions))}
              </ol>`
            : !loading && !failed
              ? empty(
                  "لا أجهزة تنتظر ولا اشتراكات تنتهي خلال سبعة أيام. تظهر الطلبات هنا فور تسجيلها.",
                )
              : nothing
        }
        ${devices.length ? html`<p class="small muted">صاحب المتجر غالبًا ينتظر الآن على هاتفه.</p>` : nothing}
        ${
          model.pending.complete
            ? nothing
            : html`<p class="small muted">عُرض جزء من طلبات الأجهزة؛ العدد قد يكون أكبر.</p>`
        }
        ${
          model.stores.complete
            ? nothing
            : html`<p class="small muted">القائمة كبيرة: عُرضت أول ${fmt(model.stores.rows.length)} متجر. استخدم البحث في «المتاجر» للوصول إلى الباقي.</p>`
        }
      </section>

      ${platformNumbers(model)}
      ${recentActions(model)}
    </main>
  `;
}
