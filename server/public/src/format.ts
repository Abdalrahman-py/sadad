// Number, money and date display. Money arrives in integer cents.
const numberFormat = new Intl.NumberFormat("ar-PS", { maximumFractionDigits: 2 });
const dateTimeFormat = new Intl.DateTimeFormat("ar-PS", { dateStyle: "short", timeStyle: "short" });

export const fmt = (value: number | null | undefined): string => numberFormat.format(value ?? 0);

export const money = (cents: number | null | undefined): string => `${fmt((cents ?? 0) / 100)} ₪`;

/** Accepts epoch milliseconds or an ISO string; an unparsable value shows a dash. */
export function dateTime(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : dateTimeFormat.format(parsed);
}

export function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "دائم";
  const left = Math.max(0, ms);
  if (!left) return "منتهٍ";
  const days = Math.floor(left / 86_400_000);
  const hours = Math.floor((left % 86_400_000) / 3_600_000);
  return days ? `${fmt(days)} يوم و${fmt(hours)} ساعة` : `${fmt(hours)} ساعة`;
}

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
