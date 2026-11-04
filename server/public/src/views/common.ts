// Small pieces the views share. lit-html escapes every interpolated value; no view builds HTML
// from strings.
import { html, nothing, type TemplateResult } from "lit-html";
import { fmt } from "../format";
import type { PageStep } from "../model";
import type { Tone } from "../subscription";

/** A submit handler that reads the form once and never lets the browser post it. */
export const onSubmit = (run: (form: FormData) => void) => (event: SubmitEvent) => {
  event.preventDefault();
  run(new FormData(event.currentTarget as HTMLFormElement));
};

export const pager = (
  label: string,
  page: { cursors: (string | undefined)[]; next: string | null; loading: boolean },
  step: (step: PageStep) => void,
): TemplateResult => html`
  <nav class="pager" aria-label=${label}>
    <button type="button" class="btn btn-soft btn-sm" ?disabled=${page.cursors.length <= 1 || page.loading}
      @click=${() => step("prev")}>السابق</button>
    <span class="small muted" role="status">صفحة ${fmt(page.cursors.length)}</span>
    <button type="button" class="btn btn-soft btn-sm" ?disabled=${!page.next || page.loading}
      @click=${() => step("next")}>التالي</button>
  </nav>
`;

export const loadState = (state: { loading: boolean; error: string }) =>
  state.error
    ? html`<p class="form-error" role="alert">${state.error}</p>`
    : state.loading
      ? html`<p class="loading" role="status">جارٍ التحميل…</p>`
      : nothing;

export const empty = (text: string) => html`<p class="empty">${text}</p>`;

export const pill = (text: string, tone: Tone = "ok") =>
  html`<span class=${`pill pill-${tone}`}>${text}</span>`;

/** A number with its label above; `note` is the small line beside the number. */
export const metric = (label: string, value: TemplateResult | string, note?: string) =>
  html`<div class="metric"><span>${label}</span><b>${value}${note ? html` <small>${note}</small>` : nothing}</b></div>`;

const searchTimers = new Map<string, ReturnType<typeof setTimeout>>();

/** A search box that asks once the typing pauses. */
export function searchBox(label: string, value: string, search: (q: string) => void) {
  return html`<label class="search">
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"
      stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>
    <input type="search" aria-label=${label} placeholder=${label} .value=${value} maxlength="120"
      @input=${(event: InputEvent) => {
        const q = (event.currentTarget as HTMLInputElement).value.trim();
        clearTimeout(searchTimers.get(label));
        searchTimers.set(
          label,
          setTimeout(() => search(q), 300),
        );
      }}>
  </label>`;
}

/** Copies `text` and says so on the button itself; no state to keep. */
export const copyButton = (text: string, label = "نسخ") => {
  const reset = (button: HTMLButtonElement) => {
    button.textContent = label;
  };
  return html`<button type="button" class="btn btn-primary btn-sm" @click=${(event: Event) => {
    const button = event.currentTarget as HTMLButtonElement;
    button.textContent = "…";
    navigator.clipboard
      .writeText(text)
      .then(() => {
        button.textContent = "نُسخ";
        setTimeout(() => reset(button), 2200);
      })
      .catch(() => {
        button.textContent = "تعذّر النسخ";
        setTimeout(() => reset(button), 3000);
      });
  }}>${label}</button>`;
};

export const PERMISSIONS = {
  contacts: "جهات الاتصال",
  debts: "الديون",
  payments: "الدفعات",
  reports: "الكشوفات والتقارير",
  analytics: "الإحصائيات",
  export: "التصدير",
  whatsapp: "WhatsApp",
  appLock: "قفل التطبيق",
} as const;

export type Permission = keyof typeof PERMISSIONS;

export const permissionBoxes = (granted: Record<string, boolean>) => html`
  <fieldset>
    <legend>ما يستطيع المتجر فعله</legend>
    <div class="check-grid">
      ${Object.entries(PERMISSIONS).map(
        ([
          name,
          label,
        ]) => html`<label class="check"><input type="checkbox" name="perm" value=${name}
          .checked=${granted[name] !== false}>${label}</label>`,
      )}
    </div>
  </fieldset>
`;

/** Every permission, true when its box is ticked. */
export function readPermissions(form: FormData): Record<Permission, boolean> {
  const ticked = new Set(form.getAll("perm"));
  return Object.fromEntries(
    Object.keys(PERMISSIONS).map((name) => [name, ticked.has(name)]),
  ) as Record<Permission, boolean>;
}

export const INSTALLATION_STATE = {
  pending: "بانتظار الاعتماد",
  active: "معتمد",
  revoked: "ملغى",
} as const;

export const DEVICE_TONE = {
  pending: "warn",
  active: "ok",
  revoked: "muted",
} as const;
