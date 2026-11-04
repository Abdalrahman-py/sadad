import { html, nothing } from "lit-html";
import type { PendingInstallation, StoreRow } from "../api";
import { dateTime, fmt } from "../format";
import { canWrite, type Model, type QueueActions } from "../model";
import { arabicCount, byUrgency, needsDecision, subscriptionState } from "../subscription";
import { KIND_LABELS } from "./audit";
import { empty, loadState, metric } from "./common";

const slots = (store: StoreRow | undefined) => {
  if (!store) return null;
  const used = store.activeDevices;
  const limit = store.allowedDevices;
  return used >= limit
    ? { full: true, text: `الحد ممتلئ: ${fmt(used)} من ${fmt(limit)} أجهزة معتمدة` }
    : { full: false, text: `يصبح ${fmt(used + 1)} من ${fmt(limit)} أجهزة` };
};

const deviceCard = (
  device: PendingInstallation,
  store: StoreRow | undefined,
  write: boolean,
  actions: QueueActions,
) => {
  const slot = slots(store);
  return html`
    <article class="card card-tight">
      <div class="spread">
        <div class="grow col">
          <a class="cell-link" href=${`#/stores/${device.storeId}/devices`}>
            <b>${device.storeName}</b>
          </a>
          <span class="small muted">
            ${[device.staffName, device.label].filter(Boolean).join(" · ") || "جهاز"} ·
            ${dateTime(device.createdAt)}
          </span>
          ${
            slot
              ? html`<span class="small ${slot.full ? "loud-warn" : "muted"}">${slot.text}</span>`
              : nothing
          }
        </div>
        ${
          write
            ? html`<div class="btn-group">
                ${
                  slot?.full && store
                    ? html`<button class="btn btn-primary" type="button"
                        @click=${() => actions.raiseLimitAndApprove(device, store)}>رفع الحد إلى ${fmt(store.allowedDevices + 1)} واعتماد</button>
                      <a class="btn btn-soft" href=${`#/stores/${device.storeId}/devices`}>استبدال جهاز قديم</a>`
                    : html`<button class="btn btn-primary" type="button"
                        @click=${() => actions.quickApprove(device)}>اعتماد الجهاز</button>`
                }
                <button class="btn btn-ghost" type="button"
                  @click=${() => actions.quickReject(device)}>رفض</button>
              </div>`
            : nothing
        }
      </div>
    </article>`;
};

const subRow = (store: StoreRow, write: boolean, actions: QueueActions) => {
  const state = subscriptionState(store);
  const ended = state.tone === "danger";
  return html`
    <div class="list-row">
      <div class="grow col">
        <a class="cell-link" href=${`#/stores/${store.id}/subscription`}><b>${store.name}</b></a>
        <span class="small ${ended ? "loud-danger" : "tone-warn"}">${state.queue}</span>
      </div>
      ${
        write
          ? html`<div class="btn-group">
              ${
                ended
                  ? html`<button class="btn btn-primary" type="button"
                      @click=${() => actions.quickExtend(store, 30)}>تجديد 30 يومًا وتفعيل</button>`
                  : html`<button class="btn btn-soft" type="button"
                      @click=${() => actions.quickExtend(store, 30)}>تمديد 30 يومًا</button>`
              }
            </div>`
          : nothing
      }
    </div>`;
};

const recentActions = (model: Model) => html`
  <section class="card card-tight">
    <div class="spread baseline">
      <h2 class="row-title">آخر إجراءات الإدارة</h2>
      <a class="btn btn-ghost btn-sm" href="#/audit">السجل كاملًا</a>
    </div>
    ${loadState(model.audit)}
    ${
      model.audit.rows.length
        ? html`<div class="events push">
            ${model.audit.rows.slice(0, 5).map(
              (event) => html`<div class="event">
                <b>${event.kind ? (KIND_LABELS[event.kind] ?? event.kind) : "قراءة مسجلة"} · ${event.actor}</b>
                <small>${event.storeId ? `المتجر ${fmt(event.storeId)} · ` : ""}${dateTime(event.createdAt)}</small>
              </div>`,
            )}
          </div>`
        : model.audit.loading
          ? nothing
          : html`<p class="small muted push-sm">لا إجراءات مسجلة بعد.</p>`
    }
  </section>
`;

export function tasksView(model: Model, actions: QueueActions) {
  const write = canWrite(model);
  const devices = model.pending.rows;
  const subs = model.stores.rows.filter(needsDecision).sort(byUrgency);
  const count = devices.length + subs.length;
  const tasks = arabicCount(count, "مهمة واحدة", "مهمتان", "مهام", "مهمة");
  const summary = count
    ? `${tasks} تنتظرك · ${arabicCount(
        devices.length,
        "جهاز واحد",
        "جهازان",
        "أجهزة",
        "جهازًا",
      )} و${arabicCount(subs.length, "اشتراك واحد", "اشتراكان", "اشتراكات", "اشتراكًا")}.`
    : "لا مهام الآن. كل المتاجر تعمل.";
  const overview = model.overview;
  return html`
    <main class="page">
      <div class="page-head">
        <div class="title-group">
          <h1>المهام</h1>
          <p class="muted">${summary}</p>
        </div>
        ${
          write
            ? html`<a class="btn btn-primary btn-lg" href="#/create">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"
                  stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
                إنشاء حساب متجر</a>`
            : nothing
        }
      </div>

      <div class="columns">
        <div class="main">
          <section class="section" aria-labelledby="devices-h">
            <div class="card-head">
              <h2 id="devices-h">أجهزة تنتظر الاعتماد</h2>
              <span class="small muted">صاحب المتجر غالبًا ينتظر الآن على هاتفه</span>
            </div>
            ${loadState(model.pending)}
            ${devices.map((device) =>
              deviceCard(
                device,
                model.stores.rows.find((store) => store.id === device.storeId),
                write,
                actions,
              ),
            )}
            ${
              !devices.length && !model.pending.loading && !model.pending.error
                ? empty("لا أجهزة تنتظر. تظهر الطلبات هنا فور تسجيلها.")
                : nothing
            }
          </section>

          <section class="section" aria-labelledby="subs-h">
            <div class="card-head"><h2 id="subs-h">اشتراكات تحتاج قرارًا</h2></div>
            ${loadState(model.stores)}
            ${
              subs.length
                ? html`<div class="list">${subs.map((store) => subRow(store, write, actions))}</div>`
                : !model.stores.loading && !model.stores.error
                  ? empty("لا اشتراكات تنتهي خلال سبعة أيام.")
                  : nothing
            }
          </section>
        </div>

        <aside class="aside">
          <section class="card" aria-label="أرقام المنصة">
            ${
              overview
                ? html`<div class="grid-2">
                    ${metric("متاجر نشطة", html`${fmt(overview.active)}`, `من ${fmt(overview.stores)}`)}
                    ${metric("موثّقة", fmt(overview.verified))}
                    ${metric("مجمّدة", fmt(overview.suspended))}
                    ${metric("أجهزة تنتظر", fmt(overview.pendingDevices))}
                  </div>
                  ${overview.archived ? html`<p class="small muted push">${fmt(overview.archived)} حساب مؤرشف.</p>` : nothing}`
                : html`<p class="loading">جارٍ التحميل…</p>`
            }
          </section>
          ${recentActions(model)}
          ${
            model.stores.complete
              ? nothing
              : html`<p class="small muted">القائمة كبيرة: عُرضت أول ${fmt(model.stores.rows.length)} متجر. استخدم البحث في «المتاجر» للوصول إلى الباقي.</p>`
          }
        </aside>
      </div>
    </main>
  `;
}
