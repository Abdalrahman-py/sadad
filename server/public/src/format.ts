// Number, money and date display. Money arrives in integer cents.
const numberFormat = new Intl.NumberFormat("ar-PS-u-nu-latn", { maximumFractionDigits: 2 });
const dateTimeFormat = new Intl.DateTimeFormat("ar-PS-u-nu-latn", {
  dateStyle: "short",
  timeStyle: "short",
});
const dayFormat = new Intl.DateTimeFormat("ar-PS-u-nu-latn", { day: "numeric", month: "long" });
const dayYearFormat = new Intl.DateTimeFormat("ar-PS-u-nu-latn", {
  day: "numeric",
  month: "long",
  year: "numeric",
});

export const DAY_MS = 86_400_000;

export const fmt = (value: number | null | undefined): string => numberFormat.format(value ?? 0);

export const money = (cents: number | null | undefined): string => `${fmt((cents ?? 0) / 100)} ₪`;

/** Accepts epoch milliseconds or an ISO string; an unparsable value shows a dash. */
export function dateTime(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : dateTimeFormat.format(parsed);
}

/** "31 أكتوبر" — for the day an account's subscription ends. */
export function day(value: number | string | null | undefined): string {
  const parsed = value === null || value === undefined ? new Date(Number.NaN) : new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : dayFormat.format(parsed);
}

/** "31 أكتوبر 2026" — the same day with its year, when the year matters. */
export function fullDay(value: number | string | null | undefined): string {
  const parsed = value === null || value === undefined ? new Date(Number.NaN) : new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : dayYearFormat.format(parsed);
}

/** Whole days left, rounded up; 0 when the time has already passed. */
export const daysLeft = (ms: number | null | undefined): number =>
  Math.max(0, Math.ceil((ms ?? 0) / DAY_MS));

/** Arabic-Indic and Persian digits to ASCII, so pasted numbers parse. */
export const englishDigits = (value: unknown): string =>
  String(value ?? "")
    .replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d)))
    .replace(/[۰-۹]/g, (d) => String("۰۱۲۳۴۵۶۷۸۹".indexOf(d)));

/** A whole number within bounds, or an Error with an Arabic message. */
export function integer(raw: FormDataEntryValue | null, min: number, max: number): number {
  const value = Number(englishDigits(raw).trim());
  if (!englishDigits(raw).trim() || !Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(`أدخل عددًا صحيحًا من ${min} إلى ${max}.`);
  }
  return value;
}
