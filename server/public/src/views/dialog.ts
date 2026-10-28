import { html, nothing } from "lit-html";
import type { Actions, Dialog } from "../model";
import { onSubmit } from "./common";

const close = (actions: Actions) => html`
  <button type="button" class="btn-icon" aria-label="إغلاق" @click=${actions.closeDialog}>×</button>`;

const submit = (
  dialog: Dialog,
  actions: Actions,
  label: string,
  danger = false,
  disabled = false,
) => html`
  <p class="form-error" role="alert">${dialog.error}</p>
  <div class="dialog-actions">
    <button type="button" class="btn btn-soft" @click=${actions.closeDialog}>إلغاء</button>
    <button class=${`btn ${danger ? "btn-danger-solid" : "btn-primary"}`}
      ?disabled=${dialog.busy || disabled}>${label}</button>
  </div>
`;

function body(dialog: Dialog, actions: Actions) {
  switch (dialog.kind) {
    case "reason":
      return html`
        <form class="dialog-card stack" @submit=${onSubmit(() => actions.submitDialog())}>
          <div class="dialog-head"><h2 id="dlg-title">${dialog.title}</h2>${close(actions)}</div>
          <p class="muted">${dialog.description}</p>
          <label class="field">سبب الإجراء
            <textarea name="reason" rows="3" maxlength="2000" required autofocus .value=${dialog.value}
              @input=${(event: InputEvent) =>
                actions.dialogReason(
                  (event.currentTarget as HTMLTextAreaElement).value,
                )}></textarea>
            <span class="hint">يُسجَّل في سجل الإدارة مع اسمك.</span>
          </label>
          ${submit(dialog, actions, dialog.confirm, dialog.danger, dialog.value.trim() === "")}
        </form>`;
    case "danger":
      return html`
        <form class="dialog-card stack" @submit=${onSubmit(() => actions.submitDialog())}>
          <div class="dialog-head">
            <h2 id="dlg-title">${dialog.title}</h2>${close(actions)}
          </div>
          <p class="muted">${dialog.description}</p>
          ${
            dialog.needsReason
              ? html`<label class="field">سبب الإجراء
                  <textarea name="reason" rows="2" maxlength="2000" required autofocus .value=${dialog.reason}
                    @input=${(event: InputEvent) =>
                      actions.dialogReason(
                        (event.currentTarget as HTMLTextAreaElement).value,
                      )}></textarea>
                </label>`
              : nothing
          }
          <label class="field">
            <span>للتأكيد، اكتب اسم الدخول <b dir="ltr">${dialog.username}</b></span>
            <input name="typed" dir="ltr" autocomplete="off" required .value=${dialog.typed}
              @input=${(event: InputEvent) =>
                actions.dialogTyped((event.currentTarget as HTMLInputElement).value)}>
          </label>
          ${submit(
            dialog,
            actions,
            dialog.confirm,
            true,
            dialog.typed.trim() !== dialog.username || dialog.reason.trim() === "",
          )}
        </form>`;
    case "confirm":
      return html`
        <form class="dialog-card stack" @submit=${onSubmit(() => actions.submitDialog())}>
          <div class="dialog-head"><h2 id="dlg-title">${dialog.title}</h2>${close(actions)}</div>
          <p class="muted">${dialog.description}</p>
          ${submit(dialog, actions, dialog.confirm, true)}
        </form>`;
    default:
      return nothing;
  }
}

/** controller.ts opens it with showModal() after render; Escape and × both close it. */
export const dialogView = (dialog: Dialog, actions: Actions) => html`
  <dialog class="dialog" aria-labelledby="dlg-title" @close=${actions.closeDialog}>
    ${body(dialog, actions)}
  </dialog>
`;
