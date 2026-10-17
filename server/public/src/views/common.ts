// Small pieces the views share. lit-html escapes every interpolated value; no view builds HTML
// from strings.
import { html, nothing, type TemplateResult } from "lit-html";
import { fmt } from "../format";
import type { Paged, PageStep } from "../model";

/** A submit handler that reads the form once and never lets the browser post it. */
export const onSubmit = (run: (form: FormData) => void) => (event: SubmitEvent) => {
  event.preventDefault();
  run(new FormData(event.currentTarget as HTMLFormElement));
};

export const pager = (
  label: string,
  page: Pick<Paged<unknown>, "cursors" | "next" | "loading">,
  step: (step: PageStep) => void,
): TemplateResult => html`
  <nav class="table-pagination" aria-label=${label}>
    <button type="button" class="button quiet" ?disabled=${page.cursors.length <= 1 || page.loading}
      @click=${() => step("prev")}>السابق</button>
    <span class="small muted" role="status">صفحة ${fmt(page.cursors.length)}</span>
    <button type="button" class="button quiet" ?disabled=${!page.next || page.loading}
      @click=${() => step("next")}>التالي</button>
  </nav>
`;

export const loadState = (page: { loading: boolean; error: string }) =>
  page.error
    ? html`<p class="form-error" role="alert">${page.error}</p>`
    : page.loading
      ? html`<p class="small muted" role="status">جارٍ التحميل…</p>`
      : nothing;

export const metric = (label: string, value: string | number, mini = false) =>
  html`<article class=${mini ? "mini-metric" : "metric"}><span>${label}</span><b>${value}</b></article>`;

const searchTimers = new Map<string, ReturnType<typeof setTimeout>>();

/** A search box that asks once the typing pauses. */
export function searchBox(label: string, value: string, search: (q: string) => void) {
  return html`<input class="search" type="search" aria-label=${label} placeholder=${label}
    .value=${value} maxlength="120"
    @input=${(event: InputEvent) => {
      const q = (event.currentTarget as HTMLInputElement).value.trim();
      clearTimeout(searchTimers.get(label));
      searchTimers.set(
        label,
        setTimeout(() => search(q), 300),
      );
    }}>`;
}

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
  <fieldset><legend>الميزات المسموحة</legend><div class="checks">
    ${Object.entries(PERMISSIONS).map(
      ([name, label]) => html`<label><input type="checkbox" name="perm" value=${name}
        .checked=${granted[name] !== false}>${label}</label>`,
    )}
  </div></fieldset>
`;

/** Every permission, true when its box is ticked. */
export function readPermissions(form: FormData): Record<Permission, boolean> {
  const ticked = new Set(form.getAll("perm"));
  return Object.fromEntries(
    Object.keys(PERMISSIONS).map((name) => [name, ticked.has(name)]),
  ) as Record<Permission, boolean>;
}

export const subscriptionText = (store: {
  subscriptionMode: "permanent" | "timed" | "paused";
  subscriptionRemainingMs: number | null;
}): string =>
  store.subscriptionMode === "permanent"
    ? "فعال دائمًا"
    : store.subscriptionMode === "paused"
      ? "موقوف"
      : (store.subscriptionRemainingMs ?? 0) > 0
        ? "مدة محددة"
        : "منتهٍ";

export const INSTALLATION_STATE = {
  pending: "بانتظار اعتمادك",
  active: "هاتف معتمد",
  revoked: "أُلغي الاعتماد",
} as const;
